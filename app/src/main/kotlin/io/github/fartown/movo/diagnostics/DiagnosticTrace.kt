package io.github.fartown.movo.diagnostics

/**
 * 把扁平的诊断事件整理成运行日志的信息模型（docs/research/log-page/运行日志功能定义.md 第 4 节）：
 * 任务 → 结论、耗时构成、时间线；任务之外的设备事件单独成组。
 *
 * 纯函数、无 Android 依赖：页面每次刷新都从缓冲快照重建。
 * 任务内的间隔一律用单调时钟 `elapsedMillis` 计算；系统事件按墙钟 `timeMillis` 归入任务。
 */
internal enum class TraceStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }

internal data class TracePhase(val label: String, val offsetMs: Long)

internal data class RequestTrace(
    val id: String,
    val run: String,
    val purpose: String,
    val provider: String,
    val round: Int?,
    val attempt: Int?,
    val status: TraceStatus,
    val startedAt: Long,
    val startElapsed: Long,
    val durationMs: Long?,
    /** 从请求开始到收到第一段回答数据。 */
    val firstDataMs: Long?,
    val httpStatus: Int?,
    val errorCode: String?,
    val providerErrorCode: String?,
    val retryDelayMs: Long?,
    val serverRequestId: String?,
    val receivedBytes: Long?,
    val phases: List<TracePhase>,
) {
    val isRetry: Boolean get() = (attempt ?: 1) > 1

    /** 自动重试时底层轮次会加一；界面按用户理解的轮次显示，重试算在同一轮里。 */
    val displayRound: Int? get() = round?.let { it - ((attempt ?: 1) - 1) }
}

internal data class ToolTrace(
    val name: String,
    val round: Int?,
    val startedAt: Long,
    val startElapsed: Long,
    val durationMs: Long?,
    val success: Boolean?,
)

internal enum class EventTone { NORMAL, WARNING, ERROR }

internal enum class SystemEventKind { PROCESS, APP, NETWORK, SCREEN, POWER, SERVICE, MODEL, OTHER }

internal data class SystemEvent(
    val key: String,
    val timeMillis: Long,
    val kind: SystemEventKind,
    val title: String,
    val detail: String,
    val tone: EventTone,
)

internal sealed interface TimelineItem {
    val key: String
    val offsetMs: Long

    data class Request(val request: RequestTrace, override val offsetMs: Long) : TimelineItem {
        override val key: String get() = "request-${request.id}"
    }

    data class Tool(val tool: ToolTrace, val index: Int, override val offsetMs: Long) : TimelineItem {
        override val key: String get() = "tool-$index"
    }

    data class Compaction(
        val index: Int,
        override val offsetMs: Long,
        val tokensBefore: Long?,
        val tokensAfter: Long?,
    ) : TimelineItem {
        override val key: String get() = "compaction-$index"
    }

    data class System(val event: SystemEvent, override val offsetMs: Long) : TimelineItem {
        override val key: String get() = "system-${event.key}"
    }
}

/** 总用时花在了哪里；各项之和等于总用时（[otherMs] 是本地准备等剩余部分）。 */
internal data class TimeBreakdown(
    val waitingMs: Long,
    val receivingMs: Long,
    val retryWaitMs: Long,
    val toolMs: Long,
    val compactionMs: Long,
    val otherMs: Long,
)

internal data class RunFailure(
    val code: String?,
    val httpStatus: Int?,
    val providerErrorCode: String?,
    val round: Int?,
    /** 末尾连续失败的请求次数（含自动重试）。 */
    val failedAttempts: Int,
    val causes: String?,
)

/** 进行中任务当前卡在哪一步。 */
internal sealed interface CurrentStep {
    val sinceElapsed: Long

    data class WaitingModel(val request: RequestTrace, override val sinceElapsed: Long) : CurrentStep
    data class Receiving(val request: RequestTrace, override val sinceElapsed: Long) : CurrentStep
    data class Tool(val tool: ToolTrace, override val sinceElapsed: Long) : CurrentStep
    data class RetryWait(val retryNumber: Int, override val sinceElapsed: Long) : CurrentStep
    data class Preparing(override val sinceElapsed: Long) : CurrentStep
}

