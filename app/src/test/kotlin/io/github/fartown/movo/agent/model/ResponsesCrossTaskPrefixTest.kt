package io.github.fartown.movo.agent.model

import io.github.fartown.movo.agent.runtime.AgentRunController
import io.github.fartown.movo.data.model.OpenAiEndpointMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示缓存方案第 3 版：同一对话连续两次任务，后一次任务的第一个请求要以前一次任务最后一个请求开头（逐字一致），
 * 只有末尾那条当前时间不同。前提是：时间不在 instructions 里，放在 input 最末尾；上一次任务的原始输出项随历史存档、原样回放。
 */
class ResponsesCrossTaskPrefixTest {
    private val config = AgentModelClient.ModelConfig(
        providerId = "default",
        baseUrl = "https://example.invalid/v1",
        apiKey = "test-key",
        model = "test-model",
        systemPrompt = "",
        browserTools = false,
        openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
    )

    /** 模拟 Responses 服务：返回带原始输出项的助手消息，并记下每次请求按 Responses 组装后的样子。 */
    private class FakeResponses(
        private val config: AgentModelClient.ModelConfig,
        private val steps: List<JSONObject>,
    ) : AgentProviderClient {
        override val id = "fake-responses"
        override val capabilities = ProviderCapabilities(
            endpoint = EndpointKind.RESPONSES, streamingText = true, streamingToolCalls = true, imageInput = true,
            toolResultImages = false, strictTools = false, parallelToolCalls = false,
        )
        val bodies = mutableListOf<JSONObject>()
        private var index = 0

        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
            bodies += ResponsesRequestBuilder.build(request.effectiveConfig, request.messages, request.effectiveTools)
            val step = JSONObject(steps[index++].toString())
            ResponsesEphemeralState.outputItems(steps[index - 1])?.let {
                ResponsesEphemeralState.attachOutputItems(step, it, ResponsesEphemeralState.origin(config))
            }
            return ProviderResponse(step)
        }
    }

    private fun toolStep(callId: String, tool: String): JSONObject {
        val arguments = "{}"
        return JSONObject()
            .put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
            .put("tool_calls", JSONArray().put(
                JSONObject().put("id", callId).put("type", "function")
                    .put("function", JSONObject().put("name", tool).put("arguments", arguments)),
            ))
            .also {
                // 服务商原样返回的输出项：思考 + 带编号和状态的调用（Movo 重建时不会有这些字段）。
                ResponsesEphemeralState.attachOutputItems(it, JSONArray()
                    .put(JSONObject().put("type", "reasoning").put("id", "rs_$callId").put("summary", JSONArray()))
                    .put(JSONObject().put("type", "function_call").put("id", "fc_$callId").put("status", "completed")
                        .put("call_id", callId).put("name", tool).put("arguments", arguments)))
            }
    }

    private fun answerStep(text: String): JSONObject = JSONObject()
        .put("role", "assistant").put("content", text).put("finish_reason", "stop")
        .also {
            ResponsesEphemeralState.attachOutputItems(it, JSONArray().put(
                JSONObject().put("type", "message").put("id", "msg_$text").put("role", "assistant").put("status", "completed")
                    .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text).put("annotations", JSONArray()))),
            ))
        }

    private fun run(
        provider: FakeResponses,
        prompt: String,
        history: List<AgentModelClient.ConversationMessage>,
        runConfig: AgentModelClient.ModelConfig = config,
    ): List<AgentModelClient.ConversationMessage> {
        val result = AgentModelClient.complete(
            config = runConfig,
            typedCatalog = TestToolCatalog::build,
            prompt = prompt,
            history = history,
            toolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("""{"status":"ok"}""") },
            provider = provider,
        )
        // App 存历史：用户这句话 + 这次任务的 transcript，经过存档编码再读回来。
        val stored = history + AgentModelClient.ConversationMessage(role = "user", content = prompt) + result.transcript
        return AgentConversationCodec.decodeTranscript(AgentConversationCodec.encodeTranscriptForStorage(stored))
    }

    private fun JSONArray.items(): List<String> = (0 until length()).map { getJSONObject(it).toString() }

    @Test
    fun theNextTaskStartsWithTheWholePreviousTaskOnlyTheTrailingTimeDiffers() {
        val first = FakeResponses(config, listOf(toolStep("c1", "get_current_context"), toolStep("c2", "get_current_context"), answerStep("看完了")))
        val history = run(first, "打开设置看一下蓝牙", emptyList())
        val second = FakeResponses(config, listOf(answerStep("Wi-Fi 是 Home")))
        run(second, "再看一下 Wi-Fi", history)

        val last = first.bodies.last()
        val next = second.bodies.first()
        // instructions 里没有时间，两次任务逐字一致。
        assertFalse(last.getString("instructions").contains("环境信息（Movo 自动提供"))
        assertEquals(last.getString("instructions"), next.getString("instructions"))
        // 前一次任务最后一个请求（去掉末尾的时间）是后一次任务第一个请求的开头。
        val before = last.getJSONArray("input").items()
        val after = next.getJSONArray("input").items()
        val previous = before.dropLast(1)
        assertEquals(previous, after.take(previous.size))
        // 两个请求的最后一条都是当前时间（开发者消息）。
        listOf(before.last(), after.last()).forEach { item ->
            val json = JSONObject(item)
            assertEquals("developer", json.getString("role"))
            assertTrue(json.getString("content").contains("当前时间"))
        }
        // 上一次任务的原始输出项（思考、带编号的调用）原样回放。
        assertTrue(after.any { it.contains("rs_c1") } && after.any { it.contains("fc_c2") })
    }

    @Test
    fun aDifferentModelRebuildsThePreviousOutputInsteadOfReplayingIt() {
        val first = FakeResponses(config, listOf(toolStep("c1", "get_current_context"), answerStep("好了")))
        val history = run(first, "开始", emptyList())
        val otherModel = config.copy(model = "other-model")
        val second = FakeResponses(otherModel, listOf(answerStep("继续")))
        run(second, "继续", history, runConfig = otherModel)

        val after = second.bodies.first().getJSONArray("input").items()
        assertFalse(after.any { it.contains("rs_c1") || it.contains("fc_c1") })
        assertTrue(after.any { JSONObject(it).optString("type") == "function_call" && JSONObject(it).optString("call_id") == "c1" })
        assertNotEquals(first.bodies.last().getJSONArray("input").items().dropLast(1), after.take(first.bodies.last().getJSONArray("input").length() - 1))
    }
}
