package io.github.fartown.movo.agent.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 移植 Claude Monitor 实测用例（docs/research/agent-monitor.md 第 1 节）。 */
class MonitorEventPipelineTest {

    @Test
    fun framerJoinsChunksAndKeepsTailUntilDrain() {
        val framer = MonitorLineFramer()
        assertEquals(emptyList<String>(), framer.accept("PRE"))
        assertEquals(listOf("PREFIX", "A", "B"), framer.accept("FIX\nA\nB\n"))
        assertEquals(emptyList<String>(), framer.accept("TAIL"))
        assertEquals("TAIL", framer.drain())
        assertNull(framer.drain())
    }

    @Test
    fun framerTrimsAndDropsBlankLinesIncludingCrLf() {
        val framer = MonitorLineFramer()
        assertEquals(listOf("one", "two"), framer.accept("  one \r\n\n   \r\ntwo\n"))
    }

    @Test
    fun lineAndBatchTruncationCountUtf16Units() {
        val framer = MonitorLineFramer()
        val accented = "é".repeat(500)
        assertEquals(listOf(accented), framer.accept(accented + "\n"))
        val emoji = "😀".repeat(251) // 502 UTF-16 units
        val truncated = framer.accept(emoji + "\n").single()
        assertEquals(500 + MonitorLimits.LINE_TRUNCATED.length, truncated.length)
        assertTrue(truncated.endsWith(MonitorLimits.LINE_TRUNCATED))

        val batcher = MonitorBatcher()
        repeat(8) { batcher.add("x".repeat(500), now = 0) }
        val batch = batcher.flush(now = 200)!!
        assertEquals(3000 + MonitorLimits.BATCH_TRUNCATED.length, batch.length)
    }

    @Test
    fun batchWindowIsFixedNotDebounced() {
        val batcher = MonitorBatcher()
        assertEquals(200L, batcher.add("A", now = 0))
        assertEquals(200L, batcher.add("B", now = 100))
        assertNull(batcher.flush(now = 199))
        assertEquals("A\nB", batcher.flush(now = 200))
        assertTrue(batcher.isEmpty())
        assertEquals(500L, batcher.add("C", now = 300))
    }

    @Test
    fun tenBatchesPassEleventhIsSuppressedThenReportedAfterRefill() {
        val limiter = MonitorRateLimiter()
        repeat(10) { assertEquals(MonitorRateLimiter.Decision.Deliver(0), limiter.tryDeliver(0)) }
        assertEquals(MonitorRateLimiter.Decision.Suppress, limiter.tryDeliver(0))
        assertEquals(MonitorRateLimiter.Decision.Suppress, limiter.tryDeliver(1_999))
        assertEquals(MonitorRateLimiter.Decision.Deliver(2), limiter.tryDeliver(2_000))
    }

    @Test
    fun sustainedFloodStopsAfterThirtySeconds() {
        val limiter = MonitorRateLimiter()
        var now = 0L
        var stop: MonitorRateLimiter.Decision.Stop? = null
        // 每 200ms 一批（约每 50ms 一行合批后的节奏），持续超速。
        while (now <= 60_000 && stop == null) {
            val decision = limiter.tryDeliver(now)
            if (decision is MonitorRateLimiter.Decision.Stop) stop = decision
            now += 200
        }
        val stopped = requireNotNull(stop)
        assertTrue("stopped at ${now - 200}", now - 200 in 30_000..33_000)
        assertTrue(stopped.spanMs > 30_000)
    }

    @Test
    fun quietPeriodResetsFloodWindow() {
        val limiter = MonitorRateLimiter()
        repeat(10) { limiter.tryDeliver(0) }
        assertEquals(MonitorRateLimiter.Decision.Suppress, limiter.tryDeliver(0))
        assertEquals(MonitorRateLimiter.Decision.Deliver(1), limiter.tryDeliver(2_000))
        // 静默到 40s，令牌补满；先交付一批（距最近抑制已远超 6s，抑制起点重置）。
        assertEquals(MonitorRateLimiter.Decision.Deliver(0), limiter.tryDeliver(40_000))
        repeat(9) { limiter.tryDeliver(40_000) }
        // 再超速：重新开始计时，不会因 0s 时的旧起点被立刻停掉。
        assertEquals(MonitorRateLimiter.Decision.Suppress, limiter.tryDeliver(40_000))
    }
}
