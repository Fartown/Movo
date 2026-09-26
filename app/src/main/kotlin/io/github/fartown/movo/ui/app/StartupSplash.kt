package io.github.fartown.movo.ui.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AnimationUtils
import android.window.SplashScreenView
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.github.fartown.movo.R
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * 首页进场（规范 9.4）要在用户真正看得到首页时才播：冷启动时系统启动页会一直盖到内容就绪、
 * 再按剩余图标动画延迟退场，期间播放的进场会被整段挡住。启动页开始退场时放行；
 * 没有系统启动页（从其他入口拉起）时不会收到退场回调，由调用方的等待超时兜底。
 */
internal object StartupReveal {
    var revealed by androidx.compose.runtime.mutableStateOf(true)
        private set

    fun hold() {
        revealed = false
    }

    fun release() {
        revealed = true
    }

    /** 等到启动页开始退场（最多 [timeoutMillis]）。 */
    suspend fun await(timeoutMillis: Long = 2_000L) {
        if (revealed) return
        kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
            androidx.compose.runtime.snapshotFlow { revealed }.first { it }
        }
    }
}

internal fun ComponentActivity.installStartupSplash(
    isContentReady: () -> Boolean,
) {
    StartupSplash(this, isContentReady).install()
}

private class StartupSplash(
    private val activity: ComponentActivity,
    private val isContentReady: () -> Boolean,
) : DefaultLifecycleObserver, ViewTreeObserver.OnPreDrawListener {
    private val content = activity.findViewById<View>(android.R.id.content)
    private var splashView: SplashScreenView? = null
    private var exitAnimator: ValueAnimator? = null
    private var hasStopped = false

    fun install() {
        activity.lifecycle.addObserver(this)
        // 外观配置异步读取完成后才允许首帧，避免系统启动画面先退到空白窗口。
        content.viewTreeObserver.addOnPreDrawListener(this)
        activity.splashScreen.setOnExitAnimationListener(::onExit)
    }

    override fun onPreDraw(): Boolean {
        if (!isContentReady()) return false
        content.viewTreeObserver.removeOnPreDrawListener(this)
        return true
    }

    private fun onExit(view: SplashScreenView) {
        splashView = view
        if (hasStopped || !ValueAnimator.areAnimatorsEnabled() ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            dismiss()
            return
        }

        val durationScale = ValueAnimator.getDurationScale()
        val remaining = remainingSplashAnimationMillis(
            animationStart = view.iconAnimationStart,
            animationDuration = view.iconAnimationDuration,
            now = Instant.now(),
            durationScale = durationScale,
        )
        if (!durationScale.isFinite() || durationScale <= 0f) {
            dismiss()
            return
        }
        val background = view.background.mutate()
        val backgroundAlpha = background.alpha
        val icon = view.iconView
        val iconAlpha = icon?.alpha ?: 1f
        val animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = activity.resources.getInteger(R.integer.splash_exit_duration).toLong()
            // ValueAnimator 会自行应用系统倍率；首页就绪较晚时仍保留完整的短过渡。
            startDelay = (remaining / durationScale).toLong().minus(duration).coerceAtLeast(0L)
            interpolator = AnimationUtils.loadInterpolator(activity, R.interpolator.splash_exit)
            addUpdateListener {
                val opacity = it.animatedValue as Float
                background.alpha = (backgroundAlpha * opacity).roundToInt()
                // 图标可能由独立 Surface 绘制，单独赋绝对透明度，避免父层透明度逐帧累乘。
                icon?.alpha = iconAlpha * opacity
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    // 启动页开始淡出：首页进场与它重叠播放。
                    StartupReveal.release()
                }

                override fun onAnimationEnd(animation: Animator) {
                    dismiss()
                }
            })
        }
        exitAnimator = animator
        animator.start()
    }

    private fun dismiss() {
        exitAnimator?.let {
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }
        exitAnimator = null
        val view = splashView
        splashView = null
        view?.remove()
        StartupReveal.release()
    }

    override fun onStop(owner: LifecycleOwner) {
        hasStopped = true
        dismiss()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        dismiss()
        if (content.viewTreeObserver.isAlive) {
            content.viewTreeObserver.removeOnPreDrawListener(this)
        }
        activity.splashScreen.clearOnExitAnimationListener()
        activity.lifecycle.removeObserver(this)
    }
}

internal fun remainingSplashAnimationMillis(
    animationStart: Instant?,
    animationDuration: Duration?,
    now: Instant,
    durationScale: Float,
): Long {
    if (animationStart == null || animationDuration == null ||
        !durationScale.isFinite() || durationScale <= 0f
    ) return 0L

    val duration = (animationDuration.toMillis() * durationScale).toLong().coerceAtLeast(0L)
    val elapsed = Duration.between(animationStart, now).toMillis()
    return (duration - elapsed).coerceIn(0L, duration)
}
