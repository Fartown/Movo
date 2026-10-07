package io.github.fartown.movo.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.data.model.AppearanceSettings
import io.github.fartown.movo.ui.app.AgentAppTheme
import io.github.fartown.movo.ui.model.AgentChatMessageUi
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.SuggestionChipsMessageUi
import io.github.fartown.movo.ui.model.ThinkingMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.UserMessageUi
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 定稿 24 的渲染稳定性（逐帧，断言位置）：执行中只往下追加，已经出现的内容不动；一轮按真实顺序结束（回答写完 →
 * 跟到底 → 过程收成摘要条 → 推荐问题出现）全程不跳；收起时对话长则回答一像素不动，内容短则用户消息留在顶部、
 * 回答跟着往上走、不留空白；之后下一轮把它顶上去时不跳；载入历史直接是收起后的样子；点摘要条原地展开。
 * `-PmovoShots=true` 时另把每一帧写到 movo.shots.dir/turn-fold/ 便于肉眼复核。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "zh-rCN-w412dp-h917dp-xxhdpi")
class TurnFoldStabilityTest {
    @get:Rule val compose = createComposeRule()

    private val t = System.currentTimeMillis() - 60_000
    private val shots = System.getProperty("movo.shots") == "true"

    private fun user(id: String, text: String) = UserMessageUi(id, text, runStartedAtMillis = t, runFinishedAtMillis = t + 18_000)
    private fun thinking(id: String, text: String, seconds: Int, streaming: Boolean = false) =
        ThinkingMessageUi(id, text, isStreaming = streaming, elapsedSeconds = seconds, collapsed = !streaming)
    private fun tool(id: String, title: String, summary: String, done: Boolean = true) = ToolActivityMessageUi(
        id, "device_read", if (done) ToolActivityStatusUi.Success else ToolActivityStatusUi.Running, title,
        resultSummary = summary.takeIf { done }, startedAtMillis = t + 1_000, finishedAtMillis = (t + 1_300).takeIf { done },
    )
    // 与投影器一致：写的时候按纯文本显示，写完才按 Markdown 排版。
    private fun text(id: String, content: String, streaming: Boolean = false) =
        AgentMessageUi(id, content, isStreaming = streaming, renderMarkdown = !streaming)

    private val answer = "两项都查好了：电量 100%，正在充电；存储可用约 201 GB（共 256 GB）。\n\n需要的话，我可以再帮你看看哪些应用占用空间最多。"

    /** 一轮「查电量和存储、分两次查」执行到哪一步的消息（不含最终回答）。 */
    private fun turn(prefix: String, step: Int): List<AgentChatMessageUi> {
        val list = mutableListOf<AgentChatMessageUi>(user("$prefix-u", "查一下电量和存储空间，分两次查"))
        list += thinking("$prefix-run-thinking-1-0", "Two separate checks: battery first, then storage.", 2, streaming = step == 0)
        if (step >= 1) list += text("assistant-$prefix-run-1-1", "我先查一下当前电量。", streaming = step == 1)
        if (step >= 2) list += tool("$prefix-run-tool-1-a", "查看设备状态 · 电池", "电量 100%（充电中）", done = step > 2)
        if (step >= 4) list += text("assistant-$prefix-run-2-1", "电量是 100%，正在充电。接下来查存储。", streaming = step == 4)
        if (step >= 5) list += tool("$prefix-run-tool-2-a", "查看设备状态 · 存储", "可用约 201 GB，共 256 GB", done = step > 5)
        return list
    }

    private fun answerOf(prefix: String, content: String, streaming: Boolean) = text("assistant-$prefix-run-3-1", content, streaming)

    private lateinit var listState: LazyListState
    /** 列表是否停在底部跟随；测试里置 false 模拟用户往上滑开。 */
    private val anchored = mutableStateOf(true)

