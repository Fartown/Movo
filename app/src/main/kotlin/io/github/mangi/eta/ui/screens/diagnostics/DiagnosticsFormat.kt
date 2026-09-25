package io.github.mangi.eta.ui.screens.diagnostics

import io.github.mangi.eta.agent.runtime.modelFailureHint
import io.github.mangi.eta.diagnostics.CurrentStep
import io.github.mangi.eta.diagnostics.DiagnosticEntry
import io.github.mangi.eta.diagnostics.DiagnosticTraceBuilder
import io.github.mangi.eta.diagnostics.RequestTrace
import io.github.mangi.eta.diagnostics.RunFailure
import io.github.mangi.eta.diagnostics.RunTrace
import io.github.mangi.eta.diagnostics.SystemEvent
import io.github.mangi.eta.diagnostics.SystemEventKind
import io.github.mangi.eta.diagnostics.TimeBreakdown
import io.github.mangi.eta.diagnostics.TimelineItem
import io.github.mangi.eta.diagnostics.ToolTrace
import io.github.mangi.eta.diagnostics.TraceStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 失败原因的人话解释：原因 + 说明 + 建议，可带一个操作。 */
internal data class FailureExplanation(
    val title: String,
    val detail: String,
    val advice: String,
    val action: FailureAction?,
)

internal enum class FailureAction(val label: String) {
    MODEL_SETTINGS("去模型设置"),
    BACKGROUND_SETTINGS("去后台设置"),
}

/** 耗时构成的一段；[kind] 决定颜色。 */
internal data class BreakdownPart(val kind: BreakdownKind, val label: String, val ms: Long)

internal enum class BreakdownKind { WAITING, RETRY, RECEIVING, TOOL, COMPACTION, OTHER }

/** 导出文件头里的设备与版本信息。 */
internal data class ExportHeader(val device: String, val android: String, val app: String)

/**
 * 运行日志的文案与数值格式；界面和导出共用同一套措辞（功能定义第 4 节“时间与单位统一”）。
 *
 * 单位：列表和结论写「18.4 秒」；时间线和耗时构成沿用执行详情页的紧凑写法「3.7s」，相对时间写「+3.4s」。
 */
