package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.analyzer.AccessLimitMetadata
import dev.lawlan.runline.analyzer.PipelineMetadata
import dev.lawlan.runline.analyzer.PipelineSafety
import dev.lawlan.runline.analyzer.SafetyAnalyzer
import java.io.IOException
import java.time.Clock

enum class RejectionReason {
  NOT_A_JAR,
  CORE_CLASSES_BUNDLED,
  NO_PIPELINE_FOUND,
  METADATA_UNREADABLE,
  DUPLICATE_PIPELINE_NAME,
  INVALID_PIPELINE_NAME,
  JAR_TOO_MANY_ENTRIES,
  JAR_ENTRY_TOO_LARGE,
  JAR_EXPANDED_TOO_LARGE,
}

sealed interface UploadResult {
  /** Stored as a new version. */
  data class Created(val artifact: ArtifactRecord) : UploadResult

  /** The same content was uploaded before; the stored version is returned and nothing changed. */
  data class Existing(val artifact: ArtifactRecord) : UploadResult

  /** Refused. [message] explains why and what the author can do; nothing was stored. */
  data class Rejected(val reason: RejectionReason, val message: String) : UploadResult
}

/**
 * Accepts an uploaded jar: has the analyzer discover its pipelines without running them, judge each
 * one and read its metadata, and stores the artifact with all definitions in one transaction.
 * Verdicts and metadata always come from the analyzer module; nothing is read or decided here.
 */
class UploadService(
    private val guard: JarExpansionGuard,
    private val nameRule: PipelineNameRule,
    private val analyzer: SafetyAnalyzer,
    private val allowLists: AllowListProvider,
    private val store: ArtifactStore,
    private val telemetry: UploadTelemetry,
    private val clock: Clock,
) {
  fun upload(staged: StagedJar, uploader: String): UploadResult =
      telemetry.observe(uploader) { process(staged, uploader) }

  private fun process(staged: StagedJar, uploader: String): UploadResult {
    store.findByHash(staged.contentHash)?.let {
      return UploadResult.Existing(it)
    }
    // Nothing reads the jar's contents before the guard has seen that they stay within bounds.
    try {
      guard.check(staged.path)?.let {
        return it.toRejection()
      }
    } catch (e: IOException) {
      return notAJar()
    }
    // The allow list can change while the jar is being judged; the store refuses a verdict made
    // under a list that is no longer current, and the jar is judged again.
    repeat(MAX_ATTEMPTS) {
      judgeAndStore(staged, uploader)?.let {
        return it
      }
    }
    error("The allow list changed $MAX_ATTEMPTS times while the upload was being judged")
  }

  /** Null when the allow list changed between judging and storing. */
  private fun judgeAndStore(staged: StagedJar, uploader: String): UploadResult? {
    val report =
        try {
          analyzer.analyze(staged.path, allowLists.current())
        } catch (e: IOException) {
          return notAJar()
        }
    if (report.bundledCoreClasses.isNotEmpty()) return coreBundled(report.bundledCoreClasses)
    report.metadataProblems.firstOrNull()?.let {
      return rejected(
          RejectionReason.METADATA_UNREADABLE,
          "無法解析 pipeline 的 metadata：Cannot read the metadata of ${it.className}: ${it.detail}",
      )
    }
    if (report.pipelines.isEmpty()) return nothingFound(report.unreadableClasses)
    val definitions = report.pipelines.map { it.toDefinition(report.allowListVersion) }
    val badNames = definitions.mapNotNull { d -> nameRule.violation(d.name)?.let { d.name to it } }
    if (badNames.isNotEmpty()) {
      return rejected(
          RejectionReason.INVALID_PIPELINE_NAME,
          "pipeline 名稱不合規，整個 jar 不接受：" +
              badNames.joinToString("；") { (name, why) -> "「$name」（$why）" } +
              "。pipeline 名稱會成為 run 的目錄名稱，請改用字母、數字、'.'、'_'、'-'（'.' 與 '..' 除外）。",
      )
    }
    definitions
        .groupBy { it.name }
        .filterValues { it.size > 1 }
        .keys
        .firstOrNull()
        ?.let {
          return rejected(
              RejectionReason.DUPLICATE_PIPELINE_NAME,
              "同一個 jar 內有多個 pipeline 使用相同名稱「$it」，名稱必須唯一。",
          )
        }
    val artifact =
        NewArtifact(
            staged.contentHash,
            staged.path,
            staged.sizeBytes,
            uploader,
            clock.instant(),
            definitions,
            requiredAllowListVersion = report.allowListVersion,
        )
    return when (val saved = store.saveIfAbsent(artifact)) {
      is SaveResult.Created -> UploadResult.Created(saved.artifact)
      is SaveResult.AlreadyExists -> UploadResult.Existing(saved.artifact)
      SaveResult.AllowListChanged -> null
    }
  }

  private companion object {
    const val MAX_ATTEMPTS = 5
  }

  private fun PipelineSafety.toDefinition(allowListVersion: String) =
      NewDefinition(
          className,
          pipelineName,
          metadata.toDoc(),
          verdict,
          reasons.map { it.toDoc() },
          allowListVersion,
      )

  private fun notAJar() = rejected(RejectionReason.NOT_A_JAR, "上傳的檔案不是有效的 jar（壓縮檔無法開啟）。")

  private fun JarLimitViolation.toRejection() =
      when (this) {
        is JarLimitViolation.TooManyEntries ->
            rejected(RejectionReason.JAR_TOO_MANY_ENTRIES, "jar 內的項目數量超過上限 $max 個。")
        is JarLimitViolation.EntryTooLarge ->
            rejected(
                RejectionReason.JAR_ENTRY_TOO_LARGE,
                "jar 內的項目「$entry」解壓後超過單一項目大小上限 $max bytes。",
            )
        is JarLimitViolation.TotalTooLarge ->
            rejected(RejectionReason.JAR_EXPANDED_TOO_LARGE, "jar 解壓後的總大小超過上限 $max bytes。")
      }

  private fun rejected(reason: RejectionReason, message: String) =
      UploadResult.Rejected(reason, message)

  private fun coreBundled(classes: List<String>) =
      rejected(
          RejectionReason.CORE_CLASSES_BUNDLED,
          "jar 內含 dev.lawlan.runline.core 套件的類別（例如 ${classes.first()}）。" +
              "Run 一律使用 Runner 提供的 core，不接受 jar 夾帶的版本。" +
              "請以不夾帶 core 的方式重新打包（core 只作為編譯期相依，不納入 jar）後再上傳。",
      )

  private fun nothingFound(unreadable: List<String>): UploadResult.Rejected {
    return if (unreadable.isEmpty()) {
      rejected(
          RejectionReason.NO_PIPELINE_FOUND,
          "jar 內找不到任何 pipeline：沒有類別以 @PipelineDefinition 宣告為 pipeline。",
      )
    } else {
      rejected(
          RejectionReason.METADATA_UNREADABLE,
          "jar 內找不到可用的 pipeline，且下列類別檔無法解析：${unreadable.joinToString()}。",
      )
    }
  }

  private fun PipelineMetadata.toDoc() =
      MetadataDoc(
          parameters = parameters.map { ParameterDoc(it.name, it.required, it.default) },
          files = files.map { FileAccessDoc(it.scope, it.mode) },
          network = network.toDoc(),
          processes = processes.toDoc(),
          resources = resources,
      )

  private fun AccessLimitMetadata.toDoc() = AccessLimitDoc(unrestricted, allow)
}
