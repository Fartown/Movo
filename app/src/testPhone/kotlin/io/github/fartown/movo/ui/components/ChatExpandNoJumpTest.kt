package io.github.fartown.movo.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.data.model.AppearanceSettings
import io.github.fartown.movo.ui.app.AgentAppTheme
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.ThinkingMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.UserMessageUi
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 真机回归：对话停在底部、「保持最新」开着（对话浮层、打开已有对话）时，点开回答上面的执行卡，
 * 卡片要原地往下展开；原来跟底逻辑会把列表往回滚，刚展开的步骤被滚到顶栏后面（展开一次跳一次）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "zh-rCN-w412dp-h917dp-xxhdpi")
class ChatExpandNoJumpTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun expandingTheWorkCardAboveTheAnswerKeepsItsHeaderInPlace() {
        val t = System.currentTimeMillis() - 60_000
        fun step(id: String, tool: String, title: String, summary: String, at: Long) =
            ToolActivityMessageUi(id, tool, ToolActivityStatusUi.Success, title, resultSummary = summary,
                startedAtMillis = t + at, finishedAtMillis = t + at + 300)
        val answer = "已经在设置里搜好了，搜索框里是「蓝牙」，结果列表已列出。\\n\\n" +
            (1..12).joinToString("\\n\\n") { "第 $it 段：后面几条顺带一提，第 $it 条也是「蓝牙」相关的设置项，路径在设置的高级选项里。" }
        val messages = listOf(
            UserMessageUi("u1", "打开设置，搜索蓝牙，然后告诉我第一条结果", runStartedAtMillis = t, runFinishedAtMillis = t + 15_000),
            ThinkingMessageUi("th1", "The user wants me to open settings and search for Bluetooth.", isStreaming = false, elapsedSeconds = 2),
            step("s1", "app_open", "打开「设置」", "已打开「设置」", 0),
            ThinkingMessageUi("th2", "Settings is open. Let me observe the screen.", isStreaming = false, elapsedSeconds = 1),
            step("s2", "ui_observe", "查看屏幕", "「设置」 · 31 个元素", 1000),
            step("s3", "ui_tap", "点按「搜索系统设置项」", "已点按", 2000),
            step("s4", "ui_input", "输入「蓝牙」", "已输入", 3000),
            step("s5", "ui_observe", "查看屏幕", "「设置」 · 24 个元素", 4000),
            AgentMessageUi("a1", answer),
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AgentAppTheme(appearance = AppearanceSettings(), applyInterfaceScale = false) {
                val state = rememberLazyListState()
                var anchored by remember { mutableStateOf(true) }
                AgentConversationMessages(
                    visibleMessages = messages,
                    scrollState = state,
                    bottomReserve = remember { ChatBottomReserve() },
                    isStreaming = false,
                    bottomInset = 120.dp,
                    keepBottomAnchored = anchored,
                    keepLatestOnResize = true,
                    onBottomAnchorChanged = { anchored = it },
                    modifier = Modifier.fillMaxSize(),
                )
                androidx.compose.runtime.LaunchedEffect(Unit) { state.scrollToItem(messages.size + 2) }
            }
        }
        compose.mainClock.advanceTimeBy(3_000)
        val header = compose.onAllNodes(hasText("已完成", substring = true))[0]
        val before = header.getUnclippedBoundsInRoot().top
        header.performClick()
        val tops = mutableListOf<Float>()
        repeat(60) {
            compose.mainClock.advanceTimeByFrame()
            tops += compose.onAllNodes(hasText("已完成", substring = true))[0].getUnclippedBoundsInRoot().top.value
        }
        compose.mainClock.advanceTimeBy(1_000)
        val after = compose.onAllNodes(hasText("已完成", substring = true))[0].getUnclippedBoundsInRoot().top
        val maxShift = tops.maxOf { kotlin.math.abs(it - before.value) }
        assertTrue("卡头在展开过程中移动了 ${maxShift}dp（逐帧：$tops）", maxShift < 1f)
        assertTrue("卡头最终从 ${before} 移到了 ${after}", kotlin.math.abs(after.value - before.value) < 1f)
    }
}
