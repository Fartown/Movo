package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.diagnostics.MemoryDiagnostics
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ResponsesOutputValidationTest {
    @Before fun before() { MemoryDiagnostics.buffer.clear() }
    @After fun after() { MemoryDiagnostics.buffer.clear(); assertTrue(ModelRequestTrace.activeSnapshots().isEmpty()) }

    @Test fun reasoningOnlyCompletedIsAnEmptyResponseFailureNotSuccess() {
        withServer({ emptyResponse() }) { url ->
            val events = mutableListOf<ProviderEvent>()
            val failure = assertThrows(AgentModelFailure::class.java) {
                OpenAiResponsesProvider.complete(request(url), AgentRunController(), events::add)
            }
            assertEquals("MODEL_EMPTY_RESPONSE", failure.code)
            assertTrue(failure.retryable)
            assertFalse(events.any { it is ProviderEvent.Completed })
        }
        val entries = MemoryDiagnostics.buffer.snapshot().entries
        assertFalse(entries.any { it.event == "attempt.completed" })
        assertTrue(entries.single { it.event == "attempt.failed" }.details.contains("code=MODEL_EMPTY_RESPONSE"))
        val output = entries.single { it.event == "response.output" }
        assertTrue(output.details.contains("stream_text_chars=0"))
        assertTrue(output.details.contains("parsed_text_chars=0"))
        assertTrue(output.details.contains("terminal_item_types=reasoning:1"))
        assertTrue(output.details.contains("server_request_id=fixture-empty-request"))
        assertFalse(entries.joinToString { it.text() }.contains("SECRET_"))
    }

    @Test fun refusalAndTruncationAreNotRetryableEmptyAnswers() {
        val refusal = terminal(JSONArray().put(JSONObject().put("type", "message").put("content",
            JSONArray().put(JSONObject().put("type", "refusal").put("refusal", "SECRET_REFUSAL")))))
        val truncated = terminal(JSONArray(), "response.incomplete", "max_output_tokens")
        for ((body, expected) in listOf(refusal to "MODEL_REFUSAL", truncated to "MODEL_OUTPUT_LIMIT")) {
            withServer({ body }) { url ->
                val failure = assertThrows(AgentModelFailure::class.java) {
                    OpenAiResponsesProvider.complete(request(url), AgentRunController())
                }
                assertEquals(expected, failure.code)
                assertFalse(failure.retryable)
            }
        }
        assertFalse(MemoryDiagnostics.buffer.snapshot().entries.joinToString { it.text() }.contains("SECRET_"))
    }

    @Test fun textThatDisappearsFromNonemptyTerminalIsNotBlamedOnServerEmptyOutput() {
        val delta = "data: " + JSONObject().put("type", "response.output_text.delta").put("delta", "SECRET_TEXT") + "\n\n"
        withServer({ delta + emptyResponse() }) { url ->
            val failure = assertThrows(AgentModelFailure::class.java) {
                OpenAiResponsesProvider.complete(request(url), AgentRunController())
            }
            assertEquals("RESPONSE_OUTPUT_MISMATCH", failure.code)
            assertFalse(failure.retryable)
        }
    }

    @Test fun unsupportedContentIsNotRetriedAsAnEmptyAnswer() {
        val body = terminal(JSONArray().put(JSONObject().put("type", "message").put("content",
            JSONArray().put(JSONObject().put("type", "SECRET_UNKNOWN_TYPE").put("text", "SECRET_TEXT")))))
        withServer({ body }) { url ->
            val failure = assertThrows(AgentModelFailure::class.java) {
                OpenAiResponsesProvider.complete(request(url), AgentRunController())
            }
            assertEquals("RESPONSE_UNSUPPORTED_OUTPUT", failure.code)
            assertFalse(failure.retryable)
        }
        assertFalse(MemoryDiagnostics.buffer.snapshot().entries.joinToString { it.text() }.contains("SECRET_"))
    }

    @Test fun emptyThenSuccessRetriesTheExactRequestWithoutAppendingEmptyHistory() {
        val requests = mutableListOf<String>()
        val delays = mutableListOf<Long>()
        withServer({ body -> requests += body; if (requests.size == 1) emptyResponse() else answer() }) { url ->
            val input = request(url).copy(messages = JSONArray()
                .put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject()
                    .put("id", "call_done").put("function", JSONObject().put("name", "once").put("arguments", "{}")))))
                .put(JSONObject().put("role", "tool").put("tool_call_id", "call_done").put("content", "SECRET_OLD_TOOL_RESULT")))
            val before = input.messages.toString()
            val result = AgentModelRetry { _, ms -> delays += ms }.complete(1, input, OpenAiResponsesProvider,
                AgentRunController(), {}, { _, _ -> }, {})
            assertEquals("OK", result.response.assistantMessage.getString("content"))
            assertEquals(before, input.messages.toString())
        }
        assertEquals(2, requests.size)
        assertEquals(requests[0], requests[1])
        assertEquals(listOf(2000L), delays)
        val entries = MemoryDiagnostics.buffer.snapshot().entries
        assertEquals(1, entries.count { it.event == "attempt.failed" })
        assertEquals(1, entries.count { it.event == "attempt.completed" })
        assertFalse(entries.joinToString { it.text() }.contains("SECRET_"))
    }

    @Test fun repeatedEmptyResponsesStopAtExistingRetryBudget() {
        var calls = 0
        val delays = mutableListOf<Long>()
        withServer({ calls++; emptyResponse() }) { url ->
            val failure = assertThrows(AgentModelFailure::class.java) {
                AgentModelRetry { _, ms -> delays += ms }.complete(1, request(url), OpenAiResponsesProvider,
                    AgentRunController(), {}, { _, _ -> }, {})
            }
            assertEquals("MODEL_EMPTY_RESPONSE", failure.code)
            assertFalse(failure.retryable)
        }
        assertEquals(4, calls)
        assertEquals(listOf(2000L, 4000L, 8000L), delays)
        assertFalse(MemoryDiagnostics.buffer.snapshot().entries.any { it.event == "attempt.completed" })
    }

    @Test fun emptyAfterHostedToolDoesNotReplayTheHostedTool() {
        var calls = 0
        val hosted = "data: " + JSONObject().put("type", "response.web_search_call.in_progress").put("item_id", "web1") + "\n\n"
        withServer({ calls++; hosted + emptyResponse() }) { url ->
            val failure = assertThrows(AgentModelFailure::class.java) {
                AgentModelRetry { _, _ -> fail("Hosted tool must not replay") }.complete(1, request(url), OpenAiResponsesProvider,
                    AgentRunController(), {}, { _, _ -> }, {})
            }
            assertEquals("MODEL_EMPTY_RESPONSE", failure.code)
            assertFalse(failure.retryable)
        }
        assertEquals(1, calls)
    }

    @Test fun hostedToolReportedOnlyInTerminalAlsoCannotReplay() {
        var calls = 0
        val body = terminal(JSONArray().put(JSONObject().put("type", "web_search_call").put("status", "completed")))
        withServer({ calls++; body }) { url ->
            val failure = assertThrows(AgentModelFailure::class.java) {
                AgentModelRetry { _, _ -> fail("Terminal hosted tool must not replay") }.complete(1, request(url), OpenAiResponsesProvider,
                    AgentRunController(), {}, { _, _ -> }, {})
            }
            assertEquals("MODEL_EMPTY_RESPONSE", failure.code)
            assertFalse(failure.retryable)
        }
        assertEquals(1, calls)
    }

    private fun answer() = terminal(JSONArray().put(JSONObject().put("type", "message").put("content",
        JSONArray().put(JSONObject().put("type", "output_text").put("text", "OK")))))

    private fun request(url: String) = ProviderRequest(
        AgentModelClient.ModelConfig(baseUrl=url, apiKey="SECRET_KEY", model="SECRET_MODEL", systemPrompt="SECRET_SYSTEM",
            openAiEndpointMode=OpenAiEndpointMode.RESPONSES),
        JSONArray().put(JSONObject().put("role", "user").put("content", "SECRET_PROMPT")), JSONArray(),
    )

    private fun emptyResponse() = terminal(JSONArray().put(JSONObject().put("type", "reasoning")
        .put("summary", JSONArray().put(JSONObject().put("type", "summary_text").put("text", "SECRET_REASONING")))))

    private fun terminal(output: JSONArray, type: String = "response.completed", reason: String? = null): String =
        "event: $type\ndata: " + JSONObject().put("type", type).put("response", JSONObject()
            .put("id", "response-fixture").put("status", type.substringAfter('.')).put("output", output)
            .apply { if (reason != null) put("incomplete_details", JSONObject().put("reason", reason)) }) + "\n\n"

    private fun withServer(body: (String) -> String, block: (String) -> Unit) {
        val pool = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = pool
        server.createContext("/responses") { exchange ->
            val request = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val bytes = body(request).toByteArray()
            exchange.responseHeaders.add("x-request-id", "fixture-empty-request")
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}") } finally { server.stop(0); pool.shutdownNow() }
    }
}