internal data class RunTrace(
    val id: String,
    val wireRunId: String?,
    val conversationId: String?,
    val startedAt: Long,
    val startElapsed: Long,
    val durationMs: Long?,
    val status: TraceStatus,
    val requests: List<RequestTrace>,
    val tools: List<ToolTrace>,
    val timeline: List<TimelineItem>,
    val failure: RunFailure?,
    val entries: List<DiagnosticEntry>,
) {
    val modelRequests: Int get() = requests.size
    val lastRound: Int? get() = requests.mapNotNull { it.displayRound }.maxOrNull()

    fun breakdown(): TimeBreakdown? {
        val total = durationMs ?: return null
        var waiting = 0L
        var receiving = 0L
        var compaction = 0L
        var retryWait = 0L
        requests.forEachIndexed { index, request ->
            val duration = request.durationMs ?: return@forEachIndexed
            if (request.purpose == "COMPACTION") {
                compaction += duration
            } else {
                val firstData = request.firstDataMs?.coerceAtMost(duration)
                if (firstData == null) waiting += duration else {
                    waiting += firstData
                    receiving += duration - firstData
                }
            }
            val delay = request.retryDelayMs ?: return@forEachIndexed
            val end = request.startElapsed + duration
            val next = requests.getOrNull(index + 1)
            retryWait += next?.let { (it.startElapsed - end).coerceIn(0L, delay * 2) }
                ?: delay.coerceAtMost((startElapsed + total - end).coerceAtLeast(0L))
        }
        val tool = tools.sumOf { it.durationMs ?: 0L }
        val other = (total - waiting - receiving - compaction - retryWait - tool).coerceAtLeast(0L)
        return TimeBreakdown(waiting, receiving, retryWait, tool, compaction, other)
    }

    fun currentStep(): CurrentStep {
        val openTool = tools.lastOrNull { it.durationMs == null }
        val openRequest = requests.lastOrNull { it.status == TraceStatus.RUNNING }
        val last = requests.lastOrNull()
        return when {
            openTool != null && (openRequest == null || openTool.startElapsed >= openRequest.startElapsed) ->
                CurrentStep.Tool(openTool, openTool.startElapsed)
            openRequest != null && openRequest.firstDataMs == null ->
                CurrentStep.WaitingModel(openRequest, openRequest.startElapsed)
            openRequest != null ->
                CurrentStep.Receiving(openRequest, openRequest.startElapsed + (openRequest.firstDataMs ?: 0L))
            last != null && last.retryDelayMs != null && last.durationMs != null ->
                CurrentStep.RetryWait(last.attempt ?: 1, last.startElapsed + last.durationMs)
            else -> CurrentStep.Preparing(
                maxOf(startElapsed, last?.let { it.startElapsed + (it.durationMs ?: 0L) } ?: startElapsed,
                    tools.lastOrNull()?.let { it.startElapsed + (it.durationMs ?: 0L) } ?: startElapsed),
            )
        }
    }
}

internal data class DiagnosticTrace(
    /** 最新的任务在前。 */
    val runs: List<RunTrace>,
    /** 任务之外的设备事件与模型请求，最新在前。 */
    val system: List<SystemEvent>,
)

internal object DiagnosticTraceBuilder {
    fun build(entries: List<DiagnosticEntry>): DiagnosticTrace {
        val ordered = entries.sortedBy { it.sequence }
        val requests = ordered.filter { it.context.request.isNotBlank() }
            .groupBy { it.context.request }
            .map { (id, events) -> buildRequest(id, events) }
        val deviceEvents = deviceEvents(ordered)
        val runs = ordered.filter { it.context.run.isNotBlank() }
            .groupBy { it.context.run }
            .map { (id, events) ->
                buildRun(id, events, requests.filter { it.run == id }.sortedBy { it.startElapsed }, deviceEvents)
            }
            .sortedByDescending { it.startedAt }
        val looseRequests = requests.filter { it.run.isBlank() }.map(::looseRequestEvent)
        val system = (deviceEvents + looseRequests).sortedByDescending { it.timeMillis }
        return DiagnosticTrace(runs, system)
    }

