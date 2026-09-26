package io.github.fartown.movo.ui.theme

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * 规范第 9 章的动效令牌。页面代码只引用这里的名字，不裸写毫秒数与曲线（9.0 规则 2）。
 */
internal object MovoMotion {
    // 9.1 时长：进场 / 状态变化与退场（退场约为进场的 70%）。
    const val INSTANT = 100
    const val INSTANT_EXIT = 100
    const val FAST = 160
    const val FAST_EXIT = 120
    const val STANDARD = 240
    const val STANDARD_EXIT = 170
    const val SLOW = 360
    const val SLOW_EXIT = 250
    const val AMBIENT = 4000
    const val STAGGER = 40
    const val STAGGER_MAX_STEPS = 4

    // 固定节奏（9.1）。
    const val SPINNER_PERIOD = 800
    const val ORB_ARC_PERIOD = 1200
    const val ORB_GRADIENT_PERIOD = 12_000
    const val MINI_ORB_GRADIENT_PERIOD = 3000
    const val SHIMMER_SWEEP = 1200
    const val SHIMMER_PAUSE = 400
    const val STATUS_HALO_PERIOD = 1600
    const val TAP_INDICATOR_HOLD = 200
    const val WORK_CARD_COLLAPSE_DELAY = 600
    const val PANEL_AUTO_COLLAPSE = 4000
    const val SUPPLEMENT_ACK_HOLD = 1500
    const val COPIED_HOLD = 1400
    const val LONG_PRESS = 300
    const val MENU_CLOSE_DELAY = 160

    // 9.2 曲线。
    val EasingStandard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EasingEnter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EasingExit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val EasingLinear: Easing = LinearEasing

    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1500f)
    fun <T> gentle(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = 400f)

    fun <T> instant(): FiniteAnimationSpec<T> = tween(INSTANT, easing = EasingStandard)
    fun <T> fast(easing: Easing = EasingStandard): FiniteAnimationSpec<T> = tween(FAST, easing = easing)
    fun <T> fastExit(): FiniteAnimationSpec<T> = tween(FAST_EXIT, easing = EasingExit)
    fun <T> standard(easing: Easing = EasingStandard): FiniteAnimationSpec<T> = tween(STANDARD, easing = easing)
    fun <T> standardExit(): FiniteAnimationSpec<T> = tween(STANDARD_EXIT, easing = EasingExit)
    fun <T> slow(easing: Easing = EasingStandard): FiniteAnimationSpec<T> = tween(SLOW, easing = easing)
    fun <T> slowExit(): FiniteAnimationSpec<T> = tween(SLOW_EXIT, easing = EasingExit)

    /** stagger 序号最多 4 级，第 5 个起与第 4 个同时（9.1）。 */
    fun staggerDelay(index: Int): Int = index.coerceIn(0, STAGGER_MAX_STEPS - 1) * STAGGER
}

/**
 * 9.8 减少动画：系统动画缩放为 0 或省电模式时为 true。循环、位移与缩放动画据此切到替代方案；
 * 必须显式判断，不能依赖系统自动缩短（无限循环会停在某一帧看起来像卡死）。
 */
internal val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
internal fun ProvideReducedMotion(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var reduced by remember { mutableStateOf(isReducedMotion(context)) }
    DisposableEffect(context) {
        val refresh = { reduced = isReducedMotion(context) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }
        context.registerReceiver(
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        val scaleListener = ValueAnimator.DurationScaleChangeListener { refresh() }
        ValueAnimator.registerDurationScaleChangeListener(scaleListener)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            ValueAnimator.unregisterDurationScaleChangeListener(scaleListener)
        }
    }
    CompositionLocalProvider(LocalReducedMotion provides reduced, content = content)
}

internal fun isReducedMotion(context: Context): Boolean {
    val powerSave = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    return !ValueAnimator.areAnimatorsEnabled() || powerSave
}
