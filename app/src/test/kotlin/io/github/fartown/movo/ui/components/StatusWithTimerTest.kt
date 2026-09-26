package io.github.fartown.movo.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.ui.theme.MovoTypography
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
class StatusWithTimerTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(widthDp: Int) = compose.setContent {
        StatusWithTimer(
            status = { Text("已完成 8 个步骤", style = MovoTypography.labelMedium, maxLines = 1) },
            timer = "用时 1 分 13 秒",
            compactTimer = "1:13",
            timerColor = Color.Gray,
            modifier = Modifier.width(widthDp.dp),
        )
    }

    @Test
    fun fullTimerWhenThereIsRoom() {
        show(240)
        compose.onNodeWithText("用时 1 分 13 秒").assertIsDisplayed()
        compose.onNodeWithText("1:13").assertIsNotDisplayed()
    }

    @Test
    fun compactTimerWhenTight() {
        show(150)
        compose.onNodeWithText("已完成 8 个步骤").assertIsDisplayed()
        compose.onNodeWithText("1:13").assertIsDisplayed()
    }
}
