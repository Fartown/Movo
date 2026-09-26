package io.github.fartown.movo.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunProgressTest {
    private var now = 0L
    private val originalClock = MemoryDiagnostics.elapsedClock

    @After
    fun restoreClock() {
        MemoryDiagnostics.elapsedClock = originalClock
    }

    @Test
    fun silenceSpansRetriesButPausesDuringTools() {
        MemoryDiagnostics.elapsedClock = { now }
        val run = DiagnosticContext(run = "R900")
        MemoryDiagnostics.record("runtime", "run.started", context = run)
        now = 20_000
        // 失败的请求和重试都不算进展，静默时间继续累计。
        MemoryDiagnostics.record("model", "attempt.failed", context = run.copy(request = "Q900"))
        now = 35_000
        assertEquals(35_000L, MemoryDiagnostics.silenceMs("R900"))

        MemoryDiagnostics.record("runtime", "tool.started", context = run)
        now = 80_000
        assertNull(MemoryDiagnostics.silenceMs("R900"))
        MemoryDiagnostics.record("runtime", "tool.finished", context = run)
        now = 81_000
        assertEquals(1_000L, MemoryDiagnostics.silenceMs("R900"))

        MemoryDiagnostics.record("runtime", "run.ended", context = run)
        assertNull(MemoryDiagnostics.silenceMs("R900"))
    }
}