    private fun buildRun(
        id: String,
        all: List<DiagnosticEntry>,
        requests: List<RequestTrace>,
        deviceEvents: List<SystemEvent>,
    ): RunTrace {
        val events = all.filter { it.context.request.isBlank() }.ifEmpty { all }
        val start = events.first()
        val end = events.firstOrNull { it.event == "run.ended" || it.event == "run.interrupted" }
        val bound = events.lastOrNull { it.event == "run.bound" }
        val lastRequest = requests.lastOrNull { it.status != TraceStatus.RUNNING }
        val status = when {
            events.any { it.event == "run.interrupted" } -> TraceStatus.INTERRUPTED
            events.any { it.event == "run.completed" } -> TraceStatus.SUCCEEDED
            end == null -> TraceStatus.RUNNING
            events.any { it.event == "run.cancelled" } -> TraceStatus.CANCELLED
            events.any { it.event == "run.failed" } || lastRequest?.status == TraceStatus.FAILED -> TraceStatus.FAILED
            else -> TraceStatus.SUCCEEDED
        }
        val durationMs = end?.let { it.field("duration_ms")?.toLongOrNull() ?: (it.elapsedMillis - start.elapsedMillis) }
        val tools = buildTools(events)
        val windowEnd = durationMs?.let { start.timeMillis + it } ?: Long.MAX_VALUE
        val timeline = buildList {
            requests.forEach { add(TimelineItem.Request(it, it.startElapsed - start.elapsedMillis)) }
            tools.forEachIndexed { index, tool -> add(TimelineItem.Tool(tool, index, tool.startElapsed - start.elapsedMillis)) }
            events.filter { it.event == "context.compaction" && it.field("tokens_after").isKnownNumber() }
                .forEachIndexed { index, entry ->
                    add(TimelineItem.Compaction(index, entry.elapsedMillis - start.elapsedMillis,
                        entry.field("tokens_before")?.toLongOrNull(), entry.field("tokens_after")?.toLongOrNull()))
                }
            deviceEvents.filter { it.timeMillis in start.timeMillis..windowEnd }
                .forEach { add(TimelineItem.System(it, it.timeMillis - start.timeMillis)) }
        }.sortedBy { it.offsetMs }
        return RunTrace(
            id = id,
            wireRunId = bound?.field("wire_run")?.takeIf { it.isKnown() },
            conversationId = bound?.field("conversation")?.takeIf { it.isKnown() },
            startedAt = start.timeMillis,
            startElapsed = start.elapsedMillis,
            durationMs = durationMs,
            status = status,
            requests = requests,
            tools = tools,
            timeline = timeline,
            failure = if (status == TraceStatus.FAILED) failure(events, requests) else null,
            entries = all,
        )
    }

    private fun failure(events: List<DiagnosticEntry>, requests: List<RequestTrace>): RunFailure {
        val failed = requests.lastOrNull { it.status == TraceStatus.FAILED }
        val attempts = requests.takeLastWhile { it.status == TraceStatus.FAILED }.size
        return RunFailure(
            code = failed?.errorCode,
            httpStatus = failed?.httpStatus,
            providerErrorCode = failed?.providerErrorCode,
            round = failed?.displayRound,
            failedAttempts = attempts,
            causes = events.lastOrNull { it.event == "run.failed" }?.field("causes"),
        )
    }

    private fun buildTools(ordered: List<DiagnosticEntry>): List<ToolTrace> {
        val open = mutableListOf<DiagnosticEntry>()
        val tools = mutableListOf<ToolTrace>()
        for (entry in ordered) {
            when (entry.event) {
                "tool.started", "hosted_tool.started" -> open += entry
                "tool.finished", "hosted_tool.finished" -> {
                    val name = entry.field("tool")
                    val started = open.firstOrNull { it.field("tool") == name && it.field("round") == entry.field("round") }
                    if (started != null) open.remove(started)
                    val from = started ?: entry
                    tools += ToolTrace(
                        name = name ?: "unknown",
                        round = entry.field("round")?.toIntOrNull(),
                        startedAt = from.timeMillis,
                        startElapsed = from.elapsedMillis,
                        durationMs = started?.let { entry.elapsedMillis - it.elapsedMillis } ?: 0L,
                        success = entry.field("success")?.toBooleanStrictOrNull(),
                    )
                }
            }
        }
        open.forEach {
            tools += ToolTrace(it.field("tool") ?: "unknown", it.field("round")?.toIntOrNull(), it.timeMillis, it.elapsedMillis, null, null)
        }
        return tools.sortedBy { it.startElapsed }
    }

