package io.github.fartown.movo.agent.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.fartown.movo.agent.tools.interaction.AgentInteractionRegistry
import io.github.fartown.movo.agent.tools.interaction.InteractionReply
import io.github.fartown.movo.ui.components.movo.AgentInteractionCard
import io.github.fartown.movo.ui.model.AgentInteractionUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 审批卡 / 提问卡显示在哪里：同一个 requestId 任何时候只在一处显示。
 *
 * - Runtime 把当前 run 等待作答的卡发布到 [pending]（不论任务从哪个入口发起：App、对话浮层、小布 / 小爱）。
 * - App 内能渲染卡片的宿主（主界面根、对话浮层，见 [InteractionCardHost]）在 resumed 时登记，最近登记的那个是 [activeHost]，
 *   由它显示；没有 App 内宿主时由 Runtime 的悬浮窗显示。
 * - 前后台切换时宿主登记 / 注销，卡片随之在 App 内与悬浮窗之间迁移，不会两处同时出现，也不会哪儿都看不到。
 *
 * 进程级状态（Runtime 与界面同进程），不依赖 Runtime 服务何时创建：服务在主界面 resume 之后才建也不会漏判。
 */
internal object InteractionCardCoordinator {
    enum class Placement { NONE, IN_APP, FLOATING }

    /** 纯规则：有待作答的卡时，App 内有可见宿主就在 App 内显示，否则用悬浮窗。 */
    fun placement(hasPending: Boolean, hasInAppHost: Boolean): Placement = when {
        !hasPending -> Placement.NONE
        hasInAppHost -> Placement.IN_APP
        else -> Placement.FLOATING
    }

    /**
     * 悬浮卡窗口是否可获焦：审批卡（没有自由输入）不可获焦——可获焦的全屏窗口会抢走目标窗口焦点、收起输入法，
     * 目标界面内容随之变化，批准后观察过期要再批一次；提问卡要自由输入时才可获焦；锁屏解锁提示不可获焦。
     */
    fun floatingFocusable(model: AgentInteractionUiState, locked: Boolean): Boolean =
        !locked && !model.isApproval && model.allowFreeText

    private val _pending = MutableStateFlow<AgentInteractionUiState?>(null)
    val pending: StateFlow<AgentInteractionUiState?> = _pending.asStateFlow()

    private val hosts = mutableListOf<Any>()
    private val _activeHost = MutableStateFlow<Any?>(null)
    val activeHost: StateFlow<Any?> = _activeHost.asStateFlow()

    /** 「去解锁」进行中：系统解锁界面在前时先撤下悬浮卡，不挡住输入密码。 */
    private val _unlockInProgress = MutableStateFlow(false)
    val unlockInProgress: StateFlow<Boolean> = _unlockInProgress.asStateFlow()

    val currentPlacement: Placement
        get() = placement(_pending.value != null, _activeHost.value != null)

    fun publish(model: AgentInteractionUiState) {
        _pending.value = model
    }

    /** 收起这张卡（作答、超时、取消后 run 发来「已处理」）；不是当前这张时不动。 */
    fun resolve(requestId: String) {
        _pending.update { current -> if (current?.requestId == requestId) null else current }
    }

    /** 收起某个 run 的卡（run 结束、被新任务替换、服务停止）；[runId] 为 null 时不论哪个 run 都收起。 */
    fun clearRun(runId: String?) {
        _pending.update { current -> if (current != null && (runId == null || current.runId == runId)) null else current }
        if (_pending.value == null) _unlockInProgress.value = false
    }

    fun registerHost(owner: Any) {
        synchronized(hosts) {
            hosts.remove(owner)
            hosts.add(owner)
            _activeHost.value = hosts.last()
        }
    }

    fun unregisterHost(owner: Any) {
        synchronized(hosts) {
            if (hosts.remove(owner)) _activeHost.value = hosts.lastOrNull()
        }
    }

