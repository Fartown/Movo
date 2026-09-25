package io.github.mangi.eta.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticTraceTest {
    private val buffer = DiagnosticBuffer()
    private var clock = 1_000L

    private fun add(
        event: String,
        run: String = "",
        request: String = "",
        level: DiagnosticLevel = DiagnosticLevel.INFO,
        after: Long = 10,
        vararg fields: Pair<String, Any?>,
    ) {
        clock += after
        buffer.append(
            timeMillis = WALL + clock,
            elapsedMillis = clock,
            level = level,
            category = "test",
            event = event,
            context = DiagnosticContext(run, request),
            details = fields.joinToString("\n") { (key, value) -> "$key=$value" },
        )
    }

    private fun attempt(request: String, round: Int, attempt: Int, run: String = "R1") = add(
        "attempt.started", run = run, request = request,
        fields = arrayOf("purpose" to "CHAT", "provider" to "openai_responses", "round" to round, "attempt" to attempt),
    )

    @Test
    fun successfulRunBreaksTimeDownAndOrdersTimeline() {
        add("process.started", fields = arrayOf("network" to "wifi"))
        add("run.started", run = "R1")
        add("run.bound", run = "R1", fields = arrayOf("wire_run" to "run-abc", "conversation" to "c1"))
        attempt("Q1", round = 1, attempt = 1)
        add("http.connecting", run = "R1", request = "Q1", after = 20)
        add("http.sending_headers", run = "R1", request = "Q1", after = 80)
        add("http.request_sent", run = "R1", request = "Q1", after = 20)
        add("http.response_headers", run = "R1", request = "Q1", after = 380, fields = arrayOf("http_status" to 200, "server_request_id" to "req_1"))
        add("sse.first_event", run = "R1", request = "Q1", after = 1_000)
        add("attempt.completed", run = "R1", request = "Q1", after = 2_200, fields = arrayOf("received_bytes" to 5120))
        add("tool.started", run = "R1", fields = arrayOf("round" to 1, "tool" to "get_current_context"))
        add("tool.finished", run = "R1", after = 400, fields = arrayOf("round" to 1, "tool" to "get_current_context", "success" to true))
        add("run.completed", run = "R1")
        add("run.ended", run = "R1", fields = arrayOf("duration_ms" to 4_200))

        val run = DiagnosticTraceBuilder.build(buffer.snapshot().entries).runs.single()

        assertEquals(TraceStatus.SUCCEEDED, run.status)
        assertEquals("run-abc", run.wireRunId)
        assertEquals("c1", run.conversationId)
        val request = run.requests.single()
        assertEquals(1_500L, request.firstDataMs)
        assertEquals(3_700L, request.durationMs)
        assertEquals(200, request.httpStatus)
        assertEquals("req_1", request.serverRequestId)
        assertEquals(listOf("建立连接", "请求已发送", "响应头", "首字", "完成"), request.phases.map { it.label })
        val breakdown = run.breakdown()!!
        assertEquals(1_500L, breakdown.waitingMs)
        assertEquals(2_200L, breakdown.receivingMs)
        assertEquals(400L, breakdown.toolMs)
        assertEquals(4_200L, breakdown.waitingMs + breakdown.receivingMs + breakdown.toolMs + breakdown.retryWaitMs +
            breakdown.compactionMs + breakdown.otherMs)
        assertTrue(run.timeline[0] is TimelineItem.Request)
        assertTrue(run.timeline[1] is TimelineItem.Tool)
        assertNull(run.failure)
    }

    @Test
    fun failedRunKeepsRetryChainAndDeviceEventsInTimeline() {
        add("process.started", fields = arrayOf("network" to "wifi"))
        add("run.started", run = "R1")
        attempt("Q1", round = 1, attempt = 1)
        add("attempt.failed", run = "R1", request = "Q1", level = DiagnosticLevel.ERROR, after = 600,
            fields = arrayOf("code" to "HTTP_429", "http_status" to 429, "provider_error_code" to "rate_limit_exceeded"))
        add("retry.scheduled", run = "R1", request = "Q1", fields = arrayOf("delay_ms" to 2_000))
        add("network.changed", after = 500, fields = arrayOf("network" to "cellular"))
        add("network.changed", after = 10, fields = arrayOf("network" to "cellular"))
        clock += 1_500
        attempt("Q2", round = 2, attempt = 2)
        add("attempt.failed", run = "R1", request = "Q2", level = DiagnosticLevel.ERROR, after = 800,
            fields = arrayOf("code" to "HTTP_429", "http_status" to 429))
        add("run.failed", run = "R1", level = DiagnosticLevel.ERROR, fields = arrayOf("causes" to "AgentModelExecutionException"))
        add("run.ended", run = "R1", fields = arrayOf("duration_ms" to 3_500))

        val trace = DiagnosticTraceBuilder.build(buffer.snapshot().entries)
        val run = trace.runs.single()

        assertEquals(TraceStatus.FAILED, run.status)
        assertEquals("HTTP_429", run.failure?.code)
        assertEquals(429, run.failure?.httpStatus)
        assertEquals(2, run.failure?.failedAttempts)
        assertEquals(2_000L, run.requests.first().retryDelayMs)
        assertTrue(run.requests.last().isRetry)
        // 网络只在类型真的变化时算一次，并出现在任务时间线里。
        val system = run.timeline.filterIsInstance<TimelineItem.System>().map { it.event }
        assertEquals(listOf("网络切换"), system.map { it.title })
        assertEquals("Wi‑Fi → 移动数据", system.single().detail)
        assertEquals(listOf("网络切换", "进程启动"), trace.system.map { it.title })
        val breakdown = run.breakdown()!!
        assertEquals(1_400L, breakdown.waitingMs)
        assertEquals(2_030L, breakdown.retryWaitMs)
    }

    @Test
    fun runningRunReportsCurrentStep() {
        add("run.started", run = "R1")
        attempt("Q1", round = 1, attempt = 1)
        add("http.response_headers", run = "R1", request = "Q1", after = 300, fields = arrayOf("http_status" to 200))

        val run = DiagnosticTraceBuilder.build(buffer.snapshot().entries).runs.single()

        assertEquals(TraceStatus.RUNNING, run.status)
        assertNull(run.breakdown())
        val step = run.currentStep()
        assertTrue(step is CurrentStep.WaitingModel)
        assertEquals(run.requests.single().startElapsed, step.sinceElapsed)
    }

    @Test
    fun retentionKeepsNewestRunsAndClosesUnfinishedOnes() {
        add("run.started", run = "R1")
        add("run.ended", run = "R1")
        add("run.started", run = "R2")
        attempt("Q1", round = 1, attempt = 1, run = "R2")
        add("network.lost", fields = arrayOf("network" to "none"))
        val saved = DiagnosticRetention.closeInterrupted(buffer.snapshot().entries)

        val kept = DiagnosticRetention.retain(saved, now = WALL + clock, maxRuns = 1, looseRetentionMs = 60_000)
        assertTrue(kept.none { it.context.run == "R1" })
        assertTrue(kept.any { it.event == "network.lost" })
        val restored = DiagnosticBuffer().apply { restore(kept) }
        val run = DiagnosticTraceBuilder.build(restored.snapshot().entries).runs.single()
        assertEquals("R2", run.id)
        assertEquals(TraceStatus.INTERRUPTED, run.status)
        assertEquals(2L, DiagnosticRetention.lastNumber(kept, 'R') { it.context.run })
        assertEquals(1L, DiagnosticRetention.lastNumber(kept, 'Q') { it.context.request })
        assertTrue(DiagnosticRetention.retain(saved, now = WALL + clock + 120_000, maxRuns = 1, looseRetentionMs = 60_000)
            .none { it.event == "network.lost" })
    }

    @Test
    fun storeLineRoundTripsTabsAndNewlines() {
        add("attempt.failed", run = "R3", request = "Q9", level = DiagnosticLevel.ERROR,
            fields = arrayOf("code" to "HTTP_500", "causes" to "IOException\tTimeout\\x"))
        val entry = buffer.snapshot().entries.single()

        val decoded = DiagnosticStore.decode(DiagnosticStore.encode(entry))!!

        assertEquals(entry.copy(sequence = 0), decoded)
        assertNull(DiagnosticStore.decode("broken"))
    }

    private companion object {
        const val WALL = 1_790_000_000_000L
    }
}