    private fun buildRequest(id: String, events: List<DiagnosticEntry>): RequestTrace {
        val start = events.firstOrNull { it.event == "attempt.started" } ?: events.first()
        val last = events.last()
        val terminal = events.lastOrNull { it.event in TERMINAL_EVENTS }
        val status = when (terminal?.event) {
            "attempt.completed" -> TraceStatus.SUCCEEDED
            "attempt.failed" -> TraceStatus.FAILED
            "attempt.cancelled" -> TraceStatus.CANCELLED
            else -> TraceStatus.RUNNING
        }
        val failure = events.lastOrNull { it.event == "attempt.failed" }
        fun offset(event: String) = events.firstOrNull { it.event == event }?.let { it.elapsedMillis - start.elapsedMillis }
        val firstData = offset("sse.first_event") ?: offset("http.first_byte")
        val phases = buildList {
            offset("http.dns_end")?.let { add(TracePhase("域名解析", it)) }
            if (offset("http.connecting") != null) offset("http.sending_headers")?.let { add(TracePhase("建立连接", it)) }
            offset("http.request_sent")?.let { add(TracePhase("请求已发送", it)) }
            offset("http.response_headers")?.let { add(TracePhase("响应头", it)) }
            firstData?.let { add(TracePhase("首字", it)) }
            terminal?.let { add(TracePhase(TERMINAL_LABELS.getValue(it.event), it.elapsedMillis - start.elapsedMillis)) }
        }
        return RequestTrace(
            id = id,
            run = start.context.run,
            purpose = start.field("purpose") ?: last.field("purpose") ?: "CHAT",
            provider = start.field("provider") ?: "unknown",
            round = start.field("round")?.toIntOrNull(),
            attempt = start.field("attempt")?.toIntOrNull(),
            status = status,
            startedAt = start.timeMillis,
            startElapsed = start.elapsedMillis,
            durationMs = terminal?.let { it.elapsedMillis - start.elapsedMillis },
            firstDataMs = firstData,
            httpStatus = events.asReversed().firstNotNullOfOrNull { it.field("http_status")?.toIntOrNull() },
            errorCode = failure?.field("code")?.takeIf { it.isKnown() },
            providerErrorCode = failure?.field("provider_error_code")?.takeIf { it.isKnown() },
            retryDelayMs = events.lastOrNull { it.event == "retry.scheduled" }?.field("delay_ms")?.toLongOrNull(),
            serverRequestId = events.asReversed().firstNotNullOfOrNull { it.field("server_request_id")?.takeIf { id -> id.isKnown() } },
            receivedBytes = last.field("received_bytes")?.toLongOrNull()?.takeIf { it > 0 },
            phases = phases,
        )
    }

