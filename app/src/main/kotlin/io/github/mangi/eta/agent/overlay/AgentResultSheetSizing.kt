package io.github.mangi.eta.agent.overlay

import kotlin.math.roundToInt

/** Drag sizes the actual overlay window, so the readable content grows with the sheet. */
internal object AgentResultSheetSizing {
    const val COLLAPSED_RATIO = 0.55f
    const val EXPANDED_RATIO = 0.92f

    fun height(screenHeight: Int, expanded: Boolean): Int =
        (screenHeight * if (expanded) EXPANDED_RATIO else COLLAPSED_RATIO).roundToInt().coerceAtLeast(1)

    fun drag(currentHeight: Int, deltaY: Float, screenHeight: Int): Int =
        (currentHeight - deltaY).roundToInt().coerceIn(height(screenHeight, false), height(screenHeight, true))

    fun settleExpanded(currentHeight: Int, screenHeight: Int, velocityY: Float, flingThreshold: Float): Boolean =
        when {
            velocityY < -flingThreshold -> true
            velocityY > flingThreshold -> false
            else -> currentHeight >= (height(screenHeight, false) + height(screenHeight, true)) / 2
        }
}
