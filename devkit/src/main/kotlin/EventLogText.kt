package dev.lawlan.runline.devkit

import dev.lawlan.runline.core.IoCategory
import dev.lawlan.runline.runner.RecordedIo

/**
 * The recorded events as text, one per line. Nothing here is more than the recording holds: scope
 * and relative path, host and port, command; no content, no arguments, no absolute path.
 */
internal object EventLogText {
  fun render(recording: RecordedIo): String = buildString {
    appendLine("# 錄製事件")
    appendLine()
    appendLine("錄製模式：metadata 宣告的限制已放寬，這次執行不代表一般模式下的結果。")
    appendLine()
    if (recording.total > recording.events.size) {
      appendLine(
          "共 ${recording.total} 筆動作；逐筆保存前 ${recording.events.size} 筆（上限 ${recording.maxEvents}），" +
              "其餘只計入彙總。"
      )
      appendLine()
    }
    recording.events.forEach { e ->
      val place =
          when (e.category) {
            IoCategory.FILE -> "${e.scope} ${e.access} ${e.target}"
            IoCategory.NETWORK -> "${e.access} ${e.target}:${e.port}"
            IoCategory.PROCESS -> "${e.access} ${e.target}"
          }
      appendLine("#${e.sequence} ${e.category} $place${if (e.rejected) " 被拒絕" else ""}")
    }
    appendLine()
    appendLine("## 彙總（含未逐筆保存者；檔案不列路徑，主機不分大小寫、不含埠號）")
    appendLine()
    recording.summary.forEach { s ->
      val place =
          when (s.category) {
            IoCategory.FILE -> "${s.scope} ${s.access}"
            else -> "${s.access} ${s.target}"
          }
      appendLine(
          "${s.category} $place ${s.count} 次（#${s.firstSequence} 至 #${s.lastSequence}）" +
              if (s.rejected) " 被拒絕" else ""
      )
    }
  }
}
