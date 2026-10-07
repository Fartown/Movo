package io.github.fartown.movo.agent.accessibility

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlin.math.hypot

/**
 * 无障碍手势的笔画。一次 dispatchGesture 描述「按下 → 沿路径匀速移动 → 抬起」；需要时可以拆成同一根手指的几段连续笔画
 * （前几段 willContinue 不抬起，后一段 continueStroke 接上，各自 dispatchGesture，见 [chain]）。
 */
internal object GestureStrokes {
    /** 一段笔画：沿 [path] 走 [durationMs]。 */
    data class Stroke(val path: Path, val durationMs: Long)

    /** 按住阶段手指来回的幅度（px），远小于系统判定「移动了」的 touch slop（约 8dp），长按照常触发。 */
    private const val HOLD_WIGGLE_PX = 2f

    /**
     * 先在 ([x1], [y1]) 按住 [holdMs]，再用 [dragMs] 拖到 ([x2], [y2]) 抬起，做成一段笔画（一次 dispatchGesture）。
     * 笔画沿路径匀速走，所以按住阶段让手指在起点来回 [HOLD_WIGGLE_PX]，这段路径长度按「按住时长 : 拖动时长」折算。
     * 不用 willContinue 的两段连续笔画：真机上小米桌面接不上第二段，工具报成功、图标却没动。
     */
    fun holdThenDrag(x1: Float, y1: Float, x2: Float, y2: Float, holdMs: Long, dragMs: Long): List<Stroke> {
        val dragLength = hypot(x2 - x1, y2 - y1).coerceAtLeast(1f)
        val holdLength = dragLength * holdMs / dragMs.coerceAtLeast(1)
        val path = Path().apply {
            moveTo(x1, y1)
            var drawn = 0f
            var away = true
            while (drawn < holdLength) {
                lineTo(x1, if (away) y1 + HOLD_WIGGLE_PX else y1)
                drawn += HOLD_WIGGLE_PX
                away = !away
            }
            lineTo(x1, y1)
            lineTo(x2, y2)
        }
        return listOf(Stroke(path, holdMs + dragMs))
    }

    /**
     * 把几段笔画串成同一根手指：除最后一段外都不抬起（willContinue），后一段由前一段 continueStroke 得到。
     * 只有一段时就是普通笔画。每个元素单独作为一个手势派发。
     */
    fun chain(strokes: List<Stroke>): List<GestureDescription.StrokeDescription> {
        require(strokes.isNotEmpty()) { "手势至少要一段笔画" }
        val result = mutableListOf<GestureDescription.StrokeDescription>()
        strokes.forEachIndexed { index, stroke ->
            val willContinue = index < strokes.lastIndex
            val previous = result.lastOrNull()
            result += previous?.continueStroke(stroke.path, 0, stroke.durationMs, willContinue)
                ?: GestureDescription.StrokeDescription(stroke.path, 0, stroke.durationMs, willContinue)
        }
        return result
    }
}
