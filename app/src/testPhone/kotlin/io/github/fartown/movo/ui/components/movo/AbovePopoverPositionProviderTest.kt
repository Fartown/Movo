package io.github.fartown.movo.ui.components.movo

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/** 输入框菜单（`Popover/Menu`）出现在整个输入框上方，而不是盖住按钮所在的输入框。 */
class AbovePopoverPositionProviderTest {
    private val anchor = IntRect(left = 400, top = 1_850, right = 500, bottom = 1_900)
    private val window = IntSize(width = 1_080, height = 2_200)

    @Test
    fun endAlignedMenuSitsAboveTheInputContainer() {
        // 弹层四周 60 的阴影留白；可见底边 = 输入框上缘 1800 − 间距 24。
        val result = AbovePopoverPositionProvider(aboveYPx = 1_800, gapPx = 24, shadowPadPx = 60, alignEnd = true)
            .calculatePosition(anchor, window, LayoutDirection.Ltr, IntSize(width = 420, height = 720))

        assertEquals(500 - 420 + 60, result.x)
        assertEquals(1_800 - 24 + 60 - 720, result.y)
    }

    @Test
    fun startAlignedMenuFollowsTheAnchorAndMirrorsInRtl() {
        val size = IntSize(width = 400, height = 300)
        val ltr = AbovePopoverPositionProvider(1_800, 24, 60, alignEnd = false)
            .calculatePosition(anchor, window, LayoutDirection.Ltr, size)
        assertEquals(400 - 60, ltr.x)

        val rtl = AbovePopoverPositionProvider(1_800, 24, 60, alignEnd = false)
            .calculatePosition(anchor, window, LayoutDirection.Rtl, size)
        assertEquals(500 - 400 + 60, rtl.x)
    }

    @Test
    fun clampsIntoTheWindowAndFallsBackToTheAnchorTop() {
        val result = AbovePopoverPositionProvider(aboveYPx = 0, gapPx = 24, shadowPadPx = 60, alignEnd = false)
            .calculatePosition(
                IntRect(left = 20, top = 200, right = 120, bottom = 260),
                window,
                LayoutDirection.Ltr,
                IntSize(width = 1_200, height = 900),
            )

        assertEquals(0, result.x)
        assertEquals(0, result.y)
    }
}
