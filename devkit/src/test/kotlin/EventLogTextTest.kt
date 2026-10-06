package dev.lawlan.runline.devkit

import dev.lawlan.runline.core.FileScope
import dev.lawlan.runline.core.IoAccess
import dev.lawlan.runline.core.IoCategory
import dev.lawlan.runline.runner.RecordedEvent
import dev.lawlan.runline.runner.RecordedIo
import dev.lawlan.runline.runner.RecordedSummary
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventLogTextTest {
  private val events =
      listOf(
          RecordedEvent(
              1,
              IoCategory.FILE,
              FileScope.PIPELINE_SHARED,
              "a/b.txt",
              null,
              IoAccess.WRITE,
              false,
          ),
          RecordedEvent(2, IoCategory.NETWORK, null, "localhost", 8080, IoAccess.WRITE, false),
          RecordedEvent(3, IoCategory.PROCESS, null, "echo", null, IoAccess.WRITE, false),
          RecordedEvent(
              4,
              IoCategory.FILE,
              FileScope.RUN_PRIVATE,
              "../x",
              null,
              IoAccess.READ,
              true,
          ),
      )

  private fun summaryOf(e: RecordedEvent) =
      RecordedSummary(
          e.category,
          e.scope,
          e.target,
          e.access,
          e.rejected,
          1,
          e.sequence,
          e.sequence,
      )

  private fun recording(total: Long = 4, kept: List<RecordedEvent> = events) =
      RecordedIo(100, total, kept, events.map(::summaryOf))

  @Test
  fun `shows each event in order with its category, target and access`() {
    val lines = EventLogText.render(recording()).lines()

    assertTrue("#1 FILE PIPELINE_SHARED WRITE a/b.txt" in lines, lines.toString())
    assertTrue("#2 NETWORK WRITE localhost:8080" in lines, lines.toString())
    assertTrue("#3 PROCESS WRITE echo" in lines, lines.toString())
    assertTrue(
        lines.indexOf("#1 FILE PIPELINE_SHARED WRITE a/b.txt") <
            lines.indexOf("#3 PROCESS WRITE echo")
    )
  }

  @Test
  fun `marks a refused boundary violation`() {
    val text = EventLogText.render(recording())

    assertTrue("#4 FILE RUN_PRIVATE READ ../x 被拒絕" in text, text)
  }

  @Test
  fun `says the limits were relaxed`() {
    val text = EventLogText.render(recording())

    assertTrue("錄製模式" in text && "放寬" in text && "不代表一般模式" in text, text)
  }

  @Test
  fun `tells when only the first events were kept and the rest was summarized`() {
    val text = EventLogText.render(recording(total = 1000, kept = events))

    assertTrue("共 1000 筆" in text && "逐筆保存前 4 筆" in text, text)
    assertTrue("彙總" in text, text)
  }

  @Test
  fun `does not mention truncation when everything was kept`() {
    val text = EventLogText.render(recording())

    assertFalse("逐筆保存前" in text, text)
  }

  @Test
  fun `the summary shows counts and the sequence span`() {
    val text =
        EventLogText.render(
            RecordedIo(
                100,
                50,
                emptyList(),
                listOf(
                    RecordedSummary(
                        IoCategory.FILE,
                        FileScope.RUN_PRIVATE,
                        "",
                        IoAccess.READ,
                        false,
                        50,
                        1,
                        50,
                    )
                ),
            )
        )

    assertTrue("FILE RUN_PRIVATE READ 50 次（#1 至 #50）" in text, text)
  }
}
