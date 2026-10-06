package io.github.fartown.movo.agent.overlay

/**
 * 悬浮层生命周期的纯规则（Runtime 服务据此收尾、保留结果、停服务），抽出来便于单测。
 */
internal object OverlayLifecyclePolicy {
    /** 一轮结束时悬浮层怎么收尾。 */
    enum class Finish {
        /** 操作过其他 App（或结果在对话浮层里）：保持 ✓ / !，点开查看。 */
        SHOW_RESULT,

        /** 没有操作其他 App、用户也在 Movo 里看到了结果：常驻的悬浮球回到待命（收起展开卡、撤光晕、清提示，不带 ✓）。 */
        RETIRE_TO_STANDBY,

        /** 不需要悬浮球（常驻关）：撤掉并停服务。 */
        STOP,
    }

    /**
     * [keepStandbyOrb] 只在前两条都不成立时才求值（它可能顺带建出待命悬浮球）。
     * 流式阶段已按前台工具揭开了浮层、但工具没真正开始（被停止、流中断、改成纯文本回答）时 [executedForegroundTool] 为 false，
     * 这时也要回到待命，不能把光晕留着按「执行中」满亮度流动。
     */
    fun finish(
        executedForegroundTool: Boolean,
        resultConversation: Boolean,
        standbyOrbPresent: Boolean,
        /** 这一轮悬浮球显示过执行中，结束时用户不在 Movo 里：结果在等用户看，保持 ✓ / !（规范 8.1）。 */
        resultAwaitedOnOrb: Boolean = false,
        keepStandbyOrb: () -> Boolean,
    ): Finish = when {
        executedForegroundTool || resultConversation || resultAwaitedOnOrb -> Finish.SHOW_RESULT
        standbyOrbPresent || keepStandbyOrb() -> Finish.RETIRE_TO_STANDBY
        else -> Finish.STOP
    }

    /** 悬浮层上有结果待查看：✓ / !（或用户停止后保留的结果），点开打开结果对话。 */
    fun resultPending(
        orbPresent: Boolean,
        standby: Boolean,
        foregroundRunActive: Boolean,
        hasResultTarget: Boolean,
        phase: AgentOverlayPhase,
    ): Boolean = orbPresent && !standby && !foregroundRunActive && hasResultTarget &&
        (phase == AgentOverlayPhase.FINISHED || phase == AgentOverlayPhase.FAILED)

    /**
     * 新的一轮是否先保留悬浮层上待查看的结果：只有后台监听唤醒的一轮这样做（用户发起的一轮照常接管），
     * 直到它需要前台操作。
     */
    fun keepsPendingResult(monitorOrigin: Boolean, fromResultCard: Boolean, resultPending: Boolean): Boolean =
        monitorOrigin && !fromResultCard && resultPending

    /** 「常驻悬浮球」关时结果还能从悬浮层查看：✓ / ! 仍在保留时长内，或失败原因的展开卡开着。 */
    fun resultViewable(
        orbPresent: Boolean,
        standby: Boolean,
        phase: AgentOverlayPhase,
        nowUptimeMillis: Long,
        resultVisibleUntilUptimeMillis: Long,
        panelOpen: Boolean,
    ): Boolean = orbPresent && !standby &&
        (phase == AgentOverlayPhase.FINISHED || phase == AgentOverlayPhase.FAILED) &&
        (nowUptimeMillis < resultVisibleUntilUptimeMillis || panelOpen)

    /** 「常驻悬浮球」关、没有任务（含准备中）、没在打开结果、也没有可查看的结果：藏着的悬浮球与服务都该撤掉。 */
    fun overlayUnneeded(
        keepOrbAfterExit: Boolean,
        runActive: Boolean,
        preparingRun: Boolean,
        openingResult: Boolean,
        resultViewable: Boolean,
    ): Boolean = !keepOrbAfterExit && !runActive && !preparingRun && !openingResult && !resultViewable
}
