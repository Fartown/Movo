package io.github.fartown.movo.ui.components.movo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
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
 * 兼容旧签名（待迁移）：在组合期读取平滑音量，调用方会逐帧重组。
 * 新代码用 [rememberSmoothedLevelState]，把 `.value` 放进 `graphicsLayer` / `drawBehind` 里读。
 */
@Composable
internal fun rememberSmoothedLevel(target: Float): Float = rememberSmoothedLevelState(target).value

private const val LEVEL_RISE_MS = 80
private const val LEVEL_FALL_MS = 200
