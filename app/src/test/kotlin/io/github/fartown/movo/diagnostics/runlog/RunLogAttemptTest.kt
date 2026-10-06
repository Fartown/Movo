package io.github.fartown.movo.diagnostics.runlog

import io.github.fartown.movo.agent.model.AgentModelFailure
import io.github.fartown.movo.agent.model.AssistantBlockKind
import io.github.fartown.movo.agent.model.ProviderEvent
import io.github.fartown.movo.agent.model.ProviderRawPart
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunLogAttemptTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var elapsed = 0L
    private val store by lazy { RunLogStore(File(temp.root, "run-log"), elapsedClock = { elapsed }) }

    @After
    fun stop() = store.shutdown()

    private fun lines(session: RunLogSession): List<Map<String, Any?>> {
        assertTrue(store.awaitIdle())
        @Suppress("UNCHECKED_CAST")
        return File(store.root, "${session.dirName}/${RunLogStore.LOG_FILE}").readLines()
            .map { RunLogJson.parse(it) as Map<String, Any?> }
    }

    private fun thinking(text: String, type: String) =
        ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, text, listOf(ProviderRawPart(type, text)))

    @Test
    fun outputIsWrittenInBatchesByTimeAndSize() {
        val session = store.open("R1")!!
        val attempt = RunLogAttempt(session, "Q1")
        attempt.onProviderEvent(ProviderEvent.RequestStarted)
        elapsed = 300
        attempt.onProviderEvent(ProviderEvent.ResponseHeaders(200))
        attempt.onProviderEvent(thinking("想", "response.reasoning_text.delta"))
        attempt.onProviderEvent(thinking("一想", "response.reasoning_text.delta"))
        attempt.onProviderEvent(thinking("摘要", "response.reasoning_summary_text.delta"))
        elapsed = 2_400 // 距上次落盘超过 2 秒：下一片到达时写一批
        attempt.onProviderEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 1, "答"))
        attempt.onProviderEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 1, "x".repeat(16 * 1024))) // 满 16K 字
        attempt.onProviderEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 1, "尾"))
        elapsed = 2_500
        attempt.succeeded(JSONObject().put("content", "答…尾").put("finish_reason", "stop"))
        attempt.failed(AgentModelFailure("LATE", false, "不应再写"), cancelled = false)

        val lines = lines(session)
        assertEquals(listOf("attempt_delta", "attempt_delta", "attempt_end"), lines.map { it["t"] })
        assertEquals(
            listOf(
                mapOf("type" to "response.reasoning_text.delta", "text" to "想一想"),
                mapOf("type" to "response.reasoning_summary_text.delta", "text" to "摘要"),
            ),
            lines[0]["thinking"],
        )
        assertEquals("答", lines[0]["text"])
        assertEquals("x".repeat(16 * 1024), lines[1]["text"])
        val end = lines[2]
        assertEquals("ok", end["status"])
        assertEquals("尾", end["text"])
        assertEquals(JsonNumber("300"), end["recv_start_el"])
        assertEquals(JsonNumber("2500"), end["recv_end_el"])
    }

    @Test
    fun failureAndCancellationEndTheAttemptOnce() {
        val session = store.open("R2")!!
        val failed = RunLogAttempt(session, "Q2")
        failed.onProviderEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "半句"))
        failed.failed(AgentModelFailure("MODEL_TIMEOUT", true, "超时"), cancelled = false)
        failed.failed(AgentModelFailure("MODEL_TIMEOUT", true, "超时"), cancelled = false)
        val cancelled = RunLogAttempt(session, "Q3")
        cancelled.failed(InterruptedException(), cancelled = true)

        val ends = lines(session).filter { it["t"] == "attempt_end" }
        assertEquals(2, ends.size)
        assertEquals(listOf("failed", "MODEL_TIMEOUT", "半句"), listOf(ends[0]["status"], ends[0]["code"], ends[0]["text"]))
        assertEquals("AgentModelFailure", ends[0]["exception"])
        assertEquals("cancelled", ends[1]["status"])
        assertEquals("InterruptedException", ends[1]["exception"])
    }
}
