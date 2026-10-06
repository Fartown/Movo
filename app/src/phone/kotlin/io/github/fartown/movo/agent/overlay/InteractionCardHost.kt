package io.github.fartown.movo.agent.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.fartown.movo.agent.tools.interaction.InteractionReply
import io.github.fartown.movo.ui.components.movo.AgentInteractionCard
import io.github.fartown.movo.ui.model.AgentInteractionUiState

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
