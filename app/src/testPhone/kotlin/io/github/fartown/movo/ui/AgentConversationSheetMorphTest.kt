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

/** Q4 球 ↔ 浮层：浮层轮廓（Compose 图层裁切，按整个窗口的坐标）从悬浮球玻璃圆插值到整块浮层。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentConversationSheetMorphTest {
    private val window = Size(1080f, 2400f)
    private val sheet = androidx.compose.ui.geometry.Rect(0f, 900f, 1080f, 2400f)
    private val orb = android.graphics.RectF(980f, 1800f, 1076f, 1896f)

    private fun outline(progress: Float, from: android.graphics.RectF = orb) =
        orbMorphShape(from, progress, sheet, sheetRadius = 84f)
            .createOutline(window, LayoutDirection.Ltr, Density(3f)) as Outline.Rounded

    @Test
    fun startsAsTheOrbDisc() {
        val rect = outline(0f).roundRect
        assertEquals(980f, rect.left, 0.01f)
        assertEquals(1800f, rect.top, 0.01f)
        assertEquals(1076f, rect.right, 0.01f)
        assertEquals(1896f, rect.bottom, 0.01f)
        assertEquals(48f, rect.topLeftCornerRadius.x, 0.01f)
    }

    @Test
    fun anOrbAboveTheSheetStillStartsAtTheOrb() {
        // 球拖到了靠上的位置（在浮层顶边上方）：起点仍是球本身，不被浮层顶边截成平底。
        val high = android.graphics.RectF(980f, 300f, 1076f, 396f)
        val rect = outline(0f, high).roundRect
        assertEquals(300f, rect.top, 0.01f)
        assertEquals(396f, rect.bottom, 0.01f)
        assertEquals(900f, outline(1f, high).roundRect.top, 0.01f)
    }

    @Test
    fun endsAsTheSheetWithItsBottomCornersBelowTheScreen() {
        val rect = outline(1f).roundRect
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(900f, rect.top, 0.01f)
        assertEquals(1080f, rect.right, 0.01f)
        assertEquals(2400f + 84f, rect.bottom, 0.01f)
        assertEquals(84f, rect.topLeftCornerRadius.x, 0.01f)
    }
}
