package io.github.fartown.movo.ui.components.movo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoMotion

/**
 * 区块进场（规范 9.4「首页进场」「回答完成」「执行卡 · 新步骤」）：淡入并上移 [shift]，[durationMillis] + `enter`，
 * 按 [step] 依次延迟 `stagger`。
 * [play] 为 false 时直接显示（例如从历史记录加载、滚动回来）；减少动画时只淡入 `fast`，不位移。
 */
@Composable
internal fun MovoEntrance(
    play: Boolean = true,
    step: Int = 0,
    shift: Dp = 8.dp,
    durationMillis: Int = MovoMotion.STANDARD,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            progress.animateTo(
                1f,
                tween(
                    durationMillis = if (reduced) MovoMotion.FAST else durationMillis,
                    delayMillis = MovoMotion.staggerDelay(step),
                    easing = MovoMotion.EasingEnter,
                ),
            )
        }
    }
    val shiftPx = with(LocalDensity.current) { shift.toPx() }
    Box(
        modifier = modifier.graphicsLayer {
            val p = progress.value
            alpha = p
            if (!reduced) translationY = shiftPx * (1f - p)
        },
    ) {
        content()
    }
}
