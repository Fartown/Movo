package io.github.fartown.movo.agent.model

import io.github.fartown.movo.agent.roleplay.CharacterCardCodec
import io.github.fartown.movo.agent.roleplay.RoleplayRunContext
import io.github.fartown.movo.agent.runtime.AgentRunController
import io.github.fartown.movo.data.model.CustomBody
import io.github.fartown.movo.data.model.OpenAiEndpointMode
import io.github.fartown.movo.data.model.ProviderTypes
import io.github.fartown.movo.data.provider.BuiltinProviders
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对话模式（规范 8.2.2、方案 docs/research/chat-agent-mode）：最干净的模型调用。 */
class ChatModeRequestTest {
    private val agentHistory = listOf(
        AgentModelClient.ConversationMessage(role = "user", content = "帮我看看最近的通知", messageId = "user-1"),
        AgentModelClient.ConversationMessage(
            role = "assistant",
            content = "",
            reasoningContent = "先读通知",
            toolCallsJson = """[{"id":"c1","type":"function","function":{"name":"notification_list","arguments":"{}"}}]""",
            responsesOutputJson = """[{"type":"reasoning","id":"rs_1"}]""",
            responsesOrigin = "x",
        ),
        AgentModelClient.ConversationMessage(role = "tool", content = "{\"items\":[]}", toolCallId = "c1"),
        AgentModelClient.ConversationMessage(role = "assistant", content = "最近有 2 条快递。", messageId = "assistant-1"),
    )