internal class DiagnosticsFormat(
    private val toolName: (String) -> String = { it },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
    private val dayFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(zone)
    private val stampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)

    fun clock(time: Long): String = clockFormat.format(Instant.ofEpochMilli(time))
    fun stamp(time: Long): String = stampFormat.format(Instant.ofEpochMilli(time))

    /** 今天只写时刻，其他日子带日期。 */
    fun dayClock(time: Long, today: LocalDate = LocalDate.now(zone)): String =
        if (day(time) == today) clock(time) else dayFormat.format(Instant.ofEpochMilli(time))

    fun day(time: Long): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

    fun dayTitle(day: LocalDate, today: LocalDate = LocalDate.now(zone)): String = when (day) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${day.monthValue}月${day.dayOfMonth}日"
    }

    // ---- 数值 ----

    fun duration(ms: Long): String = when {
        ms < 1_000 -> "$ms 毫秒"
        ms < 60_000 -> String.format(Locale.ROOT, "%.1f 秒", ms / 1_000.0)
        else -> "${ms / 60_000} 分 ${(ms % 60_000) / 1_000} 秒"
    }

    fun compact(ms: Long): String = when {
        ms < 1_000 -> "${ms}ms"
        ms < 60_000 -> String.format(Locale.ROOT, "%.1fs", ms / 1_000.0)
        else -> "${ms / 60_000}m${(ms % 60_000) / 1_000}s"
    }

    fun offset(ms: Long): String = when {
        ms < 50 -> "+0s"
        ms < 60_000 -> String.format(Locale.ROOT, "+%.1fs", ms / 1_000.0)
        else -> "+${ms / 60_000}m${(ms % 60_000) / 1_000}s"
    }

    fun timer(ms: Long): String {
        val seconds = (ms / 1_000).coerceAtLeast(0)
        return if (seconds >= 3_600) {
            String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds % 3_600 / 60, seconds % 60)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
        }
    }

    fun seconds(ms: Long): String = "${(ms / 1_000).coerceAtLeast(0)} 秒"

    fun tokens(count: Long): String = if (count < 1_000) "$count" else String.format(Locale.ROOT, "%.1fK", count / 1_000.0)

    fun bytes(count: Long): String = when {
        count < 1024 -> "$count B"
        count < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", count / 1024.0)
        else -> String.format(Locale.ROOT, "%.1f MB", count / 1024.0 / 1024.0)
    }

    // ---- 任务 ----

    fun runTitle(run: RunTrace, conversationTitle: String?): String =
        conversationTitle?.takeIf { it.isNotBlank() } ?: "任务 ${run.id}"

    fun stepLabel(step: CurrentStep): String = when (step) {
        is CurrentStep.WaitingModel -> "等待模型回复"
        is CurrentStep.Receiving -> "正在接收回答"
        is CurrentStep.Tool -> "执行工具 · ${toolName(step.tool.name)}"
        is CurrentStep.RetryWait -> "等待重试"
        is CurrentStep.Preparing -> "准备中"
    }

    /** 列表每行的一句话结论。 */
    fun listSubtitle(run: RunTrace, nowElapsed: Long): String = "${clock(run.startedAt)} · " + when (run.status) {
        TraceStatus.RUNNING -> {
            val step = run.currentStep()
            "${stepLabel(step)} · 已 ${seconds(nowElapsed - step.sinceElapsed)}"
        }
        TraceStatus.FAILED, TraceStatus.INTERRUPTED -> explain(run)?.title.orEmpty()
        TraceStatus.CANCELLED -> "已停止"
        TraceStatus.SUCCEEDED -> buildList {
            add("${run.modelRequests} 次请求")
            if (run.tools.isNotEmpty()) add("${run.tools.size} 次工具")
        }.joinToString(" · ")
    }

    fun listValue(run: RunTrace, nowElapsed: Long): String =
        run.durationMs?.let(::duration) ?: timer(nowElapsed - run.startElapsed)

    fun statusLine(run: RunTrace): String = when (run.status) {
        TraceStatus.RUNNING -> {
            val step = run.currentStep()
            val request = (step as? CurrentStep.WaitingModel)?.request ?: (step as? CurrentStep.Receiving)?.request
            listOfNotNull(
                stepLabel(step),
                request?.displayRound?.let { "第 $it 轮" },
                request?.takeIf { it.isRetry }?.let { "重试第 ${(it.attempt ?: 2) - 1} 次" },
            ).joinToString(" · ")
        }
        TraceStatus.SUCCEEDED -> listOfNotNull("完成", run.lastRound?.let { "共 $it 轮" }).joinToString(" · ")
        TraceStatus.FAILED -> listOfNotNull("失败", run.failure?.round?.let { "第 $it 轮模型请求" }).joinToString(" · ")
        TraceStatus.INTERRUPTED -> "中断"
        TraceStatus.CANCELLED -> "已停止"
    }

    fun summaryTimer(run: RunTrace, nowElapsed: Long): String =
        run.durationMs?.let { "用时 ${duration(it)}" } ?: timer(nowElapsed - run.startElapsed)

    fun summaryMeta(run: RunTrace): String = buildList {
        add("${dayClock(run.startedAt)} 开始")
        run.durationMs?.let { add("${clock(run.startedAt + it)} 结束") }
        add(run.id)
    }.joinToString(" · ")

    /**
     * 进行中任务的一句提示（Q3）：多久没收到数据；超过 [STALL_MS] 时提醒网络可能不稳定。
     * [silenceMs] 为空表示正在执行工具，或任务不在本进程里。
     */
    fun runningHint(run: RunTrace, nowElapsed: Long, silenceMs: Long?): String = when {
        silenceMs != null && silenceMs >= STALL_MS -> "已 ${seconds(silenceMs)}没有收到数据，网络可能不稳定"
        silenceMs != null && silenceMs >= 1_000 -> "${seconds(silenceMs)}前收到过数据，一切正常"
        silenceMs != null -> "正在收到数据"
        else -> "${stepLabel(run.currentStep())}，已 ${seconds(nowElapsed - run.currentStep().sinceElapsed)}"
    }

    // ---- 失败原因（Q1） ----

    fun explain(run: RunTrace): FailureExplanation? = when (run.status) {
        TraceStatus.INTERRUPTED -> FailureExplanation(
            title = "App 进程被结束，任务中断",
            detail = "任务还没完成时 Movo 的进程被系统结束了，常见于切到后台后被系统回收。",
            advice = "允许 Movo 在后台运行，再重新发送",
            action = FailureAction.BACKGROUND_SETTINGS,
        )
        TraceStatus.FAILED -> explain(run.failure ?: RunFailure(null, null, null, null, 0, null), run.timeline)
        else -> null
    }

    fun explain(failure: RunFailure, timeline: List<TimelineItem>): FailureExplanation {
        val code = failure.code
        val http = failure.httpStatus ?: code?.removePrefix("HTTP_")?.toIntOrNull()
        val detail = when {
            code == null -> failure.causes?.let { "出错位置：$it" } ?: "任务在执行中出错。"
            failure.failedAttempts > 1 -> "连续 ${failure.failedAttempts} 次请求都失败，自动重试已用完。"
            failure.round != null -> "第 ${failure.round} 轮的模型请求失败，这类错误不会自动重试。"
            else -> "模型请求失败。"
        }
        fun result(title: String, advice: String, action: FailureAction? = null) = FailureExplanation(title, detail, advice, action)
        val events = timeline.filterIsInstance<TimelineItem.System>().map { it.event }
        fun networkAdvice(title: String): FailureExplanation = when {
            events.any { it.kind == SystemEventKind.NETWORK && it.title == "网络断开" } ->
                result(title, "请求期间网络断开了，检查网络后重试")
            events.any { (it.kind == SystemEventKind.APP && it.title == "App 进入后台") || it.title == "设备进入休眠" } ->
                result(title, "Movo 在后台时系统可能限制了网络；保持在前台，或允许它后台运行", FailureAction.BACKGROUND_SETTINGS)
            else -> result(title, "检查网络后重试，或换个网络试试")
        }
        return when {
            failure.providerErrorCode in QUOTA_CODES -> result("账户额度不足（HTTP ${http ?: "?"}）", "检查服务商账户的额度或账单，或换一个模型", FailureAction.MODEL_SETTINGS)
            http == 401 -> result("模型接口认证失败（HTTP 401）", "重新登录，或检查 API Key", FailureAction.MODEL_SETTINGS)
            http == 403 -> result("模型接口拒绝访问（HTTP 403）", "检查账户和模型权限", FailureAction.MODEL_SETTINGS)
            http == 404 -> result("模型或接口不存在（HTTP 404）", "检查模型名称和接口地址", FailureAction.MODEL_SETTINGS)
            http == 400 -> result("模型请求参数无效（HTTP 400）", "检查模型配置；反复出现请导出日志反馈", FailureAction.MODEL_SETTINGS)
            http == 429 -> result("模型接口限流（HTTP 429）", "稍后再试，或换一个模型", FailureAction.MODEL_SETTINGS)
            http != null && http in 500..599 -> result("模型服务暂时不可用（HTTP $http）", "服务端出了问题，稍后再试")
            http != null && code?.startsWith("HTTP_") == true -> result("模型接口返回错误（HTTP $http）", "稍后再试；反复出现请导出日志反馈")
            code == "CONTEXT_OVERFLOW" -> result("上下文超过模型容量", "压缩上下文，或新开一个对话")
            code == "MODEL_TIMEOUT" -> networkAdvice("等待模型回复超时")
            code == "MODEL_CONNECTION_FAILED" -> networkAdvice("网络连接中断")
            code == "STREAM_INCOMPLETE" -> networkAdvice("回答没有正常结束")
            code == "PROVIDER_STREAM_ERROR" -> result("服务商返回错误", "稍后再试，或换一个模型", FailureAction.MODEL_SETTINGS)
            code != null -> result(modelFailureHint(code), "重新发送；反复出现请导出日志反馈")
            else -> result("任务执行出错", "重新发送；反复出现请导出日志反馈")
        }
    }

    // ---- 耗时构成（Q2） ----

    fun breakdownParts(breakdown: TimeBreakdown): List<BreakdownPart> = listOf(
        BreakdownPart(BreakdownKind.WAITING, "等模型开始回答", breakdown.waitingMs),
        BreakdownPart(BreakdownKind.RETRY, "重试等待", breakdown.retryWaitMs),
        BreakdownPart(BreakdownKind.RECEIVING, "接收回答", breakdown.receivingMs),
        BreakdownPart(BreakdownKind.TOOL, "执行工具", breakdown.toolMs),
        BreakdownPart(BreakdownKind.COMPACTION, "上下文压缩", breakdown.compactionMs),
        BreakdownPart(BreakdownKind.OTHER, "准备与本地处理", breakdown.otherMs),
    ).filter { it.ms >= 50 }

    // ---- 时间线 ----

    fun requestTitle(request: RequestTrace): String = when {
        request.purpose == "COMPACTION" -> "模型请求 · 上下文压缩"
        request.purpose == "REPLY_REWRITE" -> "模型请求 · 改写回复"
        request.isRetry -> "模型请求 · 第 ${request.displayRound ?: "?"} 轮 · 重试第 ${(request.attempt ?: 2) - 1} 次"
        request.displayRound != null -> "模型请求 · 第 ${request.displayRound} 轮"
        else -> "模型请求"
    }

    fun requestSubtitle(request: RequestTrace, offsetMs: Long): String = (listOf(offset(offsetMs)) + when (request.status) {
        TraceStatus.SUCCEEDED -> {
            val duration = request.durationMs ?: 0L
            val first = request.firstDataMs?.coerceAtMost(duration)
            if (first == null) listOf("完成") else listOf("等首字 ${compact(first)}", "接收 ${compact(duration - first)}")
        }
        TraceStatus.FAILED -> listOfNotNull(
            request.httpStatus?.takeIf { it >= 400 }?.let { "HTTP $it" } ?: request.errorCode?.let(::shortError),
            request.retryDelayMs?.let { "${it / 1_000}s 后重试" } ?: "不再重试",
        )
        TraceStatus.RUNNING -> listOf(if (request.firstDataMs == null) "等待首字" else "接收中")
        TraceStatus.CANCELLED -> listOf("已取消")
        TraceStatus.INTERRUPTED -> listOf("中断")
    }).joinToString(" · ")

    fun requestDetail(request: RequestTrace): String = buildList {
        if (request.phases.isNotEmpty()) add(request.phases.joinToString(" · ") { "${it.label} ${compact(it.offsetMs)}" })
        add(listOfNotNull(
            request.httpStatus?.let { "状态：HTTP $it" },
            request.serverRequestId?.let { "请求 ID：$it" },
        ).joinToString(" · "))
        add(listOfNotNull(
            "服务：${request.provider}",
            request.receivedBytes?.let { "收到 ${bytes(it)}" },
        ).joinToString(" · "))
        if (request.errorCode != null) {
            add(listOfNotNull("错误码：${request.errorCode}", request.providerErrorCode?.let { "服务商代码：$it" }).joinToString(" · "))
        }
    }.filter { it.isNotBlank() }.joinToString("\n")

    fun toolTitle(tool: ToolTrace): String = "工具 · ${toolName(tool.name)}"

    fun toolSubtitle(tool: ToolTrace, offsetMs: Long): String =
        listOfNotNull(offset(offsetMs), if (tool.success == false) "失败" else null).joinToString(" · ")

    fun compactionSubtitle(item: TimelineItem.Compaction): String = listOfNotNull(
        offset(item.offsetMs),
        if (item.tokensBefore != null && item.tokensAfter != null) "${tokens(item.tokensBefore)} → ${tokens(item.tokensAfter)} tokens" else null,
    ).joinToString(" · ")

    fun systemSubtitle(event: SystemEvent, offsetMs: Long): String =
        "${offset(offsetMs)} · ${event.detail.ifBlank { "系统事件" }}"

    private fun shortError(code: String): String = when (code) {
        "MODEL_TIMEOUT" -> "等待超时"
        "MODEL_CONNECTION_FAILED" -> "连接中断"
        "STREAM_INCOMPLETE" -> "回答未结束"
        "PROVIDER_STREAM_ERROR" -> "服务商错误"
        "CONTEXT_OVERFLOW" -> "上下文超限"
        else -> modelFailureHint(code)
    }

    // ---- 导出（Q5） ----

    /**
     * 导出为 Markdown：结论在前、原始事件在后，交给没看过界面的人也能回答 Q1–Q4。
     * 不含会话标题；原始事件里的会话与界面任务 ID 一并去掉。
     */
    fun exportMarkdown(
        header: ExportHeader,
        runs: List<RunTrace>,
        system: List<SystemEvent>,
        raw: List<DiagnosticEntry>,
        generatedAt: Long,
        scope: String,
    ): String = buildString {
        appendLine("# Movo 运行日志")
        appendLine()
        appendLine("- 导出时间：${stamp(generatedAt)}")
        appendLine("- 范围：$scope")
        appendLine("- 设备：${header.device} · ${header.android}")
        appendLine("- App：${header.app}")
        appendLine("- 说明：只包含请求阶段、工具名和设备状态，不含对话内容、会话标题、工具参数和结果、API Key。")
        runs.forEach { run ->
            appendLine()
            append(exportRun(run))
        }
        if (system.isNotEmpty()) {
            appendLine()
            appendLine("## 系统事件")
            appendLine()
            system.forEach { event ->
                appendLine("- ${stamp(event.timeMillis)} ${event.title}" + event.detail.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
            }
        }
        appendLine()
        appendLine("## 原始事件")
        appendLine()
        appendLine("```text")
        raw.forEach { entry ->
            appendLine(entry.copy(details = entry.details.lineSequence()
                .filterNot { it.startsWith("conversation=") || it.startsWith("wire_run=") }
                .joinToString("\n")).text())
        }
        appendLine("```")
    }

    fun exportRun(run: RunTrace): String = buildString {
        appendLine("## ${run.id} · ${statusLine(run)} · ${run.durationMs?.let(::duration) ?: "进行中"}")
        appendLine()
        appendLine("- 开始：${stamp(run.startedAt)}")
        explain(run)?.let {
            appendLine("- 原因：${it.title}。${it.detail}")
            appendLine("- 建议：${it.advice}")
        }
        run.breakdown()?.let { breakdown ->
            appendLine("- 耗时：" + breakdownParts(breakdown).joinToString(" · ") { "${it.label} ${duration(it.ms)}" })
        }
        appendLine()
        run.timeline.forEach { item ->
            when (item) {
                is TimelineItem.Request -> {
                    appendLine("- ${requestTitle(item.request)} · ${item.request.durationMs?.let(::compact) ?: "进行中"} · ${requestSubtitle(item.request, item.offsetMs)}")
                    requestDetail(item.request).lineSequence().forEach { appendLine("  - $it") }
                }
                is TimelineItem.Tool -> appendLine("- ${toolTitle(item.tool)}（${item.tool.name}） · " +
                    "${item.tool.durationMs?.let(::compact) ?: "进行中"} · ${toolSubtitle(item.tool, item.offsetMs)}")
                is TimelineItem.Compaction -> appendLine("- 上下文压缩 · ${compactionSubtitle(item)}")
                is TimelineItem.System -> appendLine("- ${item.event.title} · ${systemSubtitle(item.event, item.offsetMs)}")
            }
        }
    }

    companion object {
        /** 超过这么久没有收到数据就算“卡住”。 */
        const val STALL_MS = 30_000L

        private val QUOTA_CODES = setOf("insufficient_quota", "quota_exceeded", "billing_error", "usage_limit_reached")

        fun purposeName(purpose: String): String = DiagnosticTraceBuilder.purposeName(purpose)
    }
}
