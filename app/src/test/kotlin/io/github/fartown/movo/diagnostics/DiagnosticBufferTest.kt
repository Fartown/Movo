package io.github.fartown.movo.diagnostics

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DiagnosticBufferTest {
    private fun DiagnosticBuffer.add(text: String) = append(1, 2, DiagnosticLevel.INFO, "test", "entry", DiagnosticContext("R1", "Q1"), text)

    @Test fun fifoEvictsOldestAndClearNeverReusesSequence() {
        val buffer = DiagnosticBuffer(maxEntries = 3)
        repeat(5) { buffer.add("entry $it") }
        val snapshot = buffer.snapshot()
        assertEquals(listOf("entry 2", "entry 3", "entry 4"), snapshot.entries.map { it.details })
        assertEquals(2L, snapshot.dropped)
        val last = snapshot.entries.last().sequence
        buffer.clear()
        assertEquals(0, buffer.snapshot().bytes)
        assertEquals(0L, buffer.snapshot().dropped)
        assertTrue(buffer.snapshot().entries.isEmpty())
        buffer.add("new")
        assertTrue(buffer.snapshot().entries.single().sequence > last)
    }

    @Test fun byteCapTruncatesUnicodeWithoutSplittingSurrogatesAndEvictsBySize() {
        val buffer = DiagnosticBuffer(maxEntries = 20, maxBytes = 1_024, maxEntryBytes = 700)
        repeat(5) { buffer.add("中文🙂".repeat(500)) }
        val snapshot = buffer.snapshot()
        assertTrue(snapshot.bytes <= 1_024)
        assertTrue(snapshot.dropped > 0)
        assertEquals(snapshot.bytes, snapshot.entries.sumOf { it.text().toByteArray(Charsets.UTF_8).size })
        snapshot.entries.forEach {
            assertTrue(it.text().toByteArray(Charsets.UTF_8).size <= 700)
            assertEquals(it.details, String(it.details.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
            assertFalse(it.details.lastOrNull()?.isHighSurrogate() == true)
        }
    }

    @Test fun concurrentWritersAndSnapshotsStayBoundedAndOrdered() {
        val buffer = DiagnosticBuffer(maxEntries = 100, maxBytes = 12_000)
        val pool = Executors.newFixedThreadPool(6)
        val futures = (0..5).map { writer -> pool.submit {
            repeat(400) {
                buffer.add("writer=$writer item=$it")
                val snapshot = buffer.snapshot()
                assertTrue(snapshot.entries.size <= 100)
                assertTrue(snapshot.bytes <= 12_000)
                assertEquals(snapshot.entries.map { entry -> entry.sequence }.distinct().sorted(), snapshot.entries.map { entry -> entry.sequence })
            }
        } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        futures.forEach { it.get() }
        assertEquals(2_400L, buffer.snapshot().entries.size + buffer.snapshot().dropped)
    }

    @Test fun throwableMetadataNeverRetainsMessageOrStack() {
        val failure = IllegalStateException("secret outer", java.net.SocketTimeoutException("secret host/key"))
        assertEquals("IllegalStateException > SocketTimeoutException", MemoryDiagnostics.causes(failure))
        assertEquals("unknown", MemoryDiagnostics.token("request-id\nAuthorization: secret"))
    }

    @Test fun requestAndRunSearchMatchWholeIdsInsteadOfMixingQ1WithQ10() {
        val entry = DiagnosticEntry(1, 0, 0, DiagnosticLevel.ERROR, "model", "attempt.failed", DiagnosticContext("R1", "Q10"), "code=HTTP_503")
        assertFalse(entry.matches("Q1", false))
        assertTrue(entry.matches("r1 q10 http_503", true))
        assertFalse(entry.copy(level = DiagnosticLevel.INFO).matches("Q10", true))
        assertTrue(entry.matches("", false))
    }
}
