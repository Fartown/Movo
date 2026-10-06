package io.github.fartown.movo.platform

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import io.github.fartown.movo.agent.overlay.AgentOverlayState
import io.github.fartown.movo.agent.overlay.OrbMode
import io.github.fartown.movo.agent.voice.session.VoiceSessionUiState
import io.github.fartown.movo.ui.model.AgentInteractionUiState

/**
 * Agent 运行中浮层窗口的内容。窗口的创建、类型选择、位置与拖动由 AgentRuntimeService 管理；
 * 这里只决定每个窗口里画什么（手机：悬浮球、展开卡、边缘光晕；电视：语音面板）。
 */
internal interface RunSurfaceRenderer {
    /** False when the flavor owns its own conversation/status window. */
    val usesRuntimeWindows: Boolean get() = true
    /** 每个浮层窗口内容的外层：主题、链接打开方式、减少动画等。 */
    @Composable
    fun Host(context: Context, isNightMode: () -> Boolean, content: @Composable () -> Unit)

    @Composable
    fun Orb(
        mode: OrbMode,
        onTap: () -> Unit,
        onLongPress: () -> Unit,
        onDragStart: () -> Unit,
        onDrag: (dx: Float, dy: Float) -> Unit,
        onDragEnd: () -> Unit,
        shown: Boolean,
        engaged: Boolean,
        hearing: Boolean,
        longRun: Boolean,
        animateEntrance: Boolean,
        hideForReveal: Boolean,
    )

    @Composable
    fun Glow(state: AgentOverlayState)

    @Composable
    fun Bubble(
        state: AgentOverlayState,
        onCollapse: () -> Unit,
        onPause: () -> Unit,
        onResume: () -> Unit,
        onStop: () -> Unit,
        onSupplementModeChange: (Boolean) -> Unit,
        onSupplement: (String) -> Unit,
        onSupplementKeyboardRequested: () -> Unit,
        anchorEnd: Boolean,
        visible: Boolean,
        onInteraction: () -> Unit,
        voice: VoiceSessionUiState,
        onStartVoice: () -> Unit,
        onEndVoice: () -> Unit,
        onOpenResult: () -> Unit,
        notice: String?,
        orbCenterOnScreen: () -> Offset?,
    )

    @Composable
    fun RemoveZone(visible: Boolean)

    /** 跨应用的审批 / 提问卡。 */
    @Composable
    fun Interaction(
        model: AgentInteractionUiState,
        onApprove: (remember: Boolean) -> Unit,
        onDecline: () -> Unit,
        onAnswer: (text: String, optionIndex: Int?) -> Unit,
        onCancel: () -> Unit,
    )
}
