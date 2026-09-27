package io.github.fartown.movo.ui.components.movo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoMotion

/**
 * 规范 9.6「音量信号」：所有音量动画共用一个 0–1 的平滑音量，上升 80ms、回落 200ms。
 * 减少动画时恒为 0（音量条停在静音高度、麦克风不脉动，规范 9.8）。
 *
 * 返回 [State]：平滑音量逐帧变化，调用方只在 `graphicsLayer` / 绘制 lambda 里读 `.value`，
 * 不要在组合期读（否则说话期间所在界面每帧重组）。
 */
@Composable
internal fun rememberSmoothedLevelState(target: Float): State<Float> {
    val reduced = LocalReducedMotion.current
    val level = remember { Animatable(0f) }
    val goal = if (reduced) 0f else target.coerceIn(0f, 1f)
    LaunchedEffect(goal) {
        val rising = goal > level.value
        level.animateTo(goal, tween(if (rising) LEVEL_RISE_MS else LEVEL_FALL_MS, easing = MovoMotion.EasingLinear))
    }
    return level.asState()
}

/**
 * 同上，但目标音量由 [target] 在协程里读取（`snapshotFlow`）：原始电平每次回调都变，
 * 调用方不必在组合期读它，所在界面（整个输入框）不会随电平重组。
 */
@Composable
internal fun rememberSmoothedLevelState(target: () -> Float): State<Float> {
    val reduced = LocalReducedMotion.current
    val level = remember { Animatable(0f) }
    val currentTarget by rememberUpdatedState(target)
    LaunchedEffect(reduced) {
        snapshotFlow { if (reduced) 0f else currentTarget().coerceIn(0f, 1f) }.collectLatest { goal ->
            val rising = goal > level.value
            level.animateTo(goal, tween(if (rising) LEVEL_RISE_MS else LEVEL_FALL_MS, easing = MovoMotion.EasingLinear))
        }
    }
    return level.asState()
}


private const val LEVEL_RISE_MS = 80
private const val LEVEL_FALL_MS = 200
