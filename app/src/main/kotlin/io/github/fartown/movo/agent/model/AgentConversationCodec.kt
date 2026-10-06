package io.github.fartown.movo.agent.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Provider JSON 与 Movo 稳定会话 DTO 之间的转换。 */
internal object AgentConversationCodec {

    private const val IMAGE_OMITTED_TEXT = "[图片观察已在当前回合使用，未写入持久会话]"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encodeTranscriptForStorage(messages: List<AgentModelClient.ConversationMessage>): String =
        json.encodeToString(messages.map(::sanitizeMessage))

    fun encodeConversationCheckpoint(messages: List<AgentModelClient.ConversationMessage>): String =
        encodeTranscriptForStorage(messages)

    fun decodeTranscript(raw: String?): List<AgentModelClient.ConversationMessage> =
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            json.decodeFromString<List<AgentModelClient.ConversationMessage>>(raw)
        }

    /**
     * [responsesOrigin] 是这次请求的服务商与模型（[ResponsesEphemeralState.origin]）：和存档里的一致时，
     * 带上这一步的原始输出项，Responses 组装时原样回放；不传或不一致时按普通助手消息重建。
     */
    fun toJsonObject(
        message: AgentModelClient.ConversationMessage,
        responsesOrigin: String? = null,
    ): JSONObject =
        JSONObject()
            .put("role", message.role)
            .also { target ->
                if (message.messageId.isNotBlank()) target.put("_movo_message_id", message.messageId)
                if (message.contextSummary) target.put("_movo_context_summary", true)
                if (message.compactedUserTurns > 0) target.put("_movo_compacted_users", message.compactedUserTurns)
                if (message.summaryThroughUserTurn > 0) target.put("_movo_summary_through_user", message.summaryThroughUserTurn)
                when {
                    message.contentJson.isNotBlank() ->
                        target.put("content", JSONTokener(message.contentJson).nextValue())
                    else -> target.put("content", message.content)
                }
                if (message.toolCallId.isNotBlank()) {
                    target.put("tool_call_id", message.toolCallId)
                }
                if (message.reasoningContent.isNotBlank()) {
                    target.put("reasoning_content", message.reasoningContent)
                }
                if (message.toolCallsJson.isNotBlank()) {
                    target.put("tool_calls", JSONTokener(message.toolCallsJson).nextValue())
                }
                if (message.responsesOutputJson.isNotBlank() && !responsesOrigin.isNullOrBlank() &&
                    message.responsesOrigin == responsesOrigin
                ) {
                    (runCatching { JSONTokener(message.responsesOutputJson).nextValue() }.getOrNull() as? JSONArray)
                        ?.let { ResponsesEphemeralState.attachOutputItems(target, it, message.responsesOrigin) }
                }
            }

    fun fromJsonObject(message: JSONObject): AgentModelClient.ConversationMessage {
        val contentValue = message.opt("content")
        return AgentModelClient.ConversationMessage(
            messageId = message.optString("_movo_message_id"),
            role = message.optString("role"),
            contextSummary = message.optBoolean("_movo_context_summary"),
            compactedUserTurns = message.optInt("_movo_compacted_users"),
            summaryThroughUserTurn = message.optInt("_movo_summary_through_user"),
            content = (contentValue as? String).orEmpty(),
            contentJson = if (
                contentValue == null ||
                contentValue == JSONObject.NULL ||
                contentValue is String
            ) {
                ""
            } else {
                contentValue.toString()
            },
            toolCallId = message.optString("tool_call_id"),
            reasoningContent = message.optString("reasoning_content"),
            toolCallsJson = message.optJSONArray("tool_calls")?.toString().orEmpty(),
            responsesOutputJson = ResponsesEphemeralState.outputItems(message)?.toString().orEmpty(),
            responsesOrigin = ResponsesEphemeralState.outputOrigin(message),
        )
    }

    fun userTextMessage(text: String): JSONObject =
        JSONObject()
            .put("role", "user")
            .put("content", text)

    fun userMessage(
        text: String,
        images: List<AgentModelClient.ModelImage>,
    ): JSONObject {
        if (images.isEmpty()) return userTextMessage(text)

        val content = JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", text)
        )
        images.forEach { image ->
            require(image.reference.isProviderImageReference()) {
                "模型图片尚未在 Agent Runtime 中物化"
            }
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", image.reference))
            )
        }
        return JSONObject()
            .put("role", "user")
            .put("content", content)
    }

    private fun String.isProviderImageReference(): Boolean =
        startsWith("https://", ignoreCase = true) ||
            startsWith("http://", ignoreCase = true) ||
            startsWith("data:image/", ignoreCase = true)

    fun assistantHistoryMessage(
        source: JSONObject,
        toolCalls: List<AgentModelClient.ToolCall>,
    ): JSONObject =
        JSONObject()
            .put("role", "assistant")
            .put("content", source.opt("content") ?: JSONObject.NULL)
            .also { message ->
                if (toolCalls.isNotEmpty()) {
                    message.put(
                        "tool_calls",
                        JSONArray().also { array ->
                            toolCalls.forEach { call -> array.put(call.toHistoryJson()) }
                        },
                    )
                }
                if (source.has("reasoning_content") && !source.isNull("reasoning_content")) {
                    message.put("reasoning_content", source.optString("reasoning_content"))
                }
                ResponsesEphemeralState.copyOutputItems(source, message)
            }

    fun toolResultMessage(
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ): JSONObject =
        JSONObject()
            .put("role", "tool")
            .put("tool_call_id", toolCall.id)
            .put("content", result.content)

    fun parseToolCalls(message: JSONObject): List<AgentModelClient.ToolCall> {
        val rawCalls = message.optJSONArray("tool_calls") ?: return emptyList()
        val usedIds = mutableSetOf<String>()
        return buildList {
            for (index in 0 until rawCalls.length()) {
                val rawCall = rawCalls.optJSONObject(index) ?: JSONObject()
                val function = rawCall.optJSONObject("function")
                val arguments = function?.opt("arguments")
                val candidateId = rawCall.optString("id").ifBlank { "tool_call_$index" }
                val stableId = if (usedIds.add(candidateId)) {
                    candidateId
                } else {
                    generateSequence(1) { it + 1 }
                        .map { suffix -> "${candidateId}_$suffix" }
                        .first(usedIds::add)
                }
                add(
                    AgentModelClient.ToolCall(
                        id = stableId,
                        name = function?.optString("name")?.trim().orEmpty().ifBlank { "unknown_tool" },
                        argumentsJson = when (arguments) {
                            is JSONObject -> arguments.toString()
                            is String -> arguments.ifBlank { "{}" }
                            else -> "{}"
                        },
                    )
                )
            }
        }
    }

    private fun AgentModelClient.ToolCall.toHistoryJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("arguments", argumentsJson),
            )

    /**
     * 这一轮新增的消息转成稳定会话 DTO：工具参数、结果和模型原始输出都原样保存（和 Codex、Claude Code 一致）。
     * 发给模型的内容本来就离开了设备，存档时再藏起来没有意义；不该给模型的内容应在工具这一层就不返回。
     */
    fun transcript(
        messages: JSONArray,
        startIndex: Int,
    ): List<AgentModelClient.ConversationMessage> =
        buildList {
            for (index in startIndex until messages.length()) {
                messages.optJSONObject(index)
                    ?.let(::fromJsonObject)
                    ?.let(::sanitizeMessage)
                    ?.let(::add)
            }
        }

    fun durableMessage(message: JSONObject): AgentModelClient.ConversationMessage =
        sanitizeMessage(fromJsonObject(message))

    fun encodedSize(messages: List<AgentModelClient.ConversationMessage>): Int =
        json.encodeToString(messages).length

    private fun sanitizeMessage(
        message: AgentModelClient.ConversationMessage,
    ): AgentModelClient.ConversationMessage =
        message.copy(
            role = message.role,
            content = message.content,
            contentJson = sanitizeContentJson(message.contentJson),
            toolCallId = message.toolCallId,
            reasoningContent = message.reasoningContent,
            toolCallsJson = message.toolCallsJson,
        )

    private fun sanitizeContentJson(raw: String): String {
        if (raw.isBlank()) return ""
        val content = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        val sanitized = when (content) {
            is JSONArray -> sanitizeContentArray(content)
            is JSONObject -> sanitizeContentObject(content)
            else -> return ""
        }
        return sanitized.toString()
    }

    private fun sanitizeContentArray(source: JSONArray): JSONArray {
        val target = JSONArray()
        var omittedImage = false
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            if (item.optString("type") in setOf("image_url", "input_image", "image") || item.has("source")) {
                omittedImage = true
                continue
            }
            target.put(sanitizeContentObject(item))
        }
        if (omittedImage) {
            target.put(JSONObject().put("type", "text").put("text", IMAGE_OMITTED_TEXT))
        }
        return target
    }

    private fun sanitizeContentObject(source: JSONObject): JSONObject =
        JSONObject(source.toString()).also { target ->
            target.remove("image_url")
            target.remove("source")
            if (target.has("text")) {
                target.put("text", target.optString("text"))
            }
        }

}
