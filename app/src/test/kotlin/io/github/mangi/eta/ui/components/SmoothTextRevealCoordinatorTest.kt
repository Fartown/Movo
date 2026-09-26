package io.github.mangi.eta.ui.components

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmoothTextRevealCoordinatorTest {
    private val textMeasurer by lazy {
        TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(RuntimeEnvironment.getApplication()),
            defaultDensity = Density(1f),
            defaultLayoutDirection = LayoutDirection.Ltr,
        )
    }

    @Test
    fun detachedEarlierBlockCompletesAndLaterBlockKeepsRevealing() = runBlocking {
        val coordinator = SmoothTextRevealCoordinator()
        val earlierKey = RevealBlockKey(0)
        val laterKey = RevealBlockKey(100)
        val earlierNode = attach(coordinator, earlierKey, "早先的思考正文")
        attach(coordinator, laterKey, "随后到达的工具输出内容。")

        coordinator.detach(earlierKey, earlierNode)

        assertEquals(7f, coordinator.drawSnapshot(earlierKey)!!.progress, 0f)
        assertTrue(earlierKey in coordinator.started.value)
        assertFalse(coordinator.drained.value)
        val clock = TestFrameClock()
        val frameJob = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            coordinator.runFrameClock()
        }
        try {
            clock.send(0L)
            clock.send(16_000_000L)
            yield()

            assertEquals(12f, coordinator.drawSnapshot(laterKey)!!.progress, 0f)
        } finally {
            frameJob.cancelAndJoin()
        }
    }

    @Test
    fun blocksCommitWholeSentencesAndAnimateEachChunk() {
        // 规范 9.4 / Q2：按句追加，每块淡入 + 模糊 160ms；没有句末的尾巴等 300ms 或下一块出现。
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        val state = SmoothTextRevealState(key, coordinator)
        state.attach(SmoothTextRevealNode(state))
        val first = "第一句。第二句还没"
        state.onTextLayout(first, layout(first))

        coordinator.advanceFrame(0L)
        val snapshot = coordinator.drawSnapshot(key)!!
        assertEquals(4f, snapshot.progress, 0f)
        assertEquals(1, snapshot.chunks.size)
        assertEquals(0, snapshot.chunks[0].from)
        assertEquals(4, snapshot.chunks[0].to)
        assertTrue(key in coordinator.started.value)

        coordinator.advanceFrame(80_000_000L)
        assertEquals(0.5f, snapshot.chunks[0].fraction, 0.01f)
        coordinator.advanceFrame(170_000_000L)
        assertTrue(snapshot.chunks.isEmpty())
        assertEquals(4f, snapshot.progress, 0f)

        // 尾巴 300ms 没有新字后整块提交。
        coordinator.advanceFrame(310_000_000L)
        assertEquals(9f, snapshot.progress, 0f)
        assertEquals(4, snapshot.chunks.single().from)
    }

    @Test
    fun laterBlockOrStreamEndFlushesTheBufferedTail() {
        val coordinator = SmoothTextRevealCoordinator()
        val firstKey = RevealBlockKey(0)
        val secondKey = RevealBlockKey(50)
        attach(coordinator, firstKey, "列表第一项没有句号")
        coordinator.advanceFrame(0L)
        assertEquals(0f, coordinator.drawSnapshot(firstKey)!!.progress, 0f)

        attach(coordinator, secondKey, "第二项")
        coordinator.advanceFrame(16_000_000L)
        assertEquals(9f, coordinator.drawSnapshot(firstKey)!!.progress, 0f)
        assertEquals(0f, coordinator.drawSnapshot(secondKey)!!.progress, 0f)

        coordinator.setStreaming(false)
        coordinator.advanceFrame(32_000_000L)
        assertEquals(3f, coordinator.drawSnapshot(secondKey)!!.progress, 0f)
        coordinator.advanceFrame(400_000_000L)
        assertTrue(coordinator.drawSnapshot(secondKey)!!.chunks.isEmpty())
        assertTrue(coordinator.drawSnapshot(firstKey)!!.chunks.isEmpty())
    }

    @Test
    fun reattachedBlockKeepsCompletedPrefixAndAnimatesOnlyNewText() {
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        val oldNode = attach(coordinator, key, "已有文字")

        coordinator.detach(key, oldNode)
        assertTrue(coordinator.drained.value)
        attach(coordinator, key, "已有文字和新增文字")

        val snapshot = coordinator.drawSnapshot(key)!!
        assertEquals(4f, snapshot.progress, 0f)
        assertEquals(9, snapshot.boundaries.lastIndex)
        assertFalse(coordinator.drained.value)
    }

    @Test
    fun layoutWithoutMountedNodeIsImmediatelyReadableOnLaterAttach() {
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        val text = "尚未挂载时收到的历史内容"
        val state = SmoothTextRevealState(key, coordinator)
        state.onTextLayout(text, layout(text))

        assertTrue(coordinator.drained.value)
        assertEquals(text.length.toFloat(), coordinator.drawSnapshot(key)!!.progress, 0f)
        state.attach(SmoothTextRevealNode(state))

        assertTrue(coordinator.drained.value)
        assertEquals(text.length.toFloat(), coordinator.drawSnapshot(key)!!.progress, 0f)
    }

    @Test
    fun staleDetachDoesNotCompleteTheReplacementNode() {
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        val oldNode = attach(coordinator, key, "原始内容")
        val replacementNode = attach(coordinator, key, "替换后的完整内容")

        coordinator.detach(key, oldNode)

        assertEquals(0f, coordinator.drawSnapshot(key)!!.progress, 0f)
        assertFalse(coordinator.drained.value)
        coordinator.detach(key, replacementNode)
        assertTrue(coordinator.drained.value)
    }

    private fun attach(
        coordinator: SmoothTextRevealCoordinator,
        key: RevealBlockKey,
        text: String,
    ): SmoothTextRevealNode {
        val state = SmoothTextRevealState(key, coordinator)
        val node = SmoothTextRevealNode(state)
        state.attach(node)
        state.onTextLayout(text, layout(text))
        return node
    }

    private fun layout(text: String): TextLayoutResult = textMeasurer.measure(
        text = text,
        style = TextStyle(fontSize = 16.sp),
        constraints = Constraints(maxWidth = 320),
    )

    private class TestFrameClock : MonotonicFrameClock {
        private val frames = Channel<Long>(Channel.UNLIMITED)

        fun send(timeNanos: Long) {
            check(frames.trySend(timeNanos).isSuccess)
        }

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(frames.receive())
    }
}
