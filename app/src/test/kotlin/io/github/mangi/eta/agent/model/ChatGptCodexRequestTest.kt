package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.auth.ChatGptCredentials
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSourceTypes
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptCodexRequestTest {
    private val originalSource = OpenAiResponsesProvider.chatGptCredentialSource

    @After
    fun restoreCredentialSource() {
        OpenAiResponsesProvider.chatGptCredentialSource = originalSource
    }

    @Test
    fun responsesUrlTargetsCodexEndpoint() {
        assertEquals(
            "https://chatgpt.com/backend-api/codex/responses",
            ChatGptCodexRequest.responsesUrl("https://chatgpt.com/backend-api/"),
        )
        assertEquals(
            "https://chatgpt.com/backend-api/codex/responses",
            ChatGptCodexRequest.responsesUrl("https://chatgpt.com/backend-api/codex"),
        )
        assertEquals(
            "https://chatgpt.com/backend-api/codex/responses",
            ChatGptCodexRequest.responsesUrl("https://chatgpt.com/backend-api/codex/responses"),
        )
    }

    @Test
    fun bodyRequestsEncryptedReasoningAndCacheKey() {
        val body = JSONObject()
            .put("store", true)
            .put("instructions", "")
            .put("include", JSONArray().put("reasoning.encrypted_content"))

        ChatGptCodexRequest.applyBody(body, "session-1")

        assertEquals(false, body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        assertTrue(body.getString("instructions").isNotBlank())
        assertEquals(1, body.getJSONArray("include").length())
        assertEquals("session-1", body.getString("prompt_cache_key"))
    }

    @Test
    fun usageLimitProducesReadableNonRetryableFailure() {
        val failure = ChatGptCodexRequest.usageLimitFailure(
            429,
            JSONObject().put(
                "error",
                JSONObject().put("code", "usage_limit_reached").put("plan_type", "PLUS"),
            ).toString(),
        )
        assertEquals("CHATGPT_USAGE_LIMIT", failure?.code)
        assertEquals(false, failure?.retryable)
        assertTrue(failure?.message.orEmpty().contains("plus"))
        assertNull(ChatGptCodexRequest.usageLimitFailure(400, "{\"error\":{\"code\":\"invalid\"}}"))
    }

    @Test
    fun providerSendsAccountHeadersAndRefreshesOnceAfterUnauthorized() {
        val requests = CopyOnWriteArrayList<Pair<Map<String, String>, JSONObject>>()
        val refreshFlags = CopyOnWriteArrayList<Boolean>()
        OpenAiResponsesProvider.chatGptCredentialSource = { force ->
            refreshFlags += force
            ChatGptCredentials(
                accessToken = if (force) "fresh-token" else "stale-token",
                refreshToken = "refresh",
                expiresAtMillis = Long.MAX_VALUE,
                accountId = "acct_1",
            )
        }
        val completed = "event: response.output_text.delta\ndata: " +
            JSONObject().put("type", "response.output_text.delta").put("delta", "hi") + "\n\n" +
            "event: response.completed\ndata: " +
            JSONObject().put("type", "response.completed").put(
                "response",
                JSONObject().put("status", "completed").put(
                    "output",
                    JSONArray().put(
                        JSONObject().put("id", "msg_1").put("type", "message").put(
                            "content",
                            JSONArray().put(JSONObject().put("type", "output_text").put("text", "hi")),
                        ),
                    ),
                ),
            ) + "\n\n"

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/backend-api/codex/responses") { exchange ->
            val headers = exchange.requestHeaders.entries.associate { (key, values) -> key.lowercase() to values.first() }
            requests += headers to JSONObject(exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) })
            if (headers["authorization"] == "Bearer stale-token") {
                val bytes = "{\"error\":{\"code\":\"token_expired\"}}".toByteArray()
                exchange.sendResponseHeaders(401, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                val bytes = completed.toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            val result = OpenAiResponsesProvider.complete(
                request = ProviderRequest(
                    config = AgentModelClient.ModelConfig(
                        providerSourceType = ProviderSourceTypes.CHATGPT,
                        baseUrl = "http://127.0.0.1:${server.address.port}/backend-api",
                        apiKey = "",
                        model = "gpt-5.5",
                        systemPrompt = "系统提示",
                        openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
                    ),
                    messages = JSONArray()
                        .put(JSONObject().put("role", "system").put("content", "系统提示"))
                        .put(JSONObject().put("role", "user").put("content", "hi")),
                    tools = JSONArray(),
                    sessionId = "session-9",
                ),
                runController = AgentRunController(),
            )

            assertEquals("hi", result.assistantMessage.getString("content"))
            assertEquals(listOf(false, true), refreshFlags.toList())
            assertEquals(2, requests.size)
            val (headers, body) = requests.last()
            assertEquals("Bearer fresh-token", headers["authorization"])
            assertEquals("acct_1", headers["chatgpt-account-id"])
            assertEquals("session-9", headers["session-id"])
            assertEquals("responses=experimental", headers["openai-beta"])
            assertEquals(false, body.getBoolean("store"))
            assertEquals("reasoning.encrypted_content", body.getJSONArray("include").getString(0))
            assertEquals("session-9", body.getString("prompt_cache_key"))
            assertEquals("系统提示", body.getString("instructions"))
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
