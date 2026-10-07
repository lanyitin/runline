package dev.lawlan.runline.engine.allowlist

import dev.lawlan.runline.analyzer.AllowList
import dev.lawlan.runline.analyzer.SafetyAnalyzer
import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.artifact.ReasonDoc
import dev.lawlan.runline.engine.artifact.ReasonKind
import dev.lawlan.runline.engine.artifact.toDoc
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Who a rejudging is written for and when; absent when it is only a preview. */
internal data class WriteContext(val by: String, val at: Instant)

/**
 * Judges every stored definition again with an allow list, using the analyzer on the jar kept in
 * the database; nothing is decided here. One jar is read, analysed and (when [write] is given)
 * written at a time, so memory holds a single jar, but the whole pass runs inside the caller's
 * session and so inside its transaction.
 */
internal class Rejudger(private val analyzer: SafetyAnalyzer, private val scratchDir: Path) {
  fun run(session: AllowListSession, allowList: AllowList, write: WriteContext?): Impact {
    val changes = mutableListOf<VerdictChange>()
    var artifactCount = 0
    var definitionCount = 0
    var unreadable = 0
    for (artifact in session.artifacts()) {
      val stored = session.definitionsOf(artifact.id)
      artifactCount++
      if (stored.isEmpty()) continue
      val judged = judge(session, artifact, stored, allowList)
      for ((definition, outcome) in judged) {
        definitionCount++
        if (outcome.unreadable) unreadable++
        val revoke =
            outcome.verdict == Verdict.UNSAFE &&
                definition.verdict == Verdict.SAFE &&
                definition.allowUnsafeExecution
        if (outcome.verdict != definition.verdict) {
          changes +=
              VerdictChange(
                  artifact.contentHash,
                  artifact.uploader,
                  definition.name,
                  definition.className,
                  definition.verdict,
                  outcome.verdict,
                  definition.allowUnsafeExecution,
                  revoke,
              )
        }
        write?.let {
          session.rejudge(
              definition.id,
              NewJudgement(
                  outcome.verdict,
                  outcome.reasons,
                  allowList.version,
                  revoke,
                  it.by,
                  it.at,
              ),
          )
        }
      }
    }
    return Impact(artifactCount, definitionCount, changes, unreadable)
  }

  private class Outcome(
      val verdict: Verdict,
      val reasons: List<ReasonDoc>,
      val unreadable: Boolean = false,
  )

  private fun judge(
      session: AllowListSession,
      artifact: ArtifactRef,
      stored: List<JudgedDefinition>,
      allowList: AllowList,
  ): List<Pair<JudgedDefinition, Outcome>> {
    val jar = Files.createTempFile(scratchDir, "rejudge-", ".jar")
    try {
      session.copyArtifact(artifact.id, jar)
      val report =
          try {
            analyzer.analyze(jar, allowList)
          } catch (e: IOException) {
            null
          }
      return stored.map { definition ->
        val pipeline = report?.pipelines?.firstOrNull { it.className == definition.className }
        definition to
            if (pipeline == null) unreadable(definition)
            else Outcome(pipeline.verdict, pipeline.reasons.map { it.toDoc() })
      }
    } finally {
      Files.deleteIfExists(jar)
    }
  }

  /**
   * The analyzer could not give a verdict for a definition it was able to read at upload (its jar
   * or its declaration is not readable now). It cannot be shown to be safe, so it is unsafe, for
   * that reason.
   */
  private fun unreadable(definition: JudgedDefinition) =
      Outcome(
          Verdict.UNSAFE,
          listOf(
              ReasonDoc(
                  ReasonKind.UNREADABLE_CLASS,
                  className = definition.className,
                  path = listOf(definition.className),
                  detail = "重判時無法讀取這個 pipeline 的 jar 或宣告，無法確認安全。",
              )
          ),
          unreadable = true,
      )
}
