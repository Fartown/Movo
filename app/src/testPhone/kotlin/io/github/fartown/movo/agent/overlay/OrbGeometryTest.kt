package io.github.fartown.movo.agent.overlay

import androidx.compose.ui.geometry.Size
import io.github.fartown.movo.agent.runtime.AgentRuntimeService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮球 ↔ 展开卡的几何闭环（规范 9.5「从球里长出、收回球里」）：按球的窗口参数摆出展开卡窗口，
 * 卡片里算出的揭开圆心换回屏幕坐标，必须正好是玻璃圆的圆心——球在任何位置、卡片多大、两侧停靠都一样。
 */
class OrbGeometryTest {
    private val density = 2.75f
    private fun px(dp: Int) = (dp * density).toInt()
    private val window = px(AgentRuntimeService.ORB_WINDOW_DP)
    private val disc = px(AgentRuntimeService.ORB_DISC_DP)
    private val shadow = px(AgentRuntimeService.PANEL_SHADOW_DP)
    private val lane = PANEL_ORB_LANE.value * density
    private val sidePadding = 12 * density

    // 小米 24129PN74C 整块屏幕（含状态栏、导航栏）。
    private val displayWidth = 1080
    private val displayHeight = 2400

    @Test
    fun defaultDockIsRightEdgeWithCenterAtThreeQuartersHeight() {
        val top = OrbGeometry.defaultTop(displayHeight, window)
        val d = OrbGeometry.disc(displayWidth, px(AgentRuntimeService.ORB_EDGE_DP), top, window, disc)
        assertEquals(displayHeight * 0.75f, d.centerY, 1f)
        assertEquals(displayWidth - px(AgentRuntimeService.ORB_EDGE_DP) - window / 2f, d.centerX, 1f)
    }

    @Test
    fun revealCenterIsTheDiscCenterWhereverTheOrbIs() {
        val cards = listOf(Size(616f, 300f), Size(770f, 520f))
        // 默认位置、右边靠上、左边、左边贴近屏幕底（阴影余量伸出屏幕）。
        val orbs = listOf(
            Triple(true, px(8), OrbGeometry.defaultTop(displayHeight, window)),
            Triple(true, px(8), 180),
            Triple(false, displayWidth - window - px(8), 1300),
            Triple(false, displayWidth - window - px(8), displayHeight - window),
        )
        for ((onEnd, fromRight, top) in orbs) for (card in cards) for (lift in listOf(0, 600)) {
            val d = OrbGeometry.disc(displayWidth, fromRight, top, window, disc)
            // 展开卡窗口：gravity = 球那一侧 | BOTTOM，大小 = 卡片 + 球侧通道 + 另一侧 12 + 上下各 12 的阴影余量。
            val windowWidth = card.width + lane + sidePadding
            val windowHeight = card.height + 2 * shadow
            val x = OrbGeometry.bubbleX(onEnd, displayWidth, fromRight, window)
            val y = OrbGeometry.bubbleY(displayHeight, top, window, disc, shadow) + lift
            val windowLeft = if (onEnd) displayWidth - x - windowWidth else x.toFloat()
            val windowTop = displayHeight - y - windowHeight
            val cardLeft = windowLeft + if (onEnd) sidePadding else lane
            val cardTop = windowTop + shadow
            val center = orbCenterInCard(card, density, onEnd, liftPx = lift.toFloat())
            assertEquals("x onEnd=$onEnd top=$top card=$card lift=$lift", d.centerX, cardLeft + center.x, 1f)
            assertEquals("y onEnd=$onEnd top=$top card=$card lift=$lift", d.centerY, cardTop + center.y, 1f)
        }
    }

    @Test
    fun cardBottomLinesUpWithTheDiscBottom() {
        val top = 1500
        val d = OrbGeometry.disc(displayWidth, px(8), top, window, disc)
        val y = OrbGeometry.bubbleY(displayHeight, top, window, disc, shadow)
        // 窗口底边距屏幕底 y，卡片底边再往上一个阴影余量。
        assertEquals(d.bottom, displayHeight - y - shadow)
    }

    @Test
    fun windowBoundsFollowGravity() {
        // 球窗口：右上对齐，离右 24、离顶 2268，边长 132。
        val orb = OrbGeometry.windowBounds(1440, 3200, android.view.Gravity.RIGHT or android.view.Gravity.TOP, 24, 2268, 132, 132)
        assertEquals(OrbGeometry.Bounds(1284, 2268, 1416, 2400), orb)
        assertTrue(orb.contains(1328f, 2300f))
        assertFalse(orb.contains(1416f, 2300f))
        // 展开卡窗口：右下对齐（球在右边），离右 24、离底 -20（阴影余量伸出屏幕外）。
        val panel = OrbGeometry.windowBounds(1440, 3200, android.view.Gravity.RIGHT or android.view.Gravity.BOTTOM, 24, -20, 1000, 560)
        assertEquals(OrbGeometry.Bounds(416, 2660, 1416, 3220), panel)
        // 球在左边：左下对齐。
        val leftPanel = OrbGeometry.windowBounds(1440, 3200, android.view.Gravity.LEFT or android.view.Gravity.BOTTOM, 24, 100, 1000, 560)
        assertEquals(OrbGeometry.Bounds(24, 2540, 1024, 3100), leftPanel)
    }
}
