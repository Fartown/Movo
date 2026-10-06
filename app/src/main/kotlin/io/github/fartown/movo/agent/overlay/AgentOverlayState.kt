package io.github.fartown.movo.agent.overlay

import androidx.compose.runtime.Immutable
import io.github.fartown.movo.agent.runtime.AgentEvent

/** Agent 浮窗所处的阶段。 */
internal enum class AgentOverlayPhase { RUNNING, PAUSED, FINISHED, FAILED }

/**
 * 悬浮球的外观（规范 8.1 `Overlay/Orb`）：待命 = 只有玻璃圆 + 光球；执行中 / 暂停 / 聆听 / 监听中 / 失败 / 完成各有状态环或角标。
 * 完成与失败优先（结果待查看），其次语音对话中的聆听，再其次暂停。
 * 监听中（规范 8.12「任务与状态」）：没有在跑的一轮、但还有后台监听在等下一次事件——任务还没完，不是待命也不是完成。
 */
internal enum class OrbMode { STANDBY, RUNNING, PAUSED, LISTENING, MONITORING, FINISHED, FAILED }

internal fun orbMode(
    phase: AgentOverlayPhase,
    standby: Boolean,
    listening: Boolean,
    stopped: Boolean = false,
    /** 还有后台监听在运行（结束后等待撤销的不算）。 */
    monitoring: Boolean = false,
    /** 这一轮所在的对话还有监听在等：这件事没完（规范 8.12「任务与状态」）。 */
    taskMonitored: Boolean = false,
): OrbMode = when {
    standby -> if (monitoring) OrbMode.MONITORING else OrbMode.STANDBY
    // 用户主动结束不是出错：不挂 Rose 环与「!」，按待命外观显示；点开仍能查看保留的结果。
    phase == AgentOverlayPhase.FAILED && stopped -> if (monitoring) OrbMode.MONITORING else OrbMode.STANDBY
    // 监听还在，这一轮答完任务也没完：直接是监听中，任何时刻都不出 ✓（事件流的「完成」早于终态到达也一样）。
    phase == AgentOverlayPhase.FINISHED -> if (taskMonitored) OrbMode.MONITORING else OrbMode.FINISHED
    phase == AgentOverlayPhase.FAILED -> OrbMode.FAILED
    listening -> OrbMode.LISTENING
    phase == AgentOverlayPhase.PAUSED -> OrbMode.PAUSED
    else -> OrbMode.RUNNING
}

/**
 * Agent 浮窗的渲染状态。由 [AgentEvent] 流累积而来，[AgentOverlayBubble] 直接消费。
 */
@Immutable
internal data class AgentOverlayState(
    val phase: AgentOverlayPhase = AgentOverlayPhase.RUNNING,
    val round: Int = 0,
    val status: AgentOverlayStatus = AgentOverlayStatus.Preparing,
    val detailText: String = "",
    /** 本次运行的工具步骤（展开卡「第 N 步·动作」与最近 3 步，规范 8.1 `Overlay/Panel`）。 */
    val steps: List<OverlayStep> = emptyList(),
    /** 第一步开始的时刻；计时从这里算起。 */
    val startedAtMillis: Long? = null,
    /** 暂停开始的时刻（暂停中计时冻结）与累计暂停时长。 */
    val pausedAtMillis: Long? = null,
    val pausedTotalMillis: Long = 0L,
) {
    /** 计时：从第一步开始到现在（暂停中冻结在暂停那一刻），扣掉暂停过的时长。 */
    fun elapsedMillis(now: Long): Long? {
        val start = startedAtMillis ?: return null
        return ((pausedAtMillis ?: now) - start - pausedTotalMillis).coerceAtLeast(0L)
    }

    companion object {
        val Initial = AgentOverlayState(status = AgentOverlayStatus.Received)
    }
}

