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
 * 说明进出执行卡的过渡逐帧截图（定稿 21，方案 2：淡出 + 展开 / 收起）。默认跳过；
 * `./gradlew :app:testDebugUnitTest -PmovoShots=true --tests '*NarrationTransitionFramesTest*'`
 * 把每一帧写到 movo.shots.dir/narration-frames/，用来逐帧检查有没有硬切、空白帧、跳动。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "zh-rCN-w412dp-h917dp-xxhdpi")
class NarrationTransitionFramesTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun enabled() {
        assumeTrue(System.getProperty("movo.shots") == "true")
    }

    private val t = System.currentTimeMillis() - 30_000
    private val user = UserMessageUi("u1", "请分三次单独查询：先查电量，再查存储，最后查 Wi‑Fi 名称", runStartedAtMillis = t)
    private fun thinking(round: Int, text: String) =
        ThinkingMessageUi("run-thinking-$round-0", text, isStreaming = false, elapsedSeconds = 2, collapsed = true)
    private fun tool(round: Int, title: String, summary: String, status: ToolActivityStatusUi = ToolActivityStatusUi.Success) =
        ToolActivityMessageUi("run-tool-$round-c$round", "device_read", status, title,
            resultSummary = summary.takeIf { status == ToolActivityStatusUi.Success },
            startedAtMillis = t + round * 1000L, finishedAtMillis = (t + round * 1000L + 300).takeIf { status == ToolActivityStatusUi.Success })

    private fun run(name: String, before: List<AgentChatMessageUi>, after: List<AgentChatMessageUi>, streamingAfter: Boolean, streamingSwitchFrame: Int = 0) {
        var messages by mutableStateOf(before)
        var streaming by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AgentAppTheme(appearance = AppearanceSettings(), applyInterfaceScale = false) {
                val state = rememberLazyListState()
                var anchored by remember { mutableStateOf(true) }
                AgentConversationMessages(
                    visibleMessages = messages,
                    scrollState = state,
                    bottomReserve = remember { ChatBottomReserve() },
                    isStreaming = streaming,
                    bottomInset = 120.dp,
                    keepBottomAnchored = anchored,
                    onBottomAnchorChanged = { anchored = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        val dir = File(System.getProperty("movo.shots.dir") ?: "build/shots", "narration-frames/$name").apply { mkdirs() }
        val log = StringBuilder()
        fun capture(index: Int) {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(dir, "f%03d.png".format(index)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fun bounds(text: String) = compose.onAllNodes(androidx.compose.ui.test.hasText(text, substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().joinToString(";") { n -> "%.0f-%.0f".format(n.boundsInRoot.top, n.boundsInRoot.bottom) }
            fun desc(text: String) = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(text), useUnmergedTree = true).fetchSemanticsNodes().size
            log.append("f%03d header=[%s] thinking=[%s] narration=[%s] chevron=收起%d/展开%d\n".format(index, bounds("正在执行"), bounds("思考·"), bounds("我先查一下"), desc("收起") + desc("Collapse"), desc("展开") + desc("Expand")))
        }
        capture(0)
        messages = after
        if (streamingSwitchFrame == 0) streaming = streamingAfter
        repeat(30) { i ->
            if (i == streamingSwitchFrame && i > 0) streaming = streamingAfter
            compose.mainClock.advanceTimeByFrame()
            capture(i + 1)
        }
        compose.mainClock.advanceTimeBy(1_000)
        capture(99)
        File(dir, "bounds.txt").writeText(log.toString())
    }

    /** 第一轮：「已思考」+ 卡外正文 → 调工具，执行卡从「已思考」一行长出来，正文进卡。 */
    @Test
    fun firstRoundTextMovesIntoTheNewCard() {
        val th1 = thinking(1, "The user wants three separate queries, each in its own tool call.")
        val text = "我先查一下当前电量。"
        run(
            "first-round",
            before = listOf(user, th1, AgentMessageUi("assistant-run-1-1", text, isStreaming = false)),
            after = listOf(user, th1, AgentMessageUi("assistant-run-1-1", text, isStreaming = false, narration = true),
                tool(1, "查看设备状态 · 电池", "", ToolActivityStatusUi.Running)),
            streamingAfter = true,
        )
    }

    /** 结束时：卡里最后一段 → 任务结束，移出卡片成为回答，卡片收成摘要条。 */
    @Test
    fun lastSegmentLeavesTheCardWhenTheRunEnds() {
        val head = listOf(
            user,
            thinking(1, "The user wants three separate queries."),
            AgentMessageUi("assistant-run-1-1", "我先查一下当前电量。", isStreaming = false, narration = true),
            tool(1, "查看设备状态 · 电池", "电量 100%（充电中）"),
            thinking(2, "Now storage."),
            AgentMessageUi("assistant-run-2-1", "电量查到了，接下来查存储。", isStreaming = false, narration = true),
            tool(2, "查看设备状态 · 存储", "可用约 201 GB"),
            thinking(3, "All three queried, summarise."),
        )
        val answer = "三次查询都完成了，分成了独立的调用：\n\n| 项目 | 结果 |\n|---|---|\n| 电量 | 100%，正在充电 |\n| 存储 | 可用约 201 GB |\n\n需要的话我可以再查内存。"
        run(
            "run-end",
            before = head + AgentMessageUi("assistant-run-3-1", answer, isStreaming = false, provisional = true),
            after = head + AgentMessageUi("assistant-run-3-1", answer, isStreaming = false),
            streamingAfter = false,
            // 真机顺序：RunFinished 先把最后一段移出卡片（仍在执行中），结果落定后才结束执行。
            streamingSwitchFrame = 8,
        )
    }
}