    /** 悬浮卡窗口是否还挂着：Runtime 服务加上 / 撤下窗口时更新。 */
    private val _floatingAttached = MutableStateFlow(false)
    val floatingAttached: StateFlow<Boolean> = _floatingAttached.asStateFlow()

    fun setFloatingAttached(attached: Boolean) {
        _floatingAttached.value = attached
    }

    /** 最近一次作答时卡片是不是悬浮在别的应用上。 */
    @Volatile
    var lastReplyWasFloating: Boolean = false
        private set

    /**
     * 用户点了允许 / 继续之后，等卡片真正撤下再执行这一步（最多 [timeoutMs]）：悬浮卡窗口撤掉，
     * 且作答时卡是悬浮的话，无障碍的活动窗口已回到目标应用。作答会立刻放行运行线程，而悬浮窗是主线程稍后才撤：
     * 刚点过的卡片还算活动窗口，这时按「当前焦点」输入找不到输入框，按坐标点按会落在卡片遮罩上（真机 P8）。
     * 返回是否在时限内等到。
     */
    fun awaitSettled(
        activePackage: () -> String?,
        selfPackage: String,
        timeoutMs: Long = 1_500,
        now: () -> Long = System::currentTimeMillis,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): Boolean {
        val deadline = now() + timeoutMs
        val waitForWindow = lastReplyWasFloating
        while (true) {
            val overlayGone = !_floatingAttached.value
            val windowBack = !waitForWindow || runCatching(activePackage).getOrNull() != selfPackage
            if (overlayGone && windowBack) return true
            if (now() >= deadline) return false
            sleep(50)
        }
    }

    /** 作答：先收起这张卡（与它显示在哪一处无关），再投递给等待中的 run。返回是否送达。 */
    fun reply(model: AgentInteractionUiState, reply: InteractionReply): Boolean {
        lastReplyWasFloating = currentPlacement == Placement.FLOATING
        resolve(model.requestId)
        return runCatching { AgentInteractionRegistry.deliver(model.runId, model.requestId, reply) }.getOrDefault(false)
    }

    fun setUnlockInProgress(inProgress: Boolean) {
        _unlockInProgress.value = inProgress
    }
}

/**
 * App 内的审批卡 / 提问卡宿主（主界面根、对话浮层各挂一个）。本页面 resumed 且 [enabled] 时登记为宿主，
 * 是最近登记的宿主时显示 Runtime 当前等待作答的卡；否则不显示（由另一处宿主或悬浮窗显示）。
 * 锁屏上（对话浮层可能盖在锁屏上）传 `enabled = false`：不在锁屏上给出「允许」，由悬浮窗显示解锁提示。
 */
@Composable
internal fun InteractionCardHost(enabled: Boolean = true) {
    val owner = remember { Any() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, enabled) {
        fun sync() {
            if (enabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                InteractionCardCoordinator.registerHost(owner)
            } else {
                InteractionCardCoordinator.unregisterHost(owner)
            }
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycle.addObserver(observer)
        sync()
        onDispose {
            lifecycle.removeObserver(observer)
            InteractionCardCoordinator.unregisterHost(owner)
        }
    }
    val activeHost by InteractionCardCoordinator.activeHost.collectAsState()
    val pending by InteractionCardCoordinator.pending.collectAsState()
    val shown = pending?.takeIf { activeHost === owner }
    // 退场动画期间卡片还显示着上一张，此时 shown 已为 null：点击不再作答。
    AgentInteractionCard(
        state = shown,
        onApprove = { shown?.let { InteractionCardCoordinator.reply(it, InteractionReply.Approval(approved = true)) } },
        onDecline = { shown?.let { InteractionCardCoordinator.reply(it, InteractionReply.Approval(approved = false)) } },
        onAnswer = { text, optionIndex ->
            shown?.let { InteractionCardCoordinator.reply(it, InteractionReply.Answer(text, optionIndex)) }
        },
        onCancel = { shown?.let { InteractionCardCoordinator.reply(it, InteractionReply.Cancelled) } },
    )
}