/**
 * 展开卡在没有在跑的一轮时显示的任务外层（规范 8.1 `Overlay/Panel` mode=Monitoring / Ended）。
 * [Monitoring]：还有后台监听在等；[Ended]：刚结束了任务和它的监听，5 秒内可以撤销。
 */
@Immutable
internal sealed interface OverlayTaskPanel {
    data class Monitoring(val monitors: List<OverlayMonitor>) : OverlayTaskPanel
    data class Ended(val endingId: String, val names: List<String>) : OverlayTaskPanel
}

@Immutable
internal data class OverlayMonitor(val name: String, val eventCount: Int, val deadlineAtMillis: Long)

/** 展开卡里的一步。 */
@Immutable
internal data class OverlayStep(
    val id: String,
    val title: String,
    val status: OverlayStepStatus,
)

internal enum class OverlayStepStatus { RUNNING, DONE, FAILED }

private fun AgentOverlayState.startStep(id: String, title: String, atMillis: Long): AgentOverlayState =
    if (steps.any { it.id == id }) {
        this
    } else {
        copy(
            steps = steps + OverlayStep(id, title, OverlayStepStatus.RUNNING),
            startedAtMillis = startedAtMillis ?: atMillis.takeIf { it > 0L } ?: System.currentTimeMillis(),
        )
    }

private fun AgentOverlayState.finishStep(id: String, success: Boolean): AgentOverlayState =
    copy(steps = steps.map { if (it.id == id) it.copy(status = if (success) OverlayStepStatus.DONE else OverlayStepStatus.FAILED) else it })

/** 暂停：记下暂停时刻，计时冻结。 */
internal fun AgentOverlayState.markPaused(now: Long = System.currentTimeMillis()): AgentOverlayState =
    copy(phase = AgentOverlayPhase.PAUSED, status = AgentOverlayStatus.Paused, pausedAtMillis = pausedAtMillis ?: now)

/** 继续：把这段暂停加进累计暂停时长。 */
internal fun AgentOverlayState.markResumed(now: Long = System.currentTimeMillis()): AgentOverlayState =
    copy(
        phase = AgentOverlayPhase.RUNNING,
        status = AgentOverlayStatus.Continuing,
        pausedAtMillis = null,
        pausedTotalMillis = pausedTotalMillis + (pausedAtMillis?.let { now - it } ?: 0L),
    )

/**
 * 将一个 [AgentEvent] 折叠进当前渲染状态。
 *
 * 文案逻辑只保留面向用户的一句话状态，
 * 工具名经 [toToolLabel] 中文化。详细 trace 流作为后续任务，此处不展开。
 */
internal fun AgentOverlayState.applyEvent(event: AgentEvent): AgentOverlayState {
    val next = reduce(event)
    // 暂停只在下一个检查点生效：暂停前已发出的请求和工具仍会陆续回报事件，这些事件不能把界面改回「运行中」。
    // 继续由服务直接写回 RUNNING；结束 / 失败照常生效。
    return if (phase == AgentOverlayPhase.PAUSED && next.phase == AgentOverlayPhase.RUNNING && event != AgentEvent.RunResumed) {
        next.copy(phase = AgentOverlayPhase.PAUSED, status = status)
    } else {
        next
    }
}