    private fun show(
        initial: List<AgentChatMessageUi>,
        streaming: Boolean,
        keepLatest: Boolean = true,
    ): (List<AgentChatMessageUi>, Boolean) -> Unit {
        var messages by mutableStateOf(initial)
        var isStreaming by mutableStateOf(streaming)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AgentAppTheme(appearance = AppearanceSettings(), applyInterfaceScale = false) {
                val state = rememberLazyListState().also { listState = it }
                AgentConversationMessages(
                    visibleMessages = messages,
                    scrollState = state,
                    bottomReserve = remember { ChatBottomReserve() },
                    isStreaming = isStreaming,
                    bottomInset = 120.dp,
                    keepBottomAnchored = anchored.value,
                    // 主界面与浮层都是「保持最新」（AgentAppRoot / 浮层 initiallyShowLatestMessage = true）。
                    keepLatestOnResize = keepLatest,
                    onBottomAnchorChanged = { anchored.value = it },
                    messageActionsEnabled = !isStreaming,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.mainClock.advanceTimeBy(3_000)
        return { next, nextStreaming -> messages = next; isStreaming = nextStreaming }
    }

    private fun bounds(text: String): Rect? = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.takeIf { it.height > 0f }

    private var frame = 0
    private val tracked = listOf("查一下电量和存储空间", "已思考", "正在", "我先查一下当前电量", "查看设备状态 · 电池", "电量是 100%", "已完成 2 个步骤", "两项都查好了", "看看哪些应用")
    private fun capture(name: String) {
        if (!shots) return
        val dir = File(System.getProperty("movo.shots.dir") ?: "build/shots", "turn-fold/$name").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "f%03d.png".format(frame)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val line = tracked.joinToString(" | ") { text -> "$text=" + (bounds(text)?.let { "%.0f-%.0f".format(it.top, it.bottom) } ?: "-") }
        File(dir, "bounds.txt").appendText("f%03d %s\n".format(frame, line))
        frame++
    }

    /** 跟底最快每帧约 36px（720dp/s，xxhdpi、16ms 一帧）；超过就是跳。 */
    private val maxFollowStepPx = 720f * 3f * 0.017f

    /** 执行中只往下追加：「我先查一下当前电量」出现后，它与用户消息的相对位置一直不变（列表跟底滚动不算）。 */
    @Test
    fun runningOnlyAppends() {
        val update = show(turn("a", 0), streaming = true)
        var reference: Float? = null
        for (step in 1..7) {
            update(if (step < 7) turn("a", step) else turn("a", 6) + answerOf("a", answer, streaming = true), true)
            repeat(20) {
                compose.mainClock.advanceTimeByFrame()
                capture("running")
                val first = bounds("我先查一下当前电量") ?: return@repeat
                val userTop = bounds("查一下电量和存储空间") ?: return@repeat
                val gap = first.top - userTop.top
                val ref = reference ?: gap.also { reference = it }
                assertTrue("第 $step 步：上面的内容动了 ${gap - ref}px", abs(gap - ref) <= 1f)
            }
        }
        assertNotNull(reference)
    }

    /**
     * 一轮按真实顺序结束：回答逐段写出 → 写完（换成 Markdown 排版、出操作行、列表跟到底）→ 上面的过程收成摘要条
     * → 推荐问题出现。收起之外的时间没有一帧跳动（只允许跟底的平滑滚动）。收起期间：上面还有内容可补时回答一像素
     * 不动；补到顶了回答只往上走（不回头），用户消息不先下后上，结束后上方不留空白（定稿 24-11 修订）。
     */
    private fun assertTurnEndsSmoothly(name: String, history: List<AgentChatMessageUi>, answerStays: Boolean) {
        val update = show(history + turn("b", 6), streaming = true)
        var written = ""
        for (part in answer.chunked(6)) {
            written += part
            update(history + turn("b", 6) + answerOf("b", written, streaming = true), true)
            repeat(4) { compose.mainClock.advanceTimeByFrame() }
        }
        compose.mainClock.advanceTimeBy(1_000)
        frame = 0
        val tops = mutableListOf<Float>()
        val userTops = mutableListOf<Float?>()
        val summaryShown = mutableListOf<Boolean>()
        fun record() {
            capture(name)
            tops += bounds("两项都查好了")!!.top
            userTops += bounds("查一下电量和存储空间")?.top
            summaryShown += bounds("已完成 2 个步骤") != null
        }
        record()
        val done = history + turn("b", 6) + answerOf("b", answer, streaming = false)
        update(done, false)
        repeat(60) { compose.mainClock.advanceTimeByFrame(); record() }
        update(done + SuggestionChipsMessageUi("b-chips", listOf("看看哪些应用占用空间最多", "设置电量低于 20% 时提醒我", "帮我清理一下不用的文件")), false)
        repeat(60) { compose.mainClock.advanceTimeByFrame(); record() }
        // 摘要条从 0 长高，头两三帧文字还没有高度：往前多算 3 帧当作收起开始（收起前列表已跟到底、静止）。
        val foldStart = summaryShown.indexOf(true) - 3
        assertTrue("没有收成摘要条", foldStart > 0)
        // 收起（`standard` 240ms ≈ 15 帧，从 foldStart 算起；之后回答最终排版晚到时照常平滑跟底，归下面的逐帧检查）。
        val foldEnd = minOf(foldStart + 16, tops.lastIndex)
        for (i in 1 until tops.size) {
            if (i in foldStart..foldEnd) continue
            assertTrue("第 $i 帧：回答一帧动了 ${tops[i] - tops[i - 1]}px", abs(tops[i] - tops[i - 1]) <= maxFollowStepPx)
        }
        for (i in foldStart..foldEnd) {
            if (answerStays) {
                assertTrue("收起第 ${i - foldStart} 帧：回答从 ${tops[foldStart - 1]} 动到 ${tops[i]}", abs(tops[i] - tops[foldStart - 1]) <= 1f)
            } else {
                assertTrue("收起第 ${i - foldStart} 帧：回答往回走了 ${tops[i] - tops[i - 1]}px", tops[i] <= tops[i - 1] + 1f)
                // 用户消息只会往下靠到顶部位置（先前滚出去的从上面露出来）然后停住，不会先下后上（真机 10-07 P6）。
                val user = userTops[i]
                val before = userTops[i - 1]
                if (user != null && before != null) {
                    assertTrue("收起第 ${i - foldStart} 帧：用户消息往回走了 ${user - before}px", user >= before - 1f)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        assertNotNull(bounds("已完成 2 个步骤"))
        assertEquals(null, bounds("我先查一下当前电量"))
        // 推荐问题整行滑到输入框上方，不停在半路（真机 10-07 第 2 轮 I2：跳一帧就停，下半截压在输入框后面）。
        val chips = bounds("帮我清理一下不用的文件")!!
        val visibleBottom = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.afterContentPadding
        assertTrue("推荐问题没滑出来：底边 ${chips.bottom}，可见区到 $visibleBottom", chips.bottom <= visibleBottom + 4f)
        if (!answerStays) {
            // 上方不留空白：用户消息回到列表顶部（顶部内边距 14dp + 气泡外边距，约 96px）。
            assertTrue("上方留了空白：用户消息在 ${bounds("查一下电量和存储空间")?.top}", bounds("查一下电量和存储空间")!!.top < 120f)
        }
    }

    /** 内容长（前面有几轮历史，列表已滚动）：回答一像素不动。 */
    @Test
    fun turnEndsSmoothlyInALongConversation() {
        // 前面几轮（文案与这一轮不同，免得按文字取位置时取到历史里的那条），让列表足够长、已经滚动。
        val earlier = (1..4).flatMap { n ->
            listOf(
                UserMessageUi("h$n-u", "第 $n 个问题：现在几点了", runStartedAtMillis = t, runFinishedAtMillis = t + 3_000),
                thinking("h$n-run-thinking-1-0", "Just tell the time.", 1),
                text("assistant-h$n-run-1-1", "现在是上午 10 点 $n 分。今天是星期三，天气晴，适合出门走走。"),
            )
        }
        assertTurnEndsSmoothly("long", earlier, answerStays = true)
    }

    /** 内容短（新对话第一轮、一屏放得下）：用户消息留在顶部，回答跟着收起往上走，不留空白（定稿 24-11 修订）。 */
    @Test
    fun turnEndsSmoothlyInAShortConversation() {
        assertTurnEndsSmoothly("short", emptyList(), answerStays = false)
    }

    /** 新对话第一轮，收起前内容超出一屏、收起后不够一屏（真机 10-07 场景 3 的情形）：先不动，到顶后往上走。 */
    @Test
    @Config(qualifiers = "zh-rCN-w412dp-h520dp-xxhdpi")
    fun turnEndsSmoothlyWhenTheFoldMakesTheListTooShortToScroll() {
        assertTurnEndsSmoothly("overflow", emptyList(), answerStays = false)
    }

    /**
     * 短对话收起后接着发下一轮：新回答越写越长、列表跟底把上一轮顶上去，没有一帧跳动
     * （真机 10-07 P2：当时顶部留白被一次清掉，整页一帧上跳约 690px）。
     */
    @Test
    fun nextTurnAfterAShortFoldScrollsWithoutAJump() {
        val first = turn("c", 6) + answerOf("c", answer, streaming = false)
        val update = show(turn("c", 6) + answerOf("c", answer, streaming = true), streaming = true)
        update(first, false)
        // 真机每帧都排版；测试里只推时钟不会排版，逐帧等排版完。
        repeat(90) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        assertTrue("收起后上方留了空白", bounds("查一下电量和存储空间")!!.top < 120f)
        frame = 0
        val tops = mutableListOf<Float>()
        val next = first + UserMessageUi("c2-u", "再说说怎么清理存储空间", runStartedAtMillis = t, runFinishedAtMillis = null)
        update(next, true)
        repeat(10) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        val long = (1..14).joinToString("\n\n") { "第 $it 条：清理微信、相册里的大文件和视频缓存，可以腾出不少空间。" }
        var written = ""
        for (part in long.chunked(10)) {
            written += part
            update(next + text("assistant-c2-run-1-1", written, streaming = true), true)
            repeat(2) {
                compose.mainClock.advanceTimeByFrame()
                capture("next-turn")
                bounds("两项都查好了")?.top?.let { tops += it }
            }
        }
        assertTrue("测试没把第一轮滚出屏幕", bounds("两项都查好了") == null)
        for (i in 1 until tops.size) {
            assertTrue("第 $i 帧：上一轮的回答一帧动了 ${tops[i] - tops[i - 1]}px", abs(tops[i] - tops[i - 1]) <= maxFollowStepPx)
        }
    }

    /**
     * 执行中往上滑开、停在本轮开头，任务这时结束（真机 10-07 第 2 轮 I1、第 3c 轮 N5）：回答压在输入框后面，用户看不到它。
     * 先不合并，用户正在看的内容一像素不动、不空白；回到底部再合并，之后不会再展开（第 3 轮 N1）。
     */
    @Test
    @Config(qualifiers = "zh-rCN-w412dp-h590dp-xxhdpi")
    fun scrolledAwayWhenTheTurnEndsNothingMovesUntilTheUserComesBack() {
        val update = show(turn("e", 6) + answerOf("e", answer, streaming = true), streaming = true)
        compose.mainClock.advanceTimeBy(1_000)
        anchored.value = false
        compose.runOnIdle { kotlinx.coroutines.runBlocking { listState.scrollToItem(0) } }
        repeat(5) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        val userTop = bounds("查一下电量和存储空间")!!.top
        frame = 0
        update(turn("e", 6) + answerOf("e", answer, streaming = false), false)
        repeat(60) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            capture("scrolled-away")
            val user = bounds("查一下电量和存储空间")
            assertNotNull("第 $it 帧：用户消息不见了（聊天区空白）", user)
            assertTrue("第 $it 帧：用户消息从 $userTop 被推到 ${user!!.top}", abs(user.top - userTop) <= 1f)
            assertNotNull("第 $it 帧：用户还在看，过程不该收起", bounds("我先查一下当前电量"))
        }
        // 回到底部（点 ↓）：这时才合并。
        anchored.value = true
        compose.runOnIdle { kotlinx.coroutines.runBlocking { listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1) } }
        repeat(90) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        assertNotNull("回到底部后应合并", bounds("已完成 2 个步骤"))
        assertEquals(null, bounds("我先查一下当前电量"))
        // 合并之后再滑开、再回来：不再展开。
        anchored.value = false
        repeat(5) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        anchored.value = true
        repeat(60) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertEquals("第 $it 帧：收起的过程又展开了", null, bounds("我先查一下当前电量"))
        }
    }

    /**
     * 执行到一半点停止（真机 10-07 用户截图：停止后 24 步全摊在页面上、中途的话下面出了操作行）：
     * 同样合并成摘要条「已停止·已执行 N 步」，只留最后说的那句话和「已停止」；内容短时用户消息不动、那句话跟着往上走、不回头。
     */
    @Test
    fun aStoppedTurnFoldsToo() {
        val running = turn("g", 6).map { if (it is ToolActivityMessageUi && it.id == "g-run-tool-2-a") it.copy(status = ToolActivityStatusUi.Running, finishedAtMillis = null, resultSummary = null) else it }
        val update = show(running, streaming = true)
        compose.mainClock.advanceTimeBy(1_000)
        val stopped = turn("g", 6) + io.github.fartown.movo.ui.model.SystemNoticeMessageUi("assistant-g-run-1", io.github.fartown.movo.ui.model.SystemNoticeCode.Stopped, null)
        frame = 0
        update(stopped, false)
        val tops = mutableListOf<Float>()
        val userTop = bounds("查一下电量和存储空间")!!.top
        repeat(60) {
            compose.mainClock.advanceTimeByFrame()
            capture("stopped")
            tops += bounds("电量是 100%")!!.top
            // 内容短：用户消息留在顶部，留下的那句话跟着合并往上走（定稿 24-11b），不回头。
            assertTrue("第 $it 帧：用户消息动了", abs(bounds("查一下电量和存储空间")!!.top - userTop) <= 1f)
        }
        for (i in 1 until tops.size) {
            assertTrue("第 $i 帧：最后那句话往回走了 ${tops[i] - tops[i - 1]}px", tops[i] <= tops[i - 1] + 1f)
        }
        compose.mainClock.advanceTimeBy(1_000)
        assertNotNull("应合并成「已停止」摘要条", bounds("已停止·已执行 2 步"))
        assertEquals(null, bounds("我先查一下当前电量"))
        assertEquals(null, bounds("查看设备状态 · 电池"))
        assertNotNull(bounds("电量是 100%"))
    }

    /**
     * 推荐问题正好在合并（那 240ms 不跟底）期间到达：合并完照样滑上来、整行露在输入框上方
     * （原来这次跟底被丢掉，推荐问题一直压在输入框后面；本地全量偶发失败查到的）。
     */
    @Test
    fun suggestionsArrivingDuringTheFoldStillSlideIn() {
        val earlier = (1..4).flatMap { n ->
            listOf(
                UserMessageUi("h$n-u", "第 $n 个问题：现在几点了", runStartedAtMillis = t, runFinishedAtMillis = t + 3_000),
                thinking("h$n-run-thinking-1-0", "Just tell the time.", 1),
                text("assistant-h$n-run-1-1", "现在是上午 10 点 $n 分。今天是星期三，天气晴，适合出门走走。"),
            )
        }
        val update = show(earlier + turn("k", 6) + answerOf("k", answer, streaming = true), streaming = true)
        compose.mainClock.advanceTimeBy(1_000)
        val done = earlier + turn("k", 6) + answerOf("k", answer, streaming = false)
        update(done, false)
        // 等到合并开始（摘要条出现）的那一帧再送推荐问题。
        var waited = 0
        while (bounds("已完成 2 个步骤") == null && waited < 120) { compose.mainClock.advanceTimeByFrame(); waited++ }
        assertTrue("没有开始合并", waited < 120)
        update(done + SuggestionChipsMessageUi("k-chips", listOf("看看哪些应用占用空间最多", "设置电量低于 20% 时提醒我", "帮我清理一下不用的文件")), false)
        repeat(90) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        val chips = bounds("帮我清理一下不用的文件")!!
        val visibleBottom = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.afterContentPadding
        assertTrue("推荐问题没滑出来：底边 ${chips.bottom}，可见区到 $visibleBottom", chips.bottom <= visibleBottom + 4f)
    }

    /**
     * 长对话里执行到一半点停止（真机 10-07 第 4 轮 N6）：留下的那句话下面还有一段卡要收。列表停在底部时，下面变短
     * 原来只能整体往下补，那句话先往下掉 130～264px 再往上收。现在那句话一像素不动，「已停止」往上靠。
     */
    @Test
    fun aStoppedTurnInALongConversationKeepsItsLastTextStill() {
        val earlier = (1..4).flatMap { n ->
            listOf(
                UserMessageUi("h$n-u", "第 $n 个问题：现在几点了", runStartedAtMillis = t, runFinishedAtMillis = t + 3_000),
                thinking("h$n-run-thinking-1-0", "Just tell the time.", 1),
                text("assistant-h$n-run-1-1", "现在是上午 10 点 $n 分。今天是星期三，天气晴，适合出门走走。"),
            )
        }
        val running = earlier + turn("m", 6).map { if (it is ToolActivityMessageUi && it.id == "m-run-tool-2-a") it.copy(status = ToolActivityStatusUi.Running, finishedAtMillis = null, resultSummary = null) else it }
        val update = show(running, streaming = true)
        compose.mainClock.advanceTimeBy(1_000)
        val stopped = earlier + turn("m", 6) + io.github.fartown.movo.ui.model.SystemNoticeMessageUi("assistant-m-run-1", io.github.fartown.movo.ui.model.SystemNoticeCode.Stopped, null)
        frame = 0
        update(stopped, false)
        val tops = mutableListOf<Float>()
        val folding = mutableListOf<Boolean>()
        repeat(90) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            capture("stopped-long")
            tops += bounds("电量是 100%")!!.top
            // 收起开始：上面那段卡（电池那一步）开始变矮或不见了。
            folding += bounds("查看设备状态 · 电池").let { it == null || it.height < 50f }
        }
        // 停下后先照「回答完成」收尾：「已停止」和操作行出来，列表平滑跟底（不跳）。
        for (i in 1 until tops.size) {
            assertTrue("第 $i 帧：一帧动了 ${tops[i] - tops[i - 1]}px", abs(tops[i] - tops[i - 1]) <= maxFollowStepPx)
        }
        // 收起期间：留下的那句话一像素不动（原来先往下掉再往上收）。
        val foldStart = folding.indexOf(true) - 2
        assertTrue("没有收起", foldStart > 0)
        for (i in foldStart until tops.size) {
            assertTrue("收起第 ${i - foldStart} 帧：最后那句话从 ${tops[foldStart]} 动到 ${tops[i]}", abs(tops[i] - tops[foldStart]) <= 1f)
        }
        assertNotNull(bounds("已停止·已执行 2 步"))
        assertEquals(null, bounds("查看设备状态 · 存储"))
    }

    /** 载入历史：直接是收起后的样子；点摘要条原地展开，中间说过的话都在。 */
    @Test
    fun historyShowsFoldedAndTheSummaryExpandsInPlace() {
        show(turn("d", 6) + answerOf("d", answer, streaming = false), streaming = false)
        assertNotNull(bounds("已完成 2 个步骤"))
        assertEquals(null, bounds("我先查一下当前电量"))
        compose.onAllNodes(hasText("已完成 2 个步骤", substring = true), useUnmergedTree = true)[0].performClick()
        compose.mainClock.advanceTimeBy(1_000)
        val narration = bounds("我先查一下当前电量")
        val second = bounds("电量是 100%")
        val reply = bounds("两项都查好了")
        assertNotNull(narration)
        assertNotNull(second)
        assertTrue(narration!!.top < second!!.top && second.top < reply!!.top)
    }
}