    /**
     * 设备事件：只保留有意义的变化。网络回调会反复触发，只有网络类型真的变了才算一条；
     * 语音等界面事件不在这里展示（导出里仍有原始记录）。
     */
    fun deviceEvents(ordered: List<DiagnosticEntry>): List<SystemEvent> {
        var network: String? = null
        var powerSave: String? = null
        var idle: String? = null
        val events = mutableListOf<SystemEvent>()
        for (entry in ordered) {
            if (entry.context.run.isNotBlank() || entry.context.request.isNotBlank()) continue
            fun add(kind: SystemEventKind, title: String, detail: String = "", tone: EventTone = EventTone.NORMAL) {
                events += SystemEvent("${entry.timeMillis}-${entry.sequence}", entry.timeMillis, kind, title, detail, tone)
            }
            when (entry.event) {
                "process.started" -> {
                    network = entry.field("network")
                    powerSave = entry.field("power_save")
                    idle = entry.field("device_idle")
                    add(SystemEventKind.PROCESS, "进程启动",
                        entry.field("version")?.let { version -> "版本 $version" }.orEmpty())
                }
                "app.foreground" -> add(SystemEventKind.APP, "App 回到前台")
                "app.background" -> add(SystemEventKind.APP, "App 进入后台",
                    if (entry.field("power_save") == "true") "省电模式已开启" else "")
                "network.available", "network.lost", "network.changed" -> {
                    val current = entry.field("network") ?: continue
                    if (current == network) continue
                    val previous = network
                    network = current
                    when {
                        current == "none" -> add(SystemEventKind.NETWORK, "网络断开",
                            previous?.let { "${networkName(it)} → 无网络" }.orEmpty(), EventTone.WARNING)
                        previous == null || previous == "none" -> add(SystemEventKind.NETWORK, "网络恢复", networkName(current))
                        else -> add(SystemEventKind.NETWORK, "网络切换", "${networkName(previous)} → ${networkName(current)}")
                    }
                }
                "screen.off" -> add(SystemEventKind.SCREEN, "屏幕关闭")
                "screen.on" -> add(SystemEventKind.SCREEN, "屏幕点亮")
                "device_idle.changed" -> {
                    val value = entry.field("device_idle")
                    if (value == idle) continue
                    idle = value
                    if (value == "true") {
                        add(SystemEventKind.POWER, "设备进入休眠", "系统会限制后台网络和任务", EventTone.WARNING)
                    } else {
                        add(SystemEventKind.POWER, "设备退出休眠")
                    }
                }
                "power_save.changed" -> {
                    val value = entry.field("power_save")
                    if (value == powerSave) continue
                    powerSave = value
                    add(SystemEventKind.POWER, if (value == "true") "省电模式已开启" else "省电模式已关闭")
                }
                "execution_service.foreground" -> add(SystemEventKind.SERVICE, "后台服务启动")
                "execution_service.rejected" -> add(SystemEventKind.SERVICE, "前台服务被系统拒绝",
                    "后台启动受限，后台任务可能被中断", EventTone.ERROR)
                "execution_service.destroyed" -> add(SystemEventKind.SERVICE, "后台服务结束")
                "runtime.destroyed" -> add(SystemEventKind.SERVICE, "运行时服务结束", tone = EventTone.WARNING)
                "observer.failed" -> add(SystemEventKind.OTHER, "设备状态监听失败",
                    entry.field("causes").orEmpty(), EventTone.WARNING)
            }
        }
        return events
    }

    private fun looseRequestEvent(request: RequestTrace): SystemEvent = SystemEvent(
        key = "request-${request.id}",
        timeMillis = request.startedAt,
        kind = SystemEventKind.MODEL,
        title = "模型请求 · ${purposeName(request.purpose)}",
        detail = when (request.status) {
            TraceStatus.FAILED -> listOfNotNull(request.httpStatus?.let { "HTTP $it" }, request.errorCode).joinToString(" · ").ifBlank { "失败" }
            TraceStatus.RUNNING -> "进行中"
            TraceStatus.CANCELLED -> "已取消"
            else -> "完成"
        },
        tone = if (request.status == TraceStatus.FAILED) EventTone.ERROR else EventTone.NORMAL,
    )

    fun purposeName(purpose: String): String = when (purpose) {
        "CHAT" -> "对话"
        "COMPACTION" -> "上下文压缩"
        "REPLY_REWRITE" -> "改写回复"
        else -> purpose.lowercase()
    }

    fun networkName(value: String): String = when (value) {
        "wifi" -> "Wi‑Fi"
        "cellular" -> "移动数据"
        "vpn" -> "VPN"
        "ethernet" -> "以太网"
        "none" -> "无网络"
        else -> "其他网络"
    }

    private fun String?.isKnownNumber(): Boolean = this?.toLongOrNull() != null
    private fun String.isKnown(): Boolean = isNotBlank() && this != "unknown" && this != "null"

    private val TERMINAL_EVENTS = setOf("attempt.completed", "attempt.failed", "attempt.cancelled")
    private val TERMINAL_LABELS = mapOf("attempt.completed" to "完成", "attempt.failed" to "失败", "attempt.cancelled" to "取消")
}

internal fun DiagnosticEntry.field(key: String): String? =
    details.lineSequence().firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
