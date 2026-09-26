package io.github.mangi.eta.agent.overlay

import kotlin.math.roundToInt
import kotlin.math.abs

/** Keeps a drag in screen space while the window's own coordinate origin is moving. */
internal class AgentResultSheetScreenDrag {
    private var lastY: Float? = null
    private var dragging = false

    fun start(screenY: Float) {
        lastY = screenY
        dragging = false
    }

    fun move(screenY: Float, touchSlop: Float): Float? {
        val previous = lastY ?: return null
        val delta = screenY - previous
        if (!dragging && abs(delta) <= touchSlop) return null
        dragging = true
        lastY = screenY
        return delta
    }

    fun finish(): Boolean {
        val wasDragging = dragging
        lastY = null
        dragging = false
        return wasDragging
    }
}

/** Drag sizes the actual overlay window, so the readable content grows with the sheet. */
internal object AgentResultSheetSizing {
    const val COLLAPSED_RATIO = 0.55f
    const val EXPANDED_RATIO = 1f

    fun height(screenHeight: Int, expanded: Boolean, keyboardLift: Int = 0): Int =
        if (expanded) screenHeight.coerceAtLeast(1)
        else ((screenHeight * COLLAPSED_RATIO).roundToInt() + keyboardLift.coerceAtLeast(0))
            .coerceIn(1, screenHeight.coerceAtLeast(1))

    // Keep fractional pixels across samples. High-rate slow gestures commonly move < 0.5px
    // per event; rounding each delta against an integer height would discard the whole drag.
    fun drag(currentHeight: Float, deltaY: Float, screenHeight: Int, keyboardLift: Int = 0): Float =
        (currentHeight - deltaY)
            .coerceIn(height(screenHeight, false, keyboardLift).toFloat(), height(screenHeight, true).toFloat())

    /** 下拉关闭：拖过半屏高度的 1/3，或向下快速甩动（规范 8.9）；向上甩回弹。 */
    fun shouldDismiss(offset: Float, sheetHeight: Int, velocityY: Float, flingThreshold: Float): Boolean = when {
        velocityY > flingThreshold -> true
        velocityY < -flingThreshold -> false
        else -> offset > sheetHeight.coerceAtLeast(1) / 3f
    }

    fun settleExpanded(currentHeight: Int, screenHeight: Int, velocityY: Float, flingThreshold: Float, keyboardLift: Int = 0): Boolean =
        when {
            velocityY < -flingThreshold -> true
            velocityY > flingThreshold -> false
            else -> currentHeight >= (height(screenHeight, false, keyboardLift) + height(screenHeight, true)) / 2
        }
}
