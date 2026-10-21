package io.github.fartown.movo.agent.model

import io.github.fartown.movo.agent.runtime.AgentRunController
import io.github.fartown.movo.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject

internal interface AgentProviderClient {
    val id: String
    val capabilities: ProviderCapabilities

    fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit = {}
    ): ProviderResponse
}

internal data class ProviderCapabilities(
    val endpoint: EndpointKind,
    val streamingText: Boolean,
    val streamingToolCalls: Boolean,
    val imageInput: Boolean,
    val toolResultImages: Boolean,
    val strictTools: Boolean,
    val parallelToolCalls: Boolean
)

internal enum class EndpointKind {
    CHAT_COMPLETIONS,
    RESPONSES,
    ANTHROPIC_MESSAGES
}

internal enum class ProviderRequestPurpose {
    CHAT, COMPACTION, REPLY_REWRITE,
    /** 回答结束后的推荐追问：不带工具的独立小请求，见 [AgentFollowUpSuggester]。 */
    FOLLOW_UP_SUGGESTIONS,
    /** 对话模式（[InteractionMode.CHAT]）：不带工具，但服务商配置（自定义请求体、联网搜索开关）照常生效。 */
    PLAIN_CHAT;

    val allowsTools: Boolean get() = this == CHAT

    /** 用户在服务商里配的请求体、额外参数和联网搜索是否生效；内部小请求（压缩、改写、追问）不带。 */
    val keepsProviderConfig: Boolean get() = this == CHAT || this == PLAIN_CHAT
}

internal data class ProviderRequest(
    val config: AgentModelClient.ModelConfig,
    val messages: JSONArray,
    val tools: JSONArray,
    val sessionId: String = java.util.UUID.randomUUID().toString(),
    val purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
) {
    val effectiveConfig: AgentModelClient.ModelConfig get() = if (!purpose.keepsProviderConfig) {
        config.copy(hostedWebSearchEnabled = false, extraBodyJson = "", customBody = emptyList())
    } else config
    val effectiveTools: JSONArray get() = if (purpose.allowsTools) tools else JSONArray()
}

internal data class ProviderResponse(
    val assistantMessage: JSONObject
) {
    val stopReason: AssistantStopReason
        get() = AssistantStopReason.fromWireValue(assistantMessage.optString("finish_reason"))
}

internal enum class AssistantStopReason {
    END_TURN,
    TOOL_USE,
    OUTPUT_LIMIT,
    CONTENT_FILTER,
    UNKNOWN;

    companion object {
        fun fromWireValue(value: String?): AssistantStopReason =
            when (value?.trim()?.lowercase()) {
                "stop", "end_turn" -> END_TURN
                "tool_calls", "tool_use" -> TOOL_USE
                "length", "max_tokens" -> OUTPUT_LIMIT
                "content_filter", "refusal" -> CONTENT_FILTER
                else -> UNKNOWN
            }
    }
}

internal enum class AssistantBlockKind {
    TEXT,
    THINKING,
    TOOL_CALL,
}

/** 一段思考的原始类型（如 response.reasoning_text.delta、reasoning_content、thinking_delta）和文字。 */
internal data class ProviderRawPart(val type: String, val text: String)

internal sealed interface ProviderEvent {
    data object RequestStarted : ProviderEvent

    data class ResponseHeaders(
        val httpCode: Int
    ) : ProviderEvent

    data class BlockStart(
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
    ) : ProviderEvent

    data class BlockDelta(
        val kind: AssistantBlockKind,
        val index: Int,
        val delta: String,
        /** 服务商把思考类型抹平之前的原始类型与文字（运行日志用）；同一片里有几种时拆开。 */
        val rawParts: List<ProviderRawPart>? = null,
    ) : ProviderEvent

    data class BlockEnd(
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
        val content: String = "",
        val replaceContent: Boolean = false,
    ) : ProviderEvent

    data class Usage(
        val usage: AgentTokenUsage,
        val contextInputTokens: Int? = usage.inputTokens ?: usage.contextTokens?.let {
            (it - (usage.outputTokens ?: 0)).coerceAtLeast(0)
        },
    ) : ProviderEvent

    data class HostedToolStarted(
        val id: String,
        val name: String,
    ) : ProviderEvent

    data class HostedToolFinished(
        val id: String,
        val name: String,
        val success: Boolean,
    ) : ProviderEvent

    data class Completed(
        val reason: String?
    ) : ProviderEvent
}
