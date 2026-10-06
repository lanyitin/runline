package dev.lawlan.runline.devkit

import dev.lawlan.runline.analyzer.AllowList
import dev.lawlan.runline.analyzer.AllowListText
import dev.lawlan.runline.analyzer.PipelineSafety
import dev.lawlan.runline.analyzer.SafetyReport
import dev.lawlan.runline.analyzer.UnsafeReason

/**
 * Words the analyzer's verdict for a developer. Everything comes from the analyzer's own result;
 * nothing is analysed or decided here.
 */
internal object VerdictText {
  private const val EXIT_NOTE =
      "說明：此 pipeline 含 JVM 結束呼叫。被允許執行的 unsafe pipeline 可以終止整個程序；" + "開發入口不攔截，執行到該呼叫時本機的開發程序會一併結束。"

  fun render(
      report: SafetyReport,
      pipeline: PipelineSafety,
      allowList: AllowListDisplay? = null,
  ): String = buildString {
    appendLine("Pipeline: ${pipeline.className} (name: ${pipeline.pipelineName})")
    appendLine("Verdict: ${pipeline.verdict}")
    appendLine("Allow list version: ${report.allowListVersion}")
    allowList?.let { appendAllowList(it) }
    if (pipeline.reasons.isNotEmpty()) {
      appendLine("Reasons:")
      pipeline.reasons.forEach { reason -> describe(reason).forEach { appendLine("  $it") } }
    }
    appendLine("Limitations: ${report.limitations}")
    if (pipeline.reasons.any { it is UnsafeReason.JvmExit }) appendLine(EXIT_NOTE)
  }

  private fun StringBuilder.appendAllowList(display: AllowListDisplay) {
    val source =
        when (display.source) {
          AllowListSource.DEFAULT -> "default"
          AllowListSource.OVERRIDE -> "override (RUNLINE_ALLOW_LIST)"
        }
    appendLine(
        "Allow list: $source, version ${display.allowList.version}, " +
            "${display.allowList.entries.size} entries"
    )
    appendLine(
        "Note: this is the development entry point's list, not the Engine's current allow list; " +
            "an administrator may have changed the Engine's list since."
    )
    if (display.listEntries) {
      appendLine("Allow list entries (the form RUNLINE_ALLOW_LIST reads):")
      display.allowList.entries.forEach { appendLine("  ${AllowListText.formatEntry(it)}") }
    }
  }

  private fun describe(reason: UnsafeReason): List<String> =
      when (reason) {
        is UnsafeReason.UnrestrictedAccess ->
            listOf("- No limit on ${reason.category} access (missing or declared unrestricted)")
        is UnsafeReason.NotAllowListed ->
            listOf("- Not on the allow list: ${reason.className}", "    path: ${path(reason.path)}")
        is UnsafeReason.JvmExit ->
            listOf(
                "- Refers to a JVM exit member: ${reason.member}",
                "    path: ${path(reason.path)}",
            )
        is UnsafeReason.IoSensitiveMember ->
            listOf(
                "- Refers to an IO sensitive member: ${reason.member}",
                "    path: ${path(reason.path)}",
            )
        is UnsafeReason.UnreadableClass ->
            listOf(
                "- Class file could not be read: ${reason.className} (${reason.detail})",
                "    path: ${path(reason.path)}",
            )
        is UnsafeReason.LimitExceeded -> listOf("- Analysis limit exceeded: ${reason.detail}")
      }

  private fun path(path: List<String>) = path.joinToString(" -> ")
}

/**
 * What the verdict output says about the allow list used: where it came from and, on request, what
 * is in it.
 */
data class AllowListDisplay(
    val source: AllowListSource,
    val allowList: AllowList,
    val listEntries: Boolean,
)
