package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.diagnostics.MemoryDiagnostics
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ModelDiagnosticsTest {
    @Before fun before() { MemoryDiagnostics.buffer.clear() }
    @After fun after() { MemoryDiagnostics.buffer.clear(); assertTrue(ModelRequestTrace.activeSnapshots().isEmpty()) }

    @Test fun compactionRetryKeepsHttpAndRequestIdentityWithoutAnyPayload() {
        val calls = AtomicInteger()
        withServer({ exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("x-request-id", "diagnostic-req-${calls.get()}")
            exchange.responseHeaders.add("Retry-After", "2")
            if (calls.getAndIncrement() == 0) respond(exchange, 503, "{\"error\":{\"code\":\"service_unavailable\",\"type\":\"server_error\",\"message\":\"SECRET_RESPONSE\"}}")
            else respond(exchange, 200, "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"SECRET_ANSWER\"}]}]}}\n\n")
        }) { url ->
            MemoryDiagnostics.withRun {
                AgentModelRetry { _, _ -> }.complete(1, request(url, ProviderRequestPurpose.COMPACTION),
                    OpenAiResponsesProvider, AgentRunController(), {}, { _, _ -> }, {})
            }
        }
        val entries = MemoryDiagnostics.buffer.snapshot().entries
        val failed = entries.single { it.event == "attempt.failed" }
        assertTrue(failed.details.contains("http_status=503"))
        assertTrue(failed.details.contains("server_request_id=diagnostic-req-0"))
        assertTrue(failed.details.contains("retry_after=2"))
        assertTrue(failed.details.contains("purpose=COMPACTION"))
        assertTrue(failed.details.contains("code=HTTP_503"))
        assertTrue(failed.details.contains("provider_error_code=service_unavailable"))
        assertTrue(failed.details.contains("provider_error_type=server_error"))
        assertEquals(failed.context, entries.single { it.event == "retry.scheduled" }.context)
        val success = entries.single { it.event == "attempt.completed" }
        assertEquals(failed.context.run, success.context.run)
        assertNotEquals(failed.context.request, success.context.request)
        assertTrue(success.details.contains("last_sse_event=response.completed"))
        assertFalse(entries.joinToString { it.text() }.contains("SECRET_"))
    }

    @Test fun responsesDoneWithoutTerminalIsDiagnosedAsIncompleteNotSuccess() {
        withServer({ respond(it, 200, "data: [DONE]\n\n") }) { url ->
            assertThrows(AgentModelFailure::class.java) {
                OpenAiResponsesProvider.complete(request(url), AgentRunController())
            }
        }
        val entries = MemoryDiagnostics.buffer.snapshot().entries
        assertTrue(entries.any { it.event == "sse.done_marker" })
        assertTrue(entries.any { it.event == "sse.eof" })
        val failure = entries.single { it.event == "attempt.failed" }
        assertTrue(failure.details.contains("code=STREAM_INCOMPLETE"))
        assertTrue(failure.details.contains("last_sse_event=DONE"))
        assertFalse(entries.any { it.event == "attempt.completed" })
    }

    @Test fun stalledBodyCapturesLastDataStageAndDeepTimeoutCause() {
        withServer({ exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use {
                it.write("event: response.created\ndata: {}\n\n".toByteArray())
                it.flush()
                Thread.sleep(800)
            }
        }) { url ->
            val trace = ModelRequestTrace(request(url), "test")
            val client = AgentHttpClient.modelClient.newBuilder().readTimeout(100, TimeUnit.MILLISECONDS).build()
            try {
                val failure = assertThrows(java.io.IOException::class.java) {
                    client.newCall(Request.Builder().url(url).tag(ModelRequestTrace::class.java, trace).build()).execute().use {
                        readProviderSse(it.body.byteStream(), AgentRunController(), trace) { _, _ -> true }
                    }
                }
                trace.failed(failure)
            } finally { trace.close() }
        }
        val failure = MemoryDiagnostics.buffer.snapshot().entries.single { it.event == "attempt.failed" }
        assertTrue(failure.details.contains("code=MODEL_TIMEOUT"))
        assertTrue(failure.details.contains("stage=reading_body"))
        assertTrue(failure.details.contains("last_sse_event=response.created"))
        assertTrue(failure.details.contains("SocketTimeoutException"))
        assertFalse(failure.details.contains("last_byte_ago_ms=unknown"))
    }

    @Test fun cancellationDoesNotAppearAsModelFailureOrRetry() {
        val trace = ModelRequestTrace(request("https://example.invalid"), "test")
        trace.failed(java.io.IOException("SECRET_cancel"), cancelled = true)
        trace.close()
        val entries = MemoryDiagnostics.buffer.snapshot().entries
        assertTrue(entries.any { it.event == "attempt.cancelled" })
        assertFalse(entries.any { it.event == "attempt.failed" || it.event == "retry.scheduled" })
    }

    private fun request(url: String, purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT) = ProviderRequest(
        AgentModelClient.ModelConfig(baseUrl = url, apiKey = "SECRET_KEY", model = "SECRET_MODEL", systemPrompt = "SECRET_SYSTEM",
            openAiEndpointMode = OpenAiEndpointMode.RESPONSES),
        JSONArray().put(JSONObject().put("role", "user").put("content", "SECRET_PROMPT")), JSONArray(), purpose = purpose,
    )

    private fun respond(exchange: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun withServer(handler: (HttpExchange) -> Unit, block: (String) -> Unit) {
        val pool = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = pool
        server.createContext("/") { exchange -> try { handler(exchange) } catch (_: Exception) { exchange.close() } }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}") } finally { server.stop(0); pool.shutdownNow() }
    }
}
