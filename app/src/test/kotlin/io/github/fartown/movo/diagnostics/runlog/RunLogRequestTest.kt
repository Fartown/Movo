package io.github.fartown.movo.diagnostics.runlog

import com.sun.net.httpserver.HttpServer
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.model.AgentModelRetry
import io.github.fartown.movo.agent.model.AgentProviderClient
import io.github.fartown.movo.agent.model.AnthropicMessagesProvider
import io.github.fartown.movo.agent.model.OpenAiChatCompletionsProvider
import io.github.fartown.movo.agent.model.OpenAiResponsesProvider
import io.github.fartown.movo.agent.model.ProviderRequest
import io.github.fartown.movo.agent.model.ProviderRequestPurpose
import io.github.fartown.movo.agent.runtime.AgentRunController
import io.github.fartown.movo.data.auth.ChatGptCredentials
import io.github.fartown.movo.data.model.CustomBody
import io.github.fartown.movo.data.model.OpenAiEndpointMode
import io.github.fartown.movo.data.model.ProviderSourceTypes
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 门槛 2（方案 §6.2）：3 个服务商的真实构造器各接一个假服务端，日志里的 `req/` 文件
 * 与服务端实际收到的请求体比对。“内容一致”和“有没有缺失”分开判。
 */
class RunLogRequestTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: RunLogStore
    private lateinit var server: HttpServer
    private val executor = Executors.newSingleThreadExecutor()
    private val received = CopyOnWriteArrayList<Pair<String, String>>()
    private val replies = ArrayDeque<(String) -> Pair<Int, String>>()
    private val originalCredentials = OpenAiResponsesProvider.chatGptCredentialSource

    private val image = "data:image/png;base64," + Base64.getEncoder().encodeToString(ByteArray(600) { (it * 7).toByte() })

    @Before
    fun setUp() {
        install(RunLogStore.Limits())
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            received += exchange.requestURI.path to body
            val reply = synchronized(replies) { replies.removeFirstOrNull() } ?: { path: String -> 200 to success(path) }
            val (status, text) = reply(exchange.requestURI.path)
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (status == 200) exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        executor.shutdownNow()
        RunLog.install(null, "", { false })
        store.shutdown()
        OpenAiResponsesProvider.chatGptCredentialSource = originalCredentials
    }

    private fun install(limits: RunLogStore.Limits) {
        if (::store.isInitialized) store.shutdown()
        store = RunLogStore(File(temp.root, "run-log-${System.nanoTime()}"), limits, elapsedClock = { MemoryDiagnostics.elapsedClock() })
        RunLog.install(store, "test") { true }
    }

    private val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    @Test
    fun chatCompletionsBodyIsStoredAsSentWithTheImageMovedOut() {
        val config = config("$baseUrl/v1").copy(
            extraBodyJson = """{"metadata":{"trace":"自定义"}}""",
            customBody = listOf(CustomBody("top_k", JsonPrimitive(20))),
        )
        val dir = runAttempt(OpenAiChatCompletionsProvider, ProviderRequest(config, messages(withImage = true), tools()))
        assertStoredAsSent(dir, received.map { it.second })
        val request = lines(dir).single { it["t"] == "request" }
        assertEquals(1, (request["images"] as List<*>).size)
        assertTrue(received.single().second.contains("\"top_k\":20"))
    }

    @Test
    fun responsesBodyIsStoredAsSent() {
        val config = config(baseUrl).copy(openAiEndpointMode = OpenAiEndpointMode.RESPONSES)
        val dir = runAttempt(OpenAiResponsesProvider, ProviderRequest(config, messages(withImage = true), tools()))
        assertStoredAsSent(dir, received.map { it.second })
    }

    @Test
    fun chatGptRewriteAndTheResendAfterUnauthorizedAreBothStored() {
        OpenAiResponsesProvider.chatGptCredentialSource = { force ->
            ChatGptCredentials(if (force) "fresh" else "stale", "refresh", Long.MAX_VALUE, "acct")
        }
        synchronized(replies) { replies += { _: String -> 401 to """{"error":{"code":"token_expired"}}""" } }
        val config = config("$baseUrl/backend-api").copy(
            providerSourceType = ProviderSourceTypes.CHATGPT,
            apiKey = "",
            openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
        )
        val dir = runAttempt(OpenAiResponsesProvider, ProviderRequest(config, messages(withImage = false), tools(), sessionId = "s-1"))
        assertEquals(2, received.size)
        assertStoredAsSent(dir, received.map { it.second })
        val requests = lines(dir).filter { it["t"] == "request" }
        assertEquals(listOf(JsonNumber("1"), JsonNumber("2")), requests.map { it["send"] })
        assertEquals(1, requests.map { it["attempt"] }.distinct().size)
    }

    @Test
    fun anthropicBodyIsStoredAsSent() {
        val dir = runAttempt(AnthropicMessagesProvider, ProviderRequest(config(baseUrl), messages(withImage = true), tools()))
        assertStoredAsSent(dir, received.map { it.second })
    }

    @Test
    fun aRetriedAttemptKeepsTheFailedRequestAndTheProviderErrorText() {
        val error = """{"error":{"message":"服务繁忙","type":"server_error"}}"""
        synchronized(replies) { replies += { _: String -> 500 to error } }
        val dir = runAttempt(OpenAiChatCompletionsProvider, ProviderRequest(config("$baseUrl/v1"), messages(false), tools()))
        assertStoredAsSent(dir, received.map { it.second })
        val lines = lines(dir)
        val starts = lines.filter { it["t"] == "attempt_start" }
        val ends = lines.filter { it["t"] == "attempt_end" }
        assertEquals(starts[0]["attempt"], starts[1]["retry_of"])
        assertEquals(listOf("failed", "ok"), ends.map { it["status"] })
        assertEquals("HTTP_500", ends[0]["code"])
        assertEquals(error, ends[0]["response_body"])
        assertEquals("server_error", ends[0]["provider_type"])
    }

    @Test
    fun compactionRequestsAreRecordedToo() {
        val dir = runAttempt(
            OpenAiChatCompletionsProvider,
            ProviderRequest(config("$baseUrl/v1"), messages(false), JSONArray(), purpose = ProviderRequestPurpose.COMPACTION),
        )
        assertStoredAsSent(dir, received.map { it.second })
        assertEquals("compaction", lines(dir).single { it["t"] == "attempt_start" }["purpose"])
    }

    @Test
    fun aBodyOverTheReferenceBudgetKeepsTheRequestLineAndSaysSo() {
        install(RunLogStore.Limits(referenceChars = 100))
        val dir = runAttempt(OpenAiChatCompletionsProvider, ProviderRequest(config("$baseUrl/v1"), messages(true), tools()))
        val request = lines(dir).single { it["t"] == "request" }
        assertEquals("queue_full", request["dropped"])
        assertEquals(JsonNumber(received.single().second.length.toString()), request["chars"])
        assertFalse(request.containsKey("body"))
    }

    @Test
    fun requestsOutsideAnAttemptAreNotRecorded() {
        var dir: String? = null
        MemoryDiagnostics.withRun(onStart = { run -> RunLog.open(run, emptyMap()).also { dir = it } }) {
            // 推荐追问这类请求不经过重试器，没有尝试编号。
            OpenAiChatCompletionsProvider.complete(ProviderRequest(config("$baseUrl/v1"), messages(false), JSONArray()), AgentRunController())
            RunLog.end("completed")
        }
        assertTrue(store.awaitIdle())
        assertEquals(1, received.size)
        assertTrue(lines(dir!!).none { it["t"] == "request" })
    }

    private fun runAttempt(provider: AgentProviderClient, request: ProviderRequest): String {
        var dir: String? = null
        MemoryDiagnostics.withRun(onStart = { run -> RunLog.open(run, emptyMap()).also { dir = it } }) {
            AgentModelRetry(waitBeforeRetry = { _, _ -> }).complete(
                initialRound = 1,
                request = request,
                provider = provider,
                controller = AgentRunController(),
                onEvent = {},
                onProviderEvent = { _, _ -> },
                discardAttemptReasoning = {},
            )
            RunLog.end("completed")
        }
        assertTrue(store.awaitIdle())
        return dir!!
    }

    /** 每份请求：图片以外逐字一致；把图片放回去后，和服务端收到的逐字段一致；字节数一致。 */
    private fun assertStoredAsSent(dir: String, sent: List<String>) {
        val requests = lines(dir).filter { it["t"] == "request" }
        assertEquals("记下的请求份数", sent.size, requests.size)
        requests.zip(sent).forEach { (record, body) ->
            val stored = GZIPInputStream(File(store.root, "$dir/${record["body"]}").inputStream())
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val images = (record["images"] as? List<*>).orEmpty().map { it as Map<*, *> }
            var index = 0
            val withoutImages = DATA_URL_TOKEN.replace(body) { match ->
                if (match.value.length - 2 > 64) "\"" + images[index++]["file"] + "\"" else match.value
            }
            assertEquals("图片以外的内容逐字一致", withoutImages, stored)
            assertEquals("图片都在", index, images.size)
            assertEquals("还原图片后逐字段一致", RunLogJson.parse(body), restore(RunLogJson.parse(stored), images, dir))
            assertEquals(JsonNumber(body.toByteArray(Charsets.UTF_8).size.toString()), record["bytes"])
        }
    }

    private fun restore(value: Any?, images: List<Map<*, *>>, dir: String): Any? = when (value) {
        is String -> images.firstOrNull { it["file"] == value }?.let { image ->
            "data:${image["mime"]};base64," + Base64.getEncoder().encodeToString(File(store.root, "$dir/$value").readBytes())
        } ?: value
        is Map<*, *> -> value.entries.associate { (key, item) -> key.toString() to restore(item, images, dir) }
        is List<*> -> value.map { restore(it, images, dir) }
        else -> value
    }

    private fun lines(dir: String): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return File(store.root, "$dir/${RunLogStore.LOG_FILE}").readLines().map { RunLogJson.parse(it) as Map<String, Any?> }
    }

    private fun config(baseUrl: String) = AgentModelClient.ModelConfig(
        baseUrl = baseUrl,
        apiKey = "sk-test",
        model = "test-model",
        systemPrompt = "系统提示",
    )

    private fun messages(withImage: Boolean): JSONArray = JSONArray()
        .put(JSONObject().put("role", "system").put("content", "系统提示 a/b \"引号\""))
        .put(
            if (withImage) {
                JSONObject().put("role", "user").put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "看看这张图"))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image))),
                )
            } else {
                JSONObject().put("role", "user").put("content", "你好")
            },
        )

    private fun tools(): JSONArray = JSONArray().put(
        JSONObject().put("type", "function").put(
            "function",
            JSONObject().put("name", "ui_tap").put("description", "点按").put(
                "parameters",
                JSONObject().put("type", "object").put("properties", JSONObject().put("index", JSONObject().put("type", "integer"))),
            ),
        ),
    )

    private fun success(path: String): String = when {
        path.endsWith("/chat/completions") ->
            "data: " + JSONObject().put("choices", JSONArray().put(
                JSONObject().put("delta", JSONObject().put("content", "好")).put("finish_reason", "stop"),
            )) + "\n\ndata: [DONE]\n\n"
        path.endsWith("/messages") -> listOf(
            "message_start" to JSONObject().put("type", "message_start").put("message", JSONObject().put("usage", JSONObject().put("input_tokens", 5))),
            "content_block_start" to JSONObject().put("type", "content_block_start").put("index", 0)
                .put("content_block", JSONObject().put("type", "text").put("text", "")),
            "content_block_delta" to JSONObject().put("type", "content_block_delta").put("index", 0)
                .put("delta", JSONObject().put("type", "text_delta").put("text", "好")),
            "content_block_stop" to JSONObject().put("type", "content_block_stop").put("index", 0),
            "message_delta" to JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", "end_turn")),
            "message_stop" to JSONObject().put("type", "message_stop"),
        ).joinToString("") { (event, data) -> "event: $event\ndata: $data\n\n" }
        else ->
            "event: response.output_text.delta\ndata: " +
                JSONObject().put("type", "response.output_text.delta").put("delta", "好") + "\n\n" +
                "event: response.completed\ndata: " + JSONObject().put("type", "response.completed").put(
                    "response",
                    JSONObject().put("status", "completed").put(
                        "output",
                        JSONArray().put(
                            JSONObject().put("id", "msg_1").put("type", "message").put(
                                "content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "好")),
                            ),
                        ),
                    ),
                ) + "\n\n"
    }

    private companion object {
        val DATA_URL_TOKEN = Regex(""""data:(?:[^"\\]|\\.)*"""")
    }
}
