package io.github.fartown.movo.agent.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import kotlin.math.min

object GestureIndicator {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeIndicator: ActiveIndicator? = null

    fun showTap(context: Context, x: Int, y: Int) {
        showPress(context, x, y, PressKind.TAP, holdDurationMs = TAP_HOLD_DURATION_MS)
    }

    fun showLongPress(context: Context, x: Int, y: Int, durationMs: Int) {
        val holdDurationMs = (durationMs.toLong() - POP_IN_DURATION_MS - FADE_OUT_DURATION_MS)
            .coerceIn(MIN_LONG_PRESS_HOLD_MS, MAX_LONG_PRESS_HOLD_MS)
        showPress(context, x, y, PressKind.LONG_PRESS, holdDurationMs)
    }

    private fun showPress(
        context: Context,
        x: Int,
        y: Int,
        kind: PressKind,
        holdDurationMs: Long,
    ) {
        mainHandler.post {
            val service = AgentAccessibilityService.current()
            val overlayContext = service ?: context
            val overlayType = if (service != null) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }

            val wm = overlayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            val indicatorView = PressIndicatorView(overlayContext, kind, holdDurationMs)

            val density = overlayContext.resources.displayMetrics.density
            val sizePx = (INDICATOR_CONTAINER_SIZE_DP * density).toInt()

            val lp = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x - sizePx / 2
                this.y = y - sizePx / 2
            }

            attachIndicator(wm, indicatorView, lp)
        }
    }

    fun showSwipe(context: Context, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        mainHandler.post {
            val service = AgentAccessibilityService.current()
            val overlayContext = service ?: context
            val overlayType = if (service != null) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }

            val wm = overlayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            val indicatorView = SwipeIndicatorView(overlayContext, x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs)

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = 0
                this.y = 0
            }

            attachIndicator(wm, indicatorView, lp)
        }
    }

    /**
     * 输入文字指示（规范 9.5）：目标控件外框 1.5 宽 Indigo 描边（圆角 8），淡入 `fast` + `enter`，
     * 停留后淡出 120ms + `exit`。[bounds] 为控件的屏幕坐标。
     */
    fun showInput(context: Context, bounds: android.graphics.Rect) {
        if (bounds.isEmpty) return
        mainHandler.post {
            val service = AgentAccessibilityService.current()
            val overlayContext = service ?: context
            val overlayType = if (service != null) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }
            val wm = overlayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            val pad = (INPUT_OUTLINE_PAD_DP * overlayContext.resources.displayMetrics.density).toInt()
            val lp = WindowManager.LayoutParams(
                bounds.width() + pad * 2,
                bounds.height() + pad * 2,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = bounds.left - pad
                y = bounds.top - pad
            }
            attachIndicator(wm, InputIndicatorView(overlayContext, pad.toFloat()), lp)
        }
    }

    private fun attachIndicator(
        windowManager: WindowManager,
        view: AnimatedIndicatorView,
        layoutParams: WindowManager.LayoutParams,
    ) {
        dismissActiveIndicator()
        runCatching { windowManager.addView(view, layoutParams) }
            .onSuccess {
                val indicator = ActiveIndicator(windowManager, view)
                activeIndicator = indicator
                view.startIndicatorAnimation {
                    mainHandler.post { finishIndicator(indicator) }
                }
            }
    }

    private fun dismissActiveIndicator() {
        activeIndicator?.let(::finishIndicator)
    }

    private fun finishIndicator(indicator: ActiveIndicator) {
        if (activeIndicator === indicator) {
            activeIndicator = null
        }
        indicator.view.cancelIndicatorAnimation()
        runCatching { indicator.windowManager.removeView(indicator.view) }
    }

    private class ActiveIndicator(
        val windowManager: WindowManager,
        val view: AnimatedIndicatorView,
    )

    private const val INDICATOR_CONTAINER_SIZE_DP = 72f
    private const val INPUT_OUTLINE_PAD_DP = 2f
    // 规范 8.1 / 9.5「点击指示」：进场 fast（160ms）+ enter，保持 200ms，退场 120ms + exit。
    private const val POP_IN_DURATION_MS = 160L
    private const val TAP_HOLD_DURATION_MS = 200L
    private const val FADE_OUT_DURATION_MS = 120L
    private const val MIN_LONG_PRESS_HOLD_MS = 240L
    private const val MAX_LONG_PRESS_HOLD_MS = 620L
}

private enum class PressKind { TAP, LONG_PRESS }

private abstract class AnimatedIndicatorView(context: Context) : View(context) {
    abstract fun startIndicatorAnimation(onFinished: () -> Unit)
    abstract fun cancelIndicatorAnimation()
}

