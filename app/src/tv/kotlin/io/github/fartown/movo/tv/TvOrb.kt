package io.github.fartown.movo.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator
import android.animation.ValueAnimator

/** 光球外圈：与手机悬浮球同一套符号（Figma「Movo TV」候选页 v2 · 组件 TV/Orb）。 */
internal enum class TvOrbRing { None, Listening, Working, Speaking, Done, Focused }

/**
 * Movo 光球（手机 Movo/Orb · Logo 的同参数版本）。电视上不做模糊和循环渐变，只画静态渐变 + M；
 * 「在想 / 在做」的外圈弧线旋转，是唯一的循环动画。
 */
internal object TvOrbPainter {
    private val sweepColors = intArrayOf(0xFFFFC2A6.toInt(), 0xFFFF9EC2.toInt(), 0xFFB39BFF.toInt(), 0xFF8CC6FF.toInt(), 0xFFBFEFE3.toInt(), 0xFFFFC2A6.toInt())
    private val sweepStops = floatArrayOf(0f, .2f, .42f, .64f, .82f, 1f)
    private const val INDIGO = 0xFF4F56E3.toInt()
    private const val GREEN = 0xFF178A55.toInt()

    /** 在 [cx],[cy] 画直径 [d] 的光球；[ringInset] 是外圈到球的距离，[sweep] 是工作弧线的起始角度。 */
    fun draw(canvas: Canvas, cx: Float, cy: Float, d: Float, ring: TvOrbRing, ringInset: Float, sweep: Float, density: Float,
             gradientRotation: Float = 0f) {
        val r = d / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFFEDE6FF.toInt(); canvas.drawCircle(cx, cy, r, paint)
        // 「在想 / 在做」时只转渐变（手机 §9.7：思考转速 ×2），M 不动。
        paint.shader = SweepGradient(cx, cy, sweepColors, sweepStops).apply {
            if (gradientRotation != 0f) setLocalMatrix(android.graphics.Matrix().apply { setRotate(gradientRotation, cx, cy) })
        }; paint.alpha = 0xE6
        canvas.drawCircle(cx, cy, r, paint)
        paint.alpha = 0xFF
        // 柔光：左上白色高光（手机版 38.4/62 的径向渐变）。
        val hlR = d * 0.62f
        val hx = cx - r + d * 0.03f + hlR / 2f; val hy = cy - r - d * 0.03f + hlR / 2f
        paint.shader = RadialGradient(hx, hy, hlR, intArrayOf(0xD9FFFFFF.toInt(), 0x47FFFFFF, 0x00FFFFFF), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint)
        // M：手机路径按 62 设计，等比缩放。
        val k = d / 62f
        val ox = cx - r + 16.5f * k; val oy = cy - r + 26.9f * k
        val m = Path().apply {
            moveTo(ox, oy + 15.05f * k)
            cubicTo(ox, oy - 1.95f * k, ox + 8f * k, oy - 4.45f * k, ox + 14.5f * k, oy + 7.05f * k)
            cubicTo(ox + 21f * k, oy - 4.45f * k, ox + 29f * k, oy - 1.95f * k, ox + 29f * k, oy + 15.05f * k)
        }
        paint.shader = LinearGradient(ox, 0f, ox + 29f * k, 0f, intArrayOf(0xFFFFE6DA.toInt(), 0xFFEFE8FF.toInt(), 0xFFDDEFFF.toInt()), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 6.5f * k; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        paint.alpha = 0xE6
        canvas.drawPath(m, paint)
        paint.shader = null; paint.alpha = 0xFF
        val rr = r + ringInset
        val oval = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
        paint.style = Paint.Style.STROKE
        when (ring) {
            TvOrbRing.None -> Unit
            TvOrbRing.Listening -> { paint.color = INDIGO; paint.alpha = 0x59; paint.strokeWidth = 2f * density; canvas.drawOval(oval, paint) }
            TvOrbRing.Speaking -> { paint.color = INDIGO; paint.alpha = 0x33; paint.strokeWidth = 2f * density; canvas.drawOval(oval, paint) }
            TvOrbRing.Focused -> { paint.color = INDIGO; paint.strokeWidth = 4f * density; canvas.drawOval(oval, paint) }
            TvOrbRing.Done -> { paint.color = GREEN; paint.strokeWidth = 2.5f * density; canvas.drawOval(oval, paint) }
            TvOrbRing.Working -> { paint.color = INDIGO; paint.strokeWidth = 3f * density; paint.strokeCap = Paint.Cap.ROUND; canvas.drawArc(oval, sweep - 90f, 200f, false, paint) }
        }
    }
}

/** 原生 View 版光球（无障碍浮层里用）。只有工作态旋转外圈，其他状态静止。 */
internal class TvOrbView(context: Context, private val orbDp: Float) : View(context) {
    private val density = resources.displayMetrics.density
    private var sweep = 0f
    private var spinner: ValueAnimator? = null
    var ring: TvOrbRing = TvOrbRing.None
        set(value) {
            if (field == value) return
            field = value
            if (value == TvOrbRing.Working) startSpin() else stopSpin()
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = ((orbDp + 8f) * density).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        TvOrbPainter.draw(canvas, width / 2f, height / 2f, orbDp * density, ring, 3f * density, sweep, density)
    }

    private fun startSpin() {
        if (spinner != null) return
        spinner = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1200; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopSpin() { spinner?.cancel(); spinner = null }

    override fun onDetachedFromWindow() { stopSpin(); super.onDetachedFromWindow() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (ring == TvOrbRing.Working) startSpin() }
}