private fun AgentOverlayState.reduce(event: AgentEvent): AgentOverlayState = when (event) {
    // 后台监听事件只出现在对话里，不改变悬浮层状态。
    is AgentEvent.MonitorEventReceived -> this
    is AgentEvent.ContextCompaction -> copy(status = AgentOverlayStatus.RequestingModel, detailText = event.displayMessage)
    is AgentEvent.RunStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        status = AgentOverlayStatus.PreparingTools(event.toolCount),
        detailText = "",
        steps = emptyList(),
        startedAtMillis = null,
        pausedAtMillis = null,
        pausedTotalMillis = 0L,
    )

    is AgentEvent.RoundStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.ReasoningRound(event.round),
    )

    is AgentEvent.ModelRetryScheduled -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.RequestingModel,
        detailText = event.displayMessage,
    )

    is AgentEvent.ProviderRequestStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.RequestingModel,
        detailText = "",
    )

    is AgentEvent.ProviderResponseStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.ModelResponded,
    )

    is AgentEvent.AssistantBlockStart -> when (event.kind) {
        AgentEvent.AssistantBlockKind.TEXT,
        AgentEvent.AssistantBlockKind.THINKING -> this

        AgentEvent.AssistantBlockKind.TOOL_CALL -> copy(
            phase = AgentOverlayPhase.RUNNING,
            round = event.round,
            status = AgentOverlayStatus.GeneratingToolArguments,
        )
    }

    is AgentEvent.AssistantBlockDelta -> when (event.kind) {
        AgentEvent.AssistantBlockKind.TEXT -> appendStreamingText(event)
        AgentEvent.AssistantBlockKind.THINKING -> copy(
            phase = AgentOverlayPhase.RUNNING,
            round = event.round,
            status = AgentOverlayStatus.Reasoning,
        )

        AgentEvent.AssistantBlockKind.TOOL_CALL -> copy(
            phase = AgentOverlayPhase.RUNNING,
            round = event.round,
            status = AgentOverlayStatus.GeneratingToolArguments,
        )
    }

    is AgentEvent.AssistantBlockEnd -> this

    is AgentEvent.AssistantReceived -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = if (event.toolNames.isEmpty()) AgentOverlayStatus.PreparingAnswer
        else AgentOverlayStatus.PlanningTools(event.toolNames),
    )

    is AgentEvent.UsageReceived -> this

    is AgentEvent.UserSupplementReceived -> copy(
        phase = AgentOverlayPhase.RUNNING,
        status = AgentOverlayStatus.SupplementReceived,
        detailText = "",
    )

    is AgentEvent.ToolStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.RunningTool(event.name),
    ).startStep("${event.round}:${event.toolCallId}", event.argsPreview.ifBlank { event.name }, event.atMillis)

    is AgentEvent.ToolFinished -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.ToolCompleted(event.name),
    ).finishStep("${event.round}:${event.toolCallId}", event.success ?: !event.resultSummary.contains("ok=false", ignoreCase = true))

    is AgentEvent.HostedToolStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.HostedToolRunning(event.name),
    ).startStep("${event.round}:${event.toolCallId}", event.name, event.atMillis)

    is AgentEvent.HostedToolFinished -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.HostedToolFinished(event.name, event.success),
    ).finishStep("${event.round}:${event.toolCallId}", event.success)

    is AgentEvent.ToolImagesAttached -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.ImagesRead(event.imageCount),
    )

    is AgentEvent.RunFinished -> copy(
        phase = AgentOverlayPhase.FINISHED,
        round = event.round,
        status = AgentOverlayStatus.ResultReady,
        pausedAtMillis = pausedAtMillis ?: System.currentTimeMillis(),
    )

    is AgentEvent.RunFailed -> copy(
        phase = AgentOverlayPhase.FAILED,
        status = AgentOverlayStatus.RunFailed,
        detailText = event.reason,
        pausedAtMillis = pausedAtMillis ?: System.currentTimeMillis(),
    )

    AgentEvent.RunPaused -> markPaused()

    AgentEvent.RunResumed -> if (phase == AgentOverlayPhase.PAUSED) markResumed() else this

    // 交互卡由应用内会话面渲染，悬浮球状态保持不变（卡片自身承载提问/审批）。
    is AgentEvent.InteractionRequested -> this
    is AgentEvent.InteractionResolved -> this
}

private const val MaxStreamingPreviewChars = 320

private fun AgentOverlayState.appendStreamingText(event: AgentEvent.AssistantBlockDelta): AgentOverlayState {
    val nextPreview = (detailText + event.delta)
        .trimStart()
        .take(MaxStreamingPreviewChars)
    return copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.GeneratingAnswer,
        detailText = nextPreview,
    )
}