    @Test
    fun chatModeSendsOnlyTheConversationText() {
        lateinit var captured: ProviderRequest
        AgentModelClient.complete(
            config = config(systemPrompt = BuiltinProviders.DEFAULT_SYSTEM_PROMPT).copy(
                hostedWebSearchEnabled = true,
                customBody = listOf(CustomBody("thinking", kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("enabled")) })),
            ),
            prompt = "快递柜超时一般怎么收费？",
            history = agentHistory,
            interactionMode = InteractionMode.CHAT,
            provider = provider { request ->
                captured = request.copy(messages = JSONArray(request.messages.toString()))
                reply("一般按时段收费。")
            },
            toolExecutor = { error("对话模式不应执行工具") },
        )
        assertEquals(ProviderRequestPurpose.PLAIN_CHAT, captured.purpose)
        assertEquals(0, captured.effectiveTools.length())
        val messages = (0 until captured.messages.length()).map(captured.messages::getJSONObject)
        // 默认系统提示词写着「可以用工具」：不带；也没有 Movo 身份、环境信息。
        assertFalse(messages.any { it.optString("role") == "system" })
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.optString("role") })
        assertEquals("最近有 2 条快递。", messages[1].optString("content"))
        val wire = captured.messages.toString()
        assertFalse(wire.contains("tool_calls"))
        assertFalse(wire.contains("notification_list"))
        assertFalse(wire.contains("rs_1"))
        // 服务商配置照常：自定义请求体（常用来开关思考）、联网搜索开关。
        assertTrue(captured.effectiveConfig.hostedWebSearchEnabled)
        assertEquals(1, captured.effectiveConfig.customBody.size)
    }

    @Test
    fun chatModeKeepsTheUsersOwnSystemPromptAndTheVoiceSection() {
        lateinit var captured: ProviderRequest
        AgentModelClient.complete(
            config = config(systemPrompt = "你是一只说话很短的猫。"),
            prompt = "你好",
            spokenReply = SpokenReply.MOVO_VOICE,
            interactionMode = InteractionMode.CHAT,
            provider = provider { request ->
                captured = request.copy(messages = JSONArray(request.messages.toString()))
                reply("喵。")
            },
            toolExecutor = { error("对话模式不应执行工具") },
        )
        val systems = (0 until captured.messages.length()).map(captured.messages::getJSONObject)
            .filter { it.optString("role") == "system" }.map { it.optString("content") }
        assertEquals(2, systems.size)
        assertEquals("你是一只说话很短的猫。", systems[0])
        assertTrue(systems[1].startsWith("【语音模式】"))
        assertFalse(systems.any { it.contains("你是 Movo") })
    }

    @Test
    fun chatModeToolCallStopsWithAChatSpecificReason() {
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(
                config = config(),
                prompt = "帮我打开微信",
                interactionMode = InteractionMode.CHAT,
                provider = provider {
                    JSONObject().put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
                        .put("tool_calls", JSONArray().put(JSONObject().put("id", "x").put("type", "function")
                            .put("function", JSONObject().put("name", "app_open").put("arguments", "{}"))))
                },
                toolExecutor = { error("对话模式不应执行工具") },
            )
        }
        assertEquals("CHAT_MODE_TOOL_CALL", (failure.cause as AgentModelFailure).code)
    }

    @Test
    fun rolePlayInChatModeKeepsThePersonaWithoutToolOrMemoryWriteRules() {
        val card = CharacterCardCodec.decodeJson("""{"name":"林舟","description":"旅人","personality":"说话带一点江湖气"}""")
        lateinit var captured: ProviderRequest
        AgentModelClient.complete(
            config = config(systemPrompt = "服务商里填的提示"),
            prompt = "你好",
            roleplayContext = RoleplayRunContext("fixture", card, "旅伴", ""),
            interactionMode = InteractionMode.CHAT,
            provider = provider { request ->
                captured = request.copy(messages = JSONArray(request.messages.toString()))
                reply("这位朋友，幸会。")
            },
            toolExecutor = { error("对话模式不应执行工具") },
        )
        val systems = (0 until captured.messages.length()).map(captured.messages::getJSONObject)
            .filter { it.optString("role") == "system" }.map { it.optString("content") }
        assertEquals(1, systems.size)
        assertTrue(systems[0].contains("说话带一点江湖气"))
        assertFalse(systems[0].contains("工具"))
        assertFalse(systems[0].contains("memory_write"))
    }

    @Test
    fun projectionDropsToolStepsButKeepsSummariesAndUserTurns() {
        val projected = ChatModeHistory.project(
            agentHistory + AgentModelClient.ConversationMessage(
                role = "assistant", content = "[Movo 上下文摘要]…", contextSummary = true, compactedUserTurns = 3,
            ),
        )
        assertEquals(listOf("user", "assistant", "assistant"), projected.map { it.role })
        assertTrue(projected.all { it.toolCallsJson.isEmpty() && it.responsesOutputJson.isEmpty() && it.reasoningContent.isEmpty() })
        assertTrue(projected.last().contextSummary)
        assertEquals(3, projected.last().compactedUserTurns)
    }

    @Test
    fun responsesRequestWithoutSystemMessagesHasNoInstructions() {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "你好"))
        val body = ResponsesRequestBuilder.build(config(), messages, JSONArray())
        assertFalse(body.has("instructions"))
        val withSystem = JSONArray().put(JSONObject().put("role", "system").put("content", "我的提示")).put(messages.get(0))
        assertNotNull(ResponsesRequestBuilder.build(config(), withSystem, JSONArray()).optString("instructions").takeIf { it == "我的提示" })
    }

    @Test
    fun webSearchSwitchIsOfferedOnlyOnTheResponsesEndpointAndSendsOnlyTheHostedTool() {
        // 定稿 25-9：「联网」只在服务商支持网页搜索时出现，条件与服务商详情页的开关一致。
        val responses = config().copy(openAiEndpointMode = OpenAiEndpointMode.RESPONSES)
        assertTrue(responses.supportsHostedWebSearch())
        assertFalse(config().supportsHostedWebSearch())
        assertFalse(responses.copy(providerType = ProviderTypes.ANTHROPIC).supportsHostedWebSearch())

        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "今天杭州天气"))
        val on = ResponsesRequestBuilder.build(responses.copy(hostedWebSearchEnabled = true), messages, JSONArray())
        assertEquals("[{\"type\":\"web_search\"}]", on.getJSONArray("tools").toString())
        val off = ResponsesRequestBuilder.build(responses.copy(hostedWebSearchEnabled = false), messages, JSONArray())
        assertFalse(off.has("tools"))
    }

    private fun config(systemPrompt: String = "") = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1",
        apiKey = "test-key",
        model = "test-model",
        systemPrompt = systemPrompt,
    )

    private fun reply(text: String): JSONObject =
        JSONObject().put("role", "assistant").put("content", text).put("finish_reason", "stop")

    private fun provider(respond: (ProviderRequest) -> JSONObject) = object : AgentProviderClient {
        override val id: String = "scripted"
        override val capabilities = ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false,
        )

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse = ProviderResponse(respond(request))
    }
}
