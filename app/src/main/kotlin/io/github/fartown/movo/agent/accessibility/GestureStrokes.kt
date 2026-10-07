package io.github.fartown.movo.agent.accessibility

import android.accessibilityservice.GestureDescription
import android.graphics.Path

/**
 * 无障碍手势的笔画。一次 dispatchGesture 只能描述「按下 → 移动 → 抬起」；「先按住不动、再拖」要拆成同一根手指
 * 的几段连续笔画：前几段 willContinue（不抬起），后一段用 continueStroke 接上一段，各自 dispatchGesture。
 */
internal object GestureStrokes {
    /** 一段笔画：沿 [path] 走 [durationMs]。 */
    data class Stroke(val path: Path, val durationMs: Long)

    /** 先在 ([x1], [y1]) 原地按住 [holdMs]，再用 [dragMs] 拖到 ([x2], [y2]) 抬起。 */
    fun holdThenDrag(x1: Float, y1: Float, x2: Float, y2: Float, holdMs: Long, dragMs: Long): List<Stroke> = listOf(
        Stroke(Path().apply { moveTo(x1, y1); lineTo(x1, y1) }, holdMs),
        Stroke(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, dragMs),
    )

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
