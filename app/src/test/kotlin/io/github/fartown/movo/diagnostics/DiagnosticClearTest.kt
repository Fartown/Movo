package io.github.fartown.movo.diagnostics

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticClearTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun DiagnosticBuffer.add(run: String, event: String) =
        append(1_000L, 1L, DiagnosticLevel.INFO, "runtime", event, DiagnosticContext(run = run), "k=v")

    @Test
    fun bufferKeepsTheRunsStillInProgress() {
        val buffer = DiagnosticBuffer()
        buffer.add("R1", "run.started")
        buffer.add("", "screen.off")
        buffer.add("R2", "run.started")
        buffer.add("R1", "run.ended")

        buffer.retain { it.context.run in setOf("R2") }

        val snapshot = buffer.snapshot()
        assertEquals(listOf("R2"), snapshot.entries.map { it.context.run })
        assertEquals(snapshot.entries.sumOf { it.text().toByteArray().size }, snapshot.bytes)
    }

    @Test
    fun clearRewritesTheFileKeepingActiveRunsAndSystemEvents() {
        val file = temp.newFile("events.log")
        val store = DiagnosticStore(file)
        val buffer = DiagnosticBuffer()
        val entries = listOf(buffer.add("R1", "run.started"), buffer.add("", "screen.off"), buffer.add("R2", "run.started"))
        entries.forEach { entry -> store.append(entry) { buffer.snapshot().entries } }

        val done = CountDownLatch(1)
        store.clear(setOf("R2"), { buffer.snapshot().entries }) { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))

        val saved = file.readLines().mapNotNull(DiagnosticStore::decode)
        // 任务之外的系统事件保留（确认框只说删除已结束任务的记录）。
        assertEquals(listOf("", "R2"), saved.map { it.context.run })
    }
}
