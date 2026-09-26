package io.github.mangi.eta.ui.components.movo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import io.github.mangi.eta.ui.theme.LocalReducedMotion
import io.github.mangi.eta.ui.theme.MovoMotion

/**
 * 规范 9.6「音量信号」：所有音量动画共用一个 0–1 的平滑音量，上升 80ms、回落 200ms。
 * 减少动画时恒为 0（音量条停在静音高度、麦克风不脉动，规范 9.8）。
 */
@Composable
internal fun rememberSmoothedLevel(target: Float): Float {
    val reduced = LocalReducedMotion.current
    val level = remember { Animatable(0f) }
    val goal = if (reduced) 0f else target.coerceIn(0f, 1f)
    LaunchedEffect(goal) {
        val rising = goal > level.value
        level.animateTo(goal, tween(if (rising) LEVEL_RISE_MS else LEVEL_FALL_MS, easing = MovoMotion.EasingLinear))
    }
    return level.value
}

private const val LEVEL_RISE_MS = 80
private const val LEVEL_FALL_MS = 200
