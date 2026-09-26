package io.github.fartown.movo.ui

import android.app.Application
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Q4 球 ↔ 浮层：浮层轮廓（Compose 图层裁切，不再改窗口）从悬浮球玻璃圆插值到整块浮层。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentConversationSheetMorphTest {
    private val sheet = Size(1080f, 1500f)
    private val orb = android.graphics.RectF(980f, 900f, 1076f, 996f)

    private fun outline(progress: Float) =
        orbMorphShape(orb, progress, sheet, sheetRadius = 84f)
            .createOutline(sheet, LayoutDirection.Ltr, Density(3f)) as Outline.Rounded

    @Test
    fun startsAsTheOrbDisc() {
        val rect = outline(0f).roundRect
        assertEquals(980f, rect.left, 0.01f)
        assertEquals(900f, rect.top, 0.01f)
        assertEquals(1076f, rect.right, 0.01f)
        assertEquals(996f, rect.bottom, 0.01f)
        assertEquals(48f, rect.topLeftCornerRadius.x, 0.01f)
    }

    @Test
    fun endsAsTheSheetWithItsBottomCornersBelowTheScreen() {
        val rect = outline(1f).roundRect
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(0f, rect.top, 0.01f)
        assertEquals(1080f, rect.right, 0.01f)
        assertEquals(1500f + 84f, rect.bottom, 0.01f)
        assertEquals(84f, rect.topLeftCornerRadius.x, 0.01f)
    }
}
