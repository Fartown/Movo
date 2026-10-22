package io.github.fartown.movo.agent.model

import io.github.fartown.movo.data.model.OpenAiEndpointMode
import io.github.fartown.movo.data.model.ProviderTypes
import io.github.fartown.movo.data.model.ReasoningEffort
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 真实后端（方案 §6）：三种协议各跑一遍「Agent 带工具调用的历史 → 切到对话模式 → 切回 Agent」，确认没有 400、上下文接得上。
 * 只在设了 MOVO_LIVE_BASE_URL / MOVO_LIVE_API_KEY / MOVO_LIVE_MODEL 时跑（取仓库根 .env 的 Agent Plan）；
 * Anthropic 协议用 MOVO_LIVE_ANTHROPIC_BASE_URL。
 */
class ChatModeLiveBackendTest {
    private val baseUrl = System.getenv("MOVO_LIVE_BASE_URL").orEmpty()
    private val apiKey = System.getenv("MOVO_LIVE_API_KEY").orEmpty()
    private val model = System.getenv("MOVO_LIVE_MODEL").orEmpty()
    private val anthropicBaseUrl = System.getenv("MOVO_LIVE_ANTHROPIC_BASE_URL").orEmpty()

    /** 一轮 Agent 任务落库后的样子：用户话、带工具调用的回答、工具结果、最终回答。 */
    private val agentTurn = listOf(
        AgentModelClient.ConversationMessage(role = "user", content = "帮我看看最近的通知，有快递的话把取件码整理出来", messageId = "user-1"),
        AgentModelClient.ConversationMessage(
            role = "assistant",
            content = "",
            toolCallsJson = """[{"id":"call_live_1","type":"function","function":{"name":"notification_list","arguments":"{}"}}]""",
        ),
        AgentModelClient.ConversationMessage(
            role = "tool",
            toolCallId = "call_live_1",
            content = """{"items":[{"app":"顺丰速运","text":"取件码 6-2-3018，菜鸟驿站（北门），今天 18:00 前"},{"app":"京东物流","text":"取件码 A17，丰巢柜 3 号，明天 12:00 前"}]}""",
        ),
        AgentModelClient.ConversationMessage(
            role = "assistant",
            content = "最近有 2 条快递：顺丰取件码 6-2-3018（菜鸟驿站北门，今天 18:00 前）；京东取件码 A17（丰巢柜 3 号，明天 12:00 前）。",
            messageId = "assistant-1",
        ),
    )

    @Test
    fun responses() = roundTrip(config(ProviderTypes.OPENAI_COMPATIBLE, baseUrl, OpenAiEndpointMode.RESPONSES))

    @Test
    fun chatCompletions() = roundTrip(config(ProviderTypes.OPENAI_COMPATIBLE, baseUrl, OpenAiEndpointMode.CHAT_COMPLETIONS))

    @Test
    fun anthropic() {
        assumeTrue(anthropicBaseUrl.isNotBlank())
        roundTrip(config(ProviderTypes.ANTHROPIC, anthropicBaseUrl, ""))
    }

    /** 对话模式开联网、真的搜了网，再切回 Agent：历史里带着 web_search_call / reasoning 原始项，不能 400（定稿 25-9）。 */
    @Test
    fun responsesWebSearchThenAgent() {
        assumeTrue(baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank())
        val config = config(ProviderTypes.OPENAI_COMPATIBLE, baseUrl, OpenAiEndpointMode.RESPONSES)
        val prompt = "请先联网搜索，再用一句话告诉我今天国际新闻里的一条标题。"
        val chat = AgentModelClient.complete(
            config = config.copy(hostedWebSearchEnabled = true),
            prompt = prompt,
            history = agentTurn,
            interactionMode = InteractionMode.CHAT,
            toolExecutor = { error("对话模式不应执行本地工具") },
        )
        assertTrue("对话模式联网回答为空", chat.content.isNotBlank())
        val searched = chat.transcript.any { it.responsesOutputJson.contains("web_search_call") }
        println("live web search: searched=$searched answer=${chat.content.take(80)}")
        assertTrue("这一轮没有触发网页搜索，测不到带搜索记录的历史", searched)
        val agent = AgentModelClient.complete(
            config = config,
            prompt = "不用调用工具，直接告诉我：顺丰那个要在几点前取？",
            history = agentTurn + AgentModelClient.ConversationMessage(role = "user", content = prompt) + chat.transcript,
            typedCatalog = TestToolCatalog::build,
            toolExecutor = { AgentModelClient.ToolResult("{\"ok\":false,\"error\":\"本测试不执行工具\"}") },
        )
        assertTrue("切回 Agent 后接不上上下文：${agent.content}", agent.content.contains("18"))
    }

    private fun roundTrip(config: AgentModelClient.ModelConfig) {
        assumeTrue(baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank())
        // 1. Agent → 对话：历史里有工具调用和工具结果，这一轮不带工具。
        val chat = AgentModelClient.complete(
            config = config,
            prompt = "京东那个取件码是多少？只回答取件码。",
            history = agentTurn,
            interactionMode = InteractionMode.CHAT,
            toolExecutor = { error("对话模式不应执行工具") },
        )
        assertTrue("对话模式回答为空", chat.content.isNotBlank())
        assertTrue("对话模式看不到之前的文字回答：${chat.content}", chat.content.contains("A17"))
        // 2. 对话 → Agent：历史里接着有对话模式的一问一答，这一轮带工具目录。
        val history = agentTurn +
            AgentModelClient.ConversationMessage(role = "user", content = "京东那个取件码是多少？只回答取件码。") +
            chat.transcript
        val agent = AgentModelClient.complete(
            config = config,
            prompt = "不用调用工具，直接告诉我：顺丰那个要在几点前取？",
            history = history,
            typedCatalog = TestToolCatalog::build,
            toolExecutor = { AgentModelClient.ToolResult("{\"ok\":false,\"error\":\"本测试不执行工具\"}") },
        )
        assertTrue("切回 Agent 后回答为空", agent.content.isNotBlank())
        assertTrue("切回 Agent 后接不上上下文：${agent.content}", agent.content.contains("18"))
    }

    private fun config(type: String, url: String, endpointMode: String) = AgentModelClient.ModelConfig(
        providerId = "live",
        providerName = "Live",
        providerType = type,
        baseUrl = url,
        apiKey = apiKey,
        model = model,
        systemPrompt = "",
        openAiEndpointMode = endpointMode,
        reasoningEffort = ReasoningEffort.OFF,
        browserTools = false,
    )
}
