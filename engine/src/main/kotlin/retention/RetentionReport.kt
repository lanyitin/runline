package dev.lawlan.runline.engine.retention

/** What one pass of the clean-up removed, and whether it ran to the end. */
data class RetentionReport(
    val runs: Int = 0,
    val logEntries: Int = 0,
    val webhookFirings: Int = 0,
    val cronFirings: Int = 0,
    /** More expired rows were left for the next pass, because this one reached its batch limit. */
    val backlogRemains: Boolean = false,
    /** The steps that failed; the others were done regardless. */
    val failedSteps: List<RetentionStep> = emptyList(),
) {
  val succeeded: Boolean
    get() = failedSteps.isEmpty()
}

enum class RetentionStep(val label: String) {
  LOG_ENTRIES("log_entries"),
  RUNS("runs"),
  WEBHOOK_FIRINGS("webhook_firings"),
  CRON_FIRINGS("cron_firings"),
}
