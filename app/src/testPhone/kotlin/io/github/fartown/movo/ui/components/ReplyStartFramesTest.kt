package io.github.fartown.movo.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.data.model.AppearanceSettings
import io.github.fartown.movo.ui.app.AgentAppTheme
import io.github.fartown.movo.ui.model.AgentChatMessageUi
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.ThinkingMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.UserMessageUi
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 回答开始流式输出前后逐帧截图与位置（10-07「回复一开始会跳一下」的回归）：纯问答时回答不随「已思考」预览收起上移，
 * 带工具时思考步骤的预览换成全文不叠字、不错位。默认跳过；
 * `./gradlew :app:testPhoneDebugUnitTest -PmovoShots=true --tests '*ReplyStartFramesTest*'`，看 movo.shots.dir/reply-start/ 下的帧与 bounds.txt。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "zh-rCN-w412dp-h917dp-xxhdpi")
class ReplyStartFramesTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun enabled() {
        assumeTrue(System.getProperty("movo.shots") == "true")
    }

    private val t = System.currentTimeMillis() - 30_000
    private val chunks = listOf("杭州是浙江省的省会，", "位于中国东南沿海。", "它以西湖闻名，", "风景秀丽、人文荟萃。", "\n\n杭州也是", "数字经济的重镇，", "阿里巴巴等企业总部都在这里。")

    private fun run(name: String, start: List<AgentChatMessageUi>, thinkingDone: List<AgentChatMessageUi>, answer: (String) -> List<AgentChatMessageUi>) {
        var messages by mutableStateOf(start)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AgentAppTheme(appearance = AppearanceSettings(), applyInterfaceScale = false) {
                val state = rememberLazyListState()
                var anchored by remember { mutableStateOf(true) }
                AgentConversationMessages(
                    visibleMessages = messages,
                    scrollState = state,
                    bottomReserve = remember { ChatBottomReserve() },
                    isStreaming = true,
                    bottomInset = 120.dp,
                    keepBottomAnchored = anchored,
                    onBottomAnchorChanged = { anchored = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        val dir = File(System.getProperty("movo.shots.dir") ?: "build/shots", "reply-start/$name").apply { mkdirs() }
        val log = StringBuilder()
        fun bounds(text: String) = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().joinToString(";") { n -> "%.0f-%.0f".format(n.boundsInRoot.top, n.boundsInRoot.bottom) }
        fun capture(index: Int, note: String) {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(dir, "f%03d.png".format(index)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            log.append("f%03d %-10s user=[%s] thought=[%s] answer=[%s]\n".format(index, note, bounds("请用两三句话"), bounds("思考"), bounds("杭州是")))
        }
        var frame = 0
        capture(frame++, "start")
        messages = thinkingDone
        repeat(3) { compose.mainClock.advanceTimeByFrame(); capture(frame++, "thinkDone") }
        var text = ""
        chunks.forEachIndexed { i, c ->
            text += c
            messages = answer(text)
            repeat(4) { compose.mainClock.advanceTimeByFrame(); capture(frame++, "chunk$i") }
        }
        repeat(20) { compose.mainClock.advanceTimeByFrame(); capture(frame++, "tail") }
        compose.mainClock.advanceTimeBy(1_000)
        capture(99, "settled")
        File(dir, "bounds.txt").writeText(log.toString())
    }

    private val user = UserMessageUi("u1", "请用两三句话介绍一下杭州", runStartedAtMillis = t)
    private val thinkingText = "The user wants a short introduction to Hangzhou in two or three sentences. I should mention West Lake and the digital economy."

    /** 纯问答：思考（两行预览）→ 思考结束 → 回答第一个字到达、开始流式输出。 */
    @Test
    fun plainAnswer() {
        val streamingThought = ThinkingMessageUi("run-thinking-1-0", thinkingText, isStreaming = true, collapsed = false)
        val doneThought = ThinkingMessageUi("run-thinking-1-0", thinkingText, isStreaming = false, elapsedSeconds = 3, collapsed = true)
        run(
            "qa",
            start = listOf(user, streamingThought),
            thinkingDone = listOf(user, doneThought),
            answer = { listOf(user, doneThought, AgentMessageUi("assistant-run-1-1", it, isStreaming = true)) },
        )
    }

    /** 带工具：执行卡里工具已完成、第二轮思考中 → 思考结束 → 最后一段开始写（先在卡里预览）。 */
    @Test
    fun answerAfterTool() {
        val th1 = ThinkingMessageUi("run-thinking-1-0", "Need the battery level.", isStreaming = false, elapsedSeconds = 1, collapsed = true)
        val tool = ToolActivityMessageUi(
            "run-tool-1-c1", "device_read", ToolActivityStatusUi.Success, "查看设备状态 · 电池",
            resultSummary = "电量 100%（充电中）", startedAtMillis = t + 1000, finishedAtMillis = t + 1300,
        )
        val th2s = ThinkingMessageUi("run-thinking-2-0", thinkingText, isStreaming = true, collapsed = false)
        val th2 = ThinkingMessageUi("run-thinking-2-0", thinkingText, isStreaming = false, elapsedSeconds = 2, collapsed = true)
        run(
            "tool",
            start = listOf(user, th1, tool, th2s),
            thinkingDone = listOf(user, th1, tool, th2),
            answer = { listOf(user, th1, tool, th2, AgentMessageUi("assistant-run-2-1", it, isStreaming = true, provisional = true)) },
        )
    }
}