private class PressIndicatorView(
    context: Context,
    private val kind: PressKind,
    private val holdDurationMs: Long,
) : AnimatedIndicatorView(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var longPressProgress = 0f
    private var progressAnimator: android.animation.ValueAnimator? = null

    init {
        paint.color = INDICATOR_INDIGO
    }

    /**
     * 点击指示（规范 8.1、9.5）：Indigo 环 44（1.5 宽）+ 外圈 64 淡光晕（6% 填充 + 20% 描边），不放中心点；
     * 从 0.6 放大到 1 并淡入（fast + enter），保持 200ms，淡出并放大到 1.1（120ms + exit）。
     * 长按：按住期间环上一道 1.5 宽进度弧走完长按时长（linear），外圈填充 6% → 12%。
     */
    override fun startIndicatorAnimation(onFinished: () -> Unit) {
        alpha = 0f
        scaleX = 0.6f
        scaleY = 0.6f
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(160L)
            .setInterpolator(android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f))
            .withEndAction {
                if (kind == PressKind.LONG_PRESS) {
                    progressAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = holdDurationMs
                        interpolator = android.view.animation.LinearInterpolator()
                        addUpdateListener { longPressProgress = it.animatedValue as Float; invalidate() }
                        start()
                    }
                }
                animate()
                    .alpha(0f)
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .setDuration(120L)
                    .setStartDelay(holdDurationMs)
                    .setInterpolator(android.view.animation.PathInterpolator(0.3f, 0f, 0.8f, 0.15f))
                    .withEndAction { onFinished() }
                    .start()
            }
            .start()
    }

    override fun cancelIndicatorAnimation() {
        animate().cancel()
        progressAnimator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val ringRadius = 22f * density
        val haloRadius = 32f * density

        // 外圈淡光晕：6%（长按走到 12%）填充 + 20% 描边。
        paint.style = Paint.Style.FILL
        paint.alpha = ((0.06f + 0.06f * longPressProgress) * 255).toInt()
        canvas.drawCircle(cx, cy, haloRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.alpha = (0.20f * 255).toInt()
        canvas.drawCircle(cx, cy, haloRadius, paint)

        // 44 环，1.5 宽。
        paint.strokeWidth = 1.5f * density
        paint.alpha = 255
        canvas.drawCircle(cx, cy, ringRadius, paint)

        if (kind == PressKind.LONG_PRESS && longPressProgress > 0f) {
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 3f * density
            canvas.drawArc(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius, -90f, 360f * longPressProgress, false, paint)
            paint.strokeCap = Paint.Cap.BUTT
        }
    }
}

/** 规范 4.2：指示用 Indigo（accent/indigo-fg）。 */
private val INDICATOR_INDIGO = 0xFF4F56E3.toInt()

private class SwipeIndicatorView(
    context: Context,
    private val startX: Float,
    private val startY: Float,
    private val endX: Float,
    private val endY: Float,
    private val durationMs: Int
) : AnimatedIndicatorView(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private var alphaVal = 1f
    private var animator: AnimatorSet? = null

    init {
        paint.color = INDICATOR_INDIGO
        paint.strokeCap = Paint.Cap.ROUND
    }

    override fun startIndicatorAnimation(onFinished: () -> Unit) {
        val progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs.coerceAtLeast(300).toLong()
            addUpdateListener { animation ->
                progress = animation.animatedValue as Float
                invalidate()
            }
        }
        val alphaAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 160
            addUpdateListener { animation ->
                alphaVal = animation.animatedValue as Float
                invalidate()
            }
        }
        animator = AnimatorSet().apply {
            playSequentially(progressAnimator, alphaAnimator)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onFinished()
                }
            })
            start()
        }
    }

    override fun cancelIndicatorAnimation() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density

        // Current swipe head point
        val curX = startX + (endX - startX) * progress
        val curY = startY + (endY - startY) * progress

        // 先画极淡的路径预告，再让实线和指尖同步前进，避免轨迹看起来比手势先发生。
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.alpha = (alphaVal * 0.12f * 255).toInt()
        canvas.drawLine(startX, startY, endX, endY, paint)

        paint.strokeWidth = 3f * density
        paint.alpha = (alphaVal * 0.62f * 255).toInt()
        canvas.drawLine(startX, startY, curX, curY, paint)

        paint.style = Paint.Style.FILL
        paint.alpha = (alphaVal * 0.9f * 255).toInt()
        canvas.drawCircle(curX, curY, 7f * density, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.alpha = (alphaVal * 0.48f * 255).toInt()
        canvas.drawCircle(curX, curY, 13f * density, paint)

        paint.style = Paint.Style.FILL
        paint.alpha = (alphaVal * 0.5f * 255).toInt()
        canvas.drawCircle(startX, startY, min(4f * density, 4f * density * (1f - progress) + density), paint)
    }
}

/** 输入文字指示：控件外框描边，淡入 160ms（enter）→ 停留 → 淡出 120ms（exit）。 */
private class InputIndicatorView(context: Context, private val inset: Float) : AnimatedIndicatorView(context) {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = INDIGO
    }
    private var animator: Animator? = null

    override fun startIndicatorAnimation(onFinished: () -> Unit) {
        alpha = 0f
        val fadeIn = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160L
            interpolator = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
            addUpdateListener { alpha = it.animatedValue as Float }
        }
        val fadeOut = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 120L
            startDelay = HOLD_MS
            interpolator = android.view.animation.PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
            addUpdateListener { alpha = it.animatedValue as Float }
        }
        animator = AnimatorSet().apply {
            playSequentially(fadeIn, fadeOut)
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) onFinished() }
            })
            start()
        }
    }

    override fun cancelIndicatorAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        val half = paint.strokeWidth / 2f
        val radius = 8f * density
        canvas.drawRoundRect(inset - half, inset - half, width - inset + half, height - inset + half, radius, radius, paint)
    }

    private companion object {
        const val INDIGO = 0xFF4F56E3.toInt()
        const val HOLD_MS = 400L
    }
}
