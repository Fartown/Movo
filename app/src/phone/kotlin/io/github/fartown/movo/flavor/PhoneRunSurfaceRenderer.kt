package io.github.fartown.movo.flavor

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import io.github.fartown.movo.agent.overlay.AgentOverlayBubble
import io.github.fartown.movo.agent.overlay.AgentOverlayGlow
import io.github.fartown.movo.agent.overlay.AgentOverlayOrb
import io.github.fartown.movo.agent.overlay.AgentOverlayRemoveZone
import io.github.fartown.movo.agent.overlay.AgentOverlayState
import io.github.fartown.movo.agent.overlay.OrbMode
import io.github.fartown.movo.agent.voice.session.VoiceSessionUiState
import io.github.fartown.movo.platform.RunSurfaceRenderer
import io.github.fartown.movo.ui.components.movo.AgentInteractionOverlayContent
import io.github.fartown.movo.ui.markdown.InAppBrowserUriHandler
import io.github.fartown.movo.ui.model.AgentInteractionUiState
import io.github.fartown.movo.ui.theme.ProvideReducedMotion
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** 手机：悬浮球、展开卡、边缘光晕、移除区与跨应用审批卡（miuix 主题）。 */
internal object PhoneRunSurfaceRenderer : RunSurfaceRenderer {
    @Composable
    override fun Host(context: Context, isNightMode: () -> Boolean, content: @Composable () -> Unit) {
        MiuixTheme(colors = if (isNightMode()) darkColorScheme() else lightColorScheme()) {
            // 部分 ROM 会给 TYPE_ACCESSIBILITY_OVERLAY 分配软件 Canvas；Miuix 的
            // RuntimeShader 只检查系统版本，因此系统浮层统一使用其普通圆角回退。
            CompositionLocalProvider(
                LocalSquircleEnabled provides false,
                LocalUriHandler provides InAppBrowserUriHandler(context),
            ) {
                ProvideReducedMotion(content)
            }
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
    ) {
        AgentOverlayOrb(
            mode = mode,
            onTap = onTap,
            onLongPress = onLongPress,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            shown = shown,
            engaged = engaged,
            hearing = hearing,
            longRun = longRun,
            animateEntrance = animateEntrance,
            hideForReveal = hideForReveal,
        )
    }

    @Composable
    override fun Glow(state: AgentOverlayState) {
        AgentOverlayGlow(state = state)
    }

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
    ) {
        AgentOverlayBubble(
            state = state,
            onCollapse = onCollapse,
            onPause = onPause,
            onResume = onResume,
            onStop = onStop,
            onSupplementModeChange = onSupplementModeChange,
            onSupplement = onSupplement,
            onSupplementKeyboardRequested = onSupplementKeyboardRequested,
            anchorEnd = anchorEnd,
            visible = visible,
            onInteraction = onInteraction,
            voice = voice,
            onStartVoice = onStartVoice,
            onEndVoice = onEndVoice,
            onOpenResult = onOpenResult,
            notice = notice,
            orbCenterOnScreen = orbCenterOnScreen,
        )
    }

    @Composable
    override fun RemoveZone(visible: Boolean) {
        AgentOverlayRemoveZone(visible = visible)
    }

    @Composable
    override fun Interaction(
        model: AgentInteractionUiState,
        onApprove: (remember: Boolean) -> Unit,
        onDecline: () -> Unit,
        onAnswer: (text: String, optionIndex: Int?) -> Unit,
        onCancel: () -> Unit,
    ) {
        AgentInteractionOverlayContent(
            model = model,
            onApprove = onApprove,
            onDecline = onDecline,
            onAnswer = onAnswer,
            onCancel = onCancel,
        )
    }
}
