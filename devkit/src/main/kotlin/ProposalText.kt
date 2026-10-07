package dev.lawlan.runline.devkit

import dev.lawlan.runline.core.FileMode
import dev.lawlan.runline.core.FileScope
import dev.lawlan.runline.core.IoAccess
import dev.lawlan.runline.core.IoCategory
import dev.lawlan.runline.runner.RecordedIo
import dev.lawlan.runline.runner.RecordedSummary

/**
 * The proposal document: what the recording showed, how far that reaches, and the members to put
 * into the pipeline's `@PipelineDefinition` (Kotlin and Java forms, so they can be pasted).
 */
internal object ProposalText {
  fun render(
      className: String,
      pipelineName: String,
      proposal: MetadataProposal,
      recording: RecordedIo,
      runSucceeded: Boolean,
  ): String = buildString {
    appendLine("# Metadata 提案：$className（name = \"$pipelineName\"）")
    appendLine()
    appendLine(
        "此提案來自錄製模式的一次執行。錄製模式放寬了 metadata 宣告的限制（檔案範圍與讀寫、網路、" + "外部行程皆不受限，所有動作允許並記錄），這次執行不代表一般模式下的結果。"
    )
    if (!runSucceeded) {
      appendLine()
      appendLine("這次執行未成功，提案只反映它失敗之前做過的 IO。")
    }
    appendLine()
    appendLine("## 涵蓋限度")
    appendLine()
    appendLine("- 只涵蓋這次執行走過的路徑；沒走到的分支用到的 IO 不在其中，提案可能過窄。")
    appendLine("- 只涵蓋經由 context 的 IO；直接使用 JDK 的 IO 類別不被錄製，這類 IO 只會出現在靜態分析的 unsafe 判定。")
    appendLine("- 共享資源只含用過的型別化資源（名稱與型別）；容量與只宣告名稱的資源、參數（parameters）與 trigger 不在其中，請保留你原有的宣告。")
    appendLine("- 提案不會自動套用，請自行檢視後採用。")
    appendRejected(recording)
    appendLine()
    appendLine("## 依據")
    appendLine()
    appendLine("| 類別 | 提案 | 依據 |")
    appendLine("|---|---|---|")
    appendEvidence(proposal, recording)
    appendLine()
    appendLine("## 採用")
    appendLine()
    appendLine(
        "把下列成員（用過型別化資源時多一個 typedResources）放進 pipeline 的 `@PipelineDefinition`，取代原有的同名成員（沒有則新增），"
    )
    appendLine("其他成員（name、parameters、resources）保持原樣。沒有使用過的類別是「不允許」（空的範圍），不是「不限制」。")
    appendLine()
    appendLine("Kotlin：")
    appendLine()
    appendLine("```kotlin")
    append(kotlinMembers(proposal))
    appendLine("```")
    appendLine()
    appendLine("Java：")
    appendLine()
    appendLine("```java")
    append(javaMembers(proposal))
    appendLine("```")
  }

  private fun StringBuilder.appendRejected(recording: RecordedIo) {
    val rejected = recording.summary.filter { it.rejected }.sumOf { it.count }
    if (rejected > 0) {
      appendLine("- 錄製期間有 $rejected 筆檔案動作因違反目錄邊界（絕對路徑、跳出範圍、符號連結逃逸）被拒絕；" + "它們已記錄在事件檔，但不納入提案。")
    }
  }

  private fun StringBuilder.appendEvidence(proposal: MetadataProposal, recording: RecordedIo) {
    val used = recording.summary.filter { !it.rejected }
    if (proposal.files.isEmpty()) appendLine("| 檔案 | 不允許（未使用） | |")
    sortedFiles(proposal).forEach { (scope, mode) ->
      val uses = used.filter { it.category == IoCategory.FILE && it.scope == scope }
      val reads = uses.filter { it.access == IoAccess.READ }.sumOf { it.count }
      val writes = uses.filter { it.access == IoAccess.WRITE }.sumOf { it.count }
      appendLine("| 檔案 | $scope: $mode | 讀 $reads 次、寫 $writes 次 |")
    }
    appendTargets("網路", proposal.hosts, used, IoCategory.NETWORK, lowercase = true)
    appendTargets("外部行程", proposal.commands, used, IoCategory.PROCESS, lowercase = false)
    proposal.resources.forEach { (name, type) ->
      val count =
          used.filter { it.category == IoCategory.RESOURCE && it.target == name }.sumOf { it.count }
      appendLine("| 資源 | $name: $type | $count 次 |")
    }
  }

  private fun StringBuilder.appendTargets(
      label: String,
      targets: Collection<String>,
      used: List<RecordedSummary>,
      category: IoCategory,
      lowercase: Boolean,
  ) {
    if (targets.isEmpty()) appendLine("| $label | 不允許（未使用） | |")
    targets.forEach { target ->
      val count =
          used
              .filter {
                it.category == category &&
                    (if (lowercase) it.target.lowercase() else it.target) == target
              }
              .sumOf { it.count }
      appendLine("| $label | $target | $count 次 |")
    }
  }

  private fun sortedFiles(proposal: MetadataProposal): List<Pair<FileScope, FileMode>> =
      proposal.files.toList().sortedBy { it.first.ordinal }

  private fun kotlinMembers(proposal: MetadataProposal): String = buildString {
    val files = sortedFiles(proposal)
    if (files.isEmpty()) {
      appendLine("files = [],")
    } else {
      appendLine("files = [")
      files.forEach { (scope, mode) ->
        appendLine("    FileAccess(scope = FileScope.$scope, mode = FileMode.$mode),")
      }
      appendLine("],")
    }
    appendLine("network = AccessLimit(allow = ${kotlinList(proposal.hosts)}),")
    appendLine("processes = AccessLimit(allow = ${kotlinList(proposal.commands)}),")
    if (proposal.resources.isNotEmpty()) {
      appendLine(
          "typedResources = [" +
              proposal.resources.entries.joinToString(", ") {
                "TypedResource(name = ${quoted(it.key)}, type = ${quoted(it.value)})"
              } +
              "],"
      )
    }
  }

  private fun javaMembers(proposal: MetadataProposal): String = buildString {
    val files = sortedFiles(proposal)
    if (files.isEmpty()) {
      appendLine("files = {},")
    } else {
      appendLine("files = {")
      appendLine(
          files.joinToString(",\n") { (scope, mode) ->
            "    @FileAccess(scope = FileScope.$scope, mode = FileMode.$mode)"
          }
      )
      appendLine("},")
    }
    appendLine("network = @AccessLimit(allow = ${javaList(proposal.hosts)}),")
    if (proposal.resources.isEmpty()) {
      appendLine("processes = @AccessLimit(allow = ${javaList(proposal.commands)})")
    } else {
      appendLine("processes = @AccessLimit(allow = ${javaList(proposal.commands)}),")
      appendLine(
          "typedResources = {" +
              proposal.resources.entries.joinToString(", ") {
                "@TypedResource(name = ${quoted(it.key, kotlin = false)}, type = ${quoted(it.value, kotlin = false)})"
              } +
              "}"
      )
    }
  }

  private fun quoted(value: String, kotlin: Boolean = true): String =
      "\"" +
          value.replace("\\", "\\\\").replace("\"", "\\\"").let {
            if (kotlin) it.replace("$", "\\$") else it
          } +
          "\""

  private fun kotlinList(values: Collection<String>) =
      values.joinToString(", ", "[", "]") {
        "\"${it.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")}\""
      }

  private fun javaList(values: Collection<String>) =
      values.joinToString(", ", "{", "}") {
        "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\""
      }
}
