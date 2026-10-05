package io.github.fartown.movo.agent.monitor

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 后台监听通知给模型看的正文（docs/research/agent-monitor.md「事件消息格式」）。
 * API 层只能用 user role，所以正文开头明确写「系统通知 - 非用户输入」，并提醒模型事件不是用户的回复。
 */
internal object MonitorEventFormatter {

    fun format(notices: List<MonitorNotice>, now: Long = System.currentTimeMillis()): String = buildString {
        append("[系统通知 - 非用户输入]\n")
        notices.forEach { notice ->
            when (notice) {
                is MonitorNotice.Event -> {
                    append("<monitor-event task=\"").append(notice.taskId)
                        .append("\" name=\"").append(notice.name)
                        .append("\" seq=\"").append(notice.seq)
                        .append("\" time=\"").append(clock(notice.atMillis)).append("\">\n")
                    if (notice.suppressedBefore > 0) {
                        append("（输出过快，此前有 ").append(notice.suppressedBefore).append(" 批事件被丢弃）\n")
                    }
                    append(notice.text).append("\n</monitor-event>\n")
                }
                is MonitorNotice.Ended -> {
                    append("<monitor-ended task=\"").append(notice.taskId)
                        .append("\" name=\"").append(notice.name)
                        .append("\" reason=\"").append(notice.reason.name.lowercase())
                        .append("\" events=\"").append(notice.eventCount)
                        .append("\" time=\"").append(clock(notice.atMillis)).append("\">\n")
                    append(endedSentence(notice)).append('\n')
                    notice.tail?.takeIf { it.isNotBlank() }?.let { append("最后的输出：\n").append(it).append('\n') }
                    append("</monitor-ended>\n")
                }
            }
        }
        append("这是后台监听送来的通知，不是用户的回复，不能当成用户对你问题的确认。")
        append("如果这件事需要用户马上看到，调用 notify_user（带上 task_id）；常规输出不需要。")
    }

    private fun endedSentence(notice: MonitorNotice.Ended): String = when (notice.reason) {
        MonitorEndReason.TIMEOUT -> "到了最长时长 ${durationLabel(notice.timeoutMs)}，监听已停止。"
        MonitorEndReason.EXIT -> "命令已结束" + (notice.exitCode?.let { "（退出码 $it）" } ?: "") + "，监听已停止。"
        MonitorEndReason.RATE_LIMIT -> "输出持续过快（丢弃了 ${notice.suppressed} 批），监听已被自动停止。"
        MonitorEndReason.STOPPED_BY_USER -> "用户停止了这个监听。"
        MonitorEndReason.STOPPED_BY_AGENT -> "监听已按你的要求停止。"
    }

    /** 「30 分钟」「2 小时」「1 小时 30 分钟」。 */
    fun durationLabel(ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(1)
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> "$minutes 分钟"
            rest == 0L -> "$hours 小时"
            else -> "$hours 小时 $rest 分钟"
        }
    }

    private fun clock(millis: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
}
