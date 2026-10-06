package io.github.fartown.movo.tv

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import io.github.fartown.movo.agent.overlay.AgentOverlayState
import io.github.fartown.movo.agent.overlay.OrbMode
import io.github.fartown.movo.agent.voice.session.VoiceSessionUiState
import io.github.fartown.movo.platform.RunSurfaceRenderer
import io.github.fartown.movo.ui.model.AgentInteractionUiState
import io.github.fartown.movo.ui.theme.ProvideReducedMotion

/**
 * 电视的运行中浮层。P1 占位：悬浮球、展开卡、边缘光晕、移除区不画（这些窗口不可获焦，不影响遥控器）；
 * P2 换成语音面板（实施方案 §5.8）。
 */
internal object TvRunSurfaceRenderer : RunSurfaceRenderer {
    override val usesRuntimeWindows = false
    @Composable
    override fun Host(context: Context, isNightMode: () -> Boolean, content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = if (isNightMode()) darkColorScheme() else lightColorScheme()) {
            ProvideReducedMotion(content)
        }
    }

    @Composable
    override fun Orb(
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
    ) = Unit

    @Composable
    override fun Glow(state: AgentOverlayState) = Unit

    @Composable
    override fun Bubble(
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
    ) = Unit

    @Composable
    override fun RemoveZone(visible: Boolean) = Unit

    /** 电视不弹提问 / 审批卡（FlavorModule.interactionCards = false），运行时不会发起交互请求。 */
    @Composable
    override fun Interaction(
        model: AgentInteractionUiState,
        onApprove: (remember: Boolean) -> Unit,
        onDecline: () -> Unit,
        onAnswer: (text: String, optionIndex: Int?) -> Unit,
        onCancel: () -> Unit,
    ) = Unit
}
