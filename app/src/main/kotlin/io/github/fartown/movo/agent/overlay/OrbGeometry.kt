package io.github.fartown.movo.agent.overlay

/**
 * 悬浮球、展开卡、对话浮层共用的一套位置（规范 8.1 / 9.5「从球里长出、收回球里」）。
 *
 * 全部是整块屏幕上的绝对像素坐标：浮窗都不被状态栏、导航栏、刘海推开，屏幕尺寸取整块屏幕（含系统栏）。
 * 球的位置只由它自己的窗口参数（右边距、上边距）决定；展开卡窗口、展开卡揭开的圆心、对话浮层的起点都从这里推出来，
 * 不读窗口在屏幕上的实际位置（窗口刚移动、刚加上时读到的是上一帧或还没排版的值）。
 */
internal object OrbGeometry {
    /** 玻璃圆在屏幕上的位置（px）。 */
    data class Disc(val left: Int, val top: Int, val size: Int) {
        val right: Int get() = left + size
        val bottom: Int get() = top + size
        val centerX: Float get() = left + size / 2f
        val centerY: Float get() = top + size / 2f
    }

    /** 球窗口（边长 [window]，右边距 [fromRight]、上边距 [top]）里玻璃圆（边长 [disc]，居中）的位置。 */
    fun disc(displayWidth: Int, fromRight: Int, top: Int, window: Int, disc: Int): Disc {
        val inset = (window - disc) / 2
        return Disc(left = displayWidth - fromRight - window + inset, top = top + inset, size = disc)
    }

    /** 默认停靠：球心在 75% 屏高（规范 8.1，单手拇指可及）。返回球窗口的上边距。 */
    fun defaultTop(displayHeight: Int, window: Int): Int = (displayHeight * 0.75f).toInt() - window / 2

    /**
     * 展开卡窗口（`gravity = 球那一侧 | BOTTOM`）的 x：窗口在球那一侧的外边缘与球窗口的外边缘对齐。
     * [onEnd] = 球在右边。
     */
    fun bubbleX(onEnd: Boolean, displayWidth: Int, orbFromRight: Int, window: Int): Int =
        if (onEnd) orbFromRight else displayWidth - orbFromRight - window

    /** 浮窗在屏幕上占的范围（px，右、下不含）。 */
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
    }

    /**
     * 浮窗（球、展开卡）在屏幕上占的范围：按窗口参数（[gravity] 已转成绝对方向；x、y 按 gravity 是离右边 / 离底的距离）
     * 和量好的大小算。用来判断 Agent 要按的点是否被浮窗挡住（规范 8.1）。
     */
    fun windowBounds(displayWidth: Int, displayHeight: Int, gravity: Int, x: Int, y: Int, width: Int, height: Int): Bounds {
        val left = if (gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK == android.view.Gravity.RIGHT) {
            displayWidth - x - width
        } else {
            x
        }
        val top = if (gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK == android.view.Gravity.BOTTOM) {
            displayHeight - y - height
        } else {
            y
        }
        return Bounds(left, top, left + width, top + height)
    }

    /**
     * 展开卡窗口的 y（距屏幕底）：卡片底边与玻璃圆底边对齐，卡片下方留 [shadow] 的阴影余量。
     * 可以为负（球贴近屏幕底时阴影余量伸出屏幕外，浮窗不限制在屏幕内）；夹到 0 会让卡片相对球错位。
     */
    fun bubbleY(displayHeight: Int, orbTop: Int, window: Int, disc: Int, shadow: Int): Int {
        // 与 [disc] 同样取整，奇数像素差时两边不差 1px。
        val discBottom = orbTop + (window - disc) / 2 + disc
        return displayHeight - discBottom - shadow
    }
}
