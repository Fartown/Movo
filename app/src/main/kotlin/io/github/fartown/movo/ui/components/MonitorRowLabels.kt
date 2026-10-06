package io.github.fartown.movo.ui.components

import android.content.Context
import android.content.res.Resources
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.monitor.MonitorTime
import io.github.fartown.movo.agent.monitor.MonitorEndReason
import io.github.fartown.movo.ui.model.MonitorEventMessageUi
import io.github.fartown.movo.ui.model.MonitorEventKindUi

internal object MonitorRowLabels {
    fun label(context: Context, row: MonitorEventMessageUi, nowMillis: Long = System.currentTimeMillis()): String {
        val resources = context.resources
        val time = MonitorTime.clock(context, row.atMillis, nowMillis)
        if (row.kind == MonitorEventKindUi.Event) return resources.getString(R.string.monitor_row_event, row.name, time)
        return when (row.reason) {
            MonitorEndReason.STOPPED_BY_USER.name, MonitorEndReason.STOPPED_BY_AGENT.name, MonitorEndReason.SESSION_END.name ->
                resources.getString(R.string.monitor_row_stopped, row.name, time)
            MonitorEndReason.TIMEOUT.name ->
                resources.getString(R.string.monitor_row_timeout, row.name, durationLabel(resources, row.limitMs ?: 0L))
            MonitorEndReason.RATE_LIMIT.name -> resources.getString(R.string.monitor_row_rate_limit, row.name)
            INTERRUPTED -> resources.getString(R.string.monitor_row_interrupted, row.name)
            // 正常退出（0）不写退出码，用户看不懂也不需要；异常退出才写。
            else -> row.exitCode?.takeIf { it != 0 }?.let { resources.getString(R.string.monitor_row_exit_code, row.name, it, time) }
                ?: resources.getString(R.string.monitor_row_exit, row.name, time)
        }
    }

    /** 「45 秒」「30 分钟」「2 小时」：不足 1 分钟按秒写。 */
    fun durationLabel(resources: Resources, ms: Long): String {
        if (ms < 60_000L) return resources.getString(R.string.monitor_duration_seconds, (ms / 1_000).coerceAtLeast(1).toInt())
        val minutes = ms / 60_000
        return if (minutes % 60 == 0L) {
            resources.getString(R.string.monitor_duration_hours, (minutes / 60).toInt())
        } else {
            resources.getString(R.string.monitor_duration_minutes, minutes.toInt())
        }
    }

    /** 进程被系统杀掉后补的「监听已中断」行的原因。 */
    const val INTERRUPTED = "INTERRUPTED"
}
