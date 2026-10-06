package dev.lawlan.runline.devkit

import dev.lawlan.runline.runner.RecordedIo
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files

/**
 * What the development entry says and writes about a recording run: the notice before it, and
 * afterwards the event file, the proposal file and the proposal on the console. Locations are shown
 * as the user configured them, never as host paths.
 */
internal class RecordingReport(private val config: RecordingConfig, private val out: PrintStream) {
  fun announce() {
    out.println(
        "[recording] 錄製模式已開啟：metadata 宣告的檔案範圍與讀寫、網路、外部行程的限制已放寬，" +
            "所有動作允許並記錄；目錄邊界（絕對路徑、跳出範圍、符號連結逃逸）與磁碟用量上限仍然生效。" +
            "此次執行不代表一般模式下的結果。"
    )
  }

  fun missing() {
    out.println("[recording] 這次執行沒有產生錄製結果，因此沒有提案。")
  }

  fun publish(
      runId: String,
      className: String,
      pipelineName: String,
      recording: RecordedIo,
      runSucceeded: Boolean,
  ) {
    val events = EventLogText.render(recording)
    val proposal =
        ProposalText.render(
            className,
            pipelineName,
            MetadataProposal.from(recording),
            recording,
            runSucceeded,
        )
    out.println(
        "[recording] 共 ${recording.total} 筆動作" +
            if (recording.total > recording.events.size) {
              "（逐筆保存前 ${recording.events.size} 筆，其餘已彙總）"
            } else {
              ""
            }
    )
    try {
      val directory = config.directory.resolve(runId)
      Files.createDirectories(directory)
      Files.writeString(directory.resolve(EVENTS_FILE), events)
      Files.writeString(directory.resolve(PROPOSAL_FILE), proposal)
      out.println("[recording] 事件：${config.shownAs}/$runId/$EVENTS_FILE")
      out.println("[recording] 提案：${config.shownAs}/$runId/$PROPOSAL_FILE")
    } catch (e: IOException) {
      out.println(
          "[recording] 無法寫入錄製輸出到 ${config.shownAs}/$runId：${e.javaClass.simpleName}: ${e.message}"
      )
    }
    out.print(proposal)
  }

  private companion object {
    const val EVENTS_FILE = "events.txt"
    const val PROPOSAL_FILE = "proposal.md"
  }
}
