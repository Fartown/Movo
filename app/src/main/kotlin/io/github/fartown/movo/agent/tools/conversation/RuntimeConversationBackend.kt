package io.github.fartown.movo.agent.tools.conversation

import io.github.fartown.movo.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 真实会话后端：由主流程在运行启动时绑定 [loader]（AgentRuntimeRunExecutor 传「数据库里这个对话的完整记录
 * （journal，没压缩过）+ 本次任务到目前为止的步骤」），和重构前的 conversation_history 同一个数据源。
 *
 * 每条消息渲染成文本：正文、工具调用（工具名、调用 id、参数）、工具结果（对应的工具名和调用 id）都保留；
 * 只有既没正文也没工具调用时才给推理内容。
 */
internal class RuntimeConversationBackend(
    private val loader: () -> List<AgentModelClient.ConversationMessage>,
) : ConversationBackend {
    override fun load(): List<ConversationEntryView> {
        val messages = loader()
        // 工具结果只带调用 id：从前面助手消息的工具调用里查出工具名。
        val toolNames = mutableMapOf<String, String>()
        return messages.mapIndexed { index, message ->
            val calls = toolCalls(message.toolCallsJson)
            calls.forEach { toolNames[it.id] = it.name }
            ConversationEntryView(
                index = index,
                role = message.role,
                text = renderText(message, calls, toolNames),
            )
        }
    }

    private data class Call(val id: String, val name: String, val arguments: String)

    private fun renderText(
        message: AgentModelClient.ConversationMessage,
        calls: List<Call>,
        toolNames: Map<String, String>,
    ): String {
        val body = message.content.ifBlank { contentText(message.contentJson) }
        val parts = mutableListOf<String>()
        when {
            message.role == "tool" || message.toolCallId.isNotBlank() -> {
                val name = toolNames[message.toolCallId]
                parts += "[" + (name?.let { "$it 的结果" } ?: "工具结果") + "（调用 id ${message.toolCallId}）]"
                parts += body
            }
            else -> {
                if (message.contextSummary) parts += "[之前对话的压缩摘要]"
                if (body.isNotBlank()) parts += body
                calls.forEach { call -> parts += "[调用工具 ${call.name}（调用 id ${call.id}）] ${call.arguments}" }
                if (body.isBlank() && calls.isEmpty() && message.reasoningContent.isNotBlank()) {
                    parts += "[推理] ${message.reasoningContent}"
                }
            }
        }
        return parts.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun toolCalls(raw: String): List<Call> {
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONTokener(raw).nextValue() as? JSONArray }.getOrNull()
            ?: return listOf(Call("", "?", raw))
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map { item ->
            val function = item.optJSONObject("function") ?: JSONObject()
            Call(
                id = item.optString("id"),
                name = function.optString("name").ifBlank { item.optString("name") },
                arguments = function.optString("arguments").ifBlank { item.optJSONObject("input")?.toString().orEmpty() },
            )
        }
    }

    /** 多段内容（文字 + 图片）只取文字；图片在持久记录里本来就没有。 */
    private fun contentText(contentJson: String): String {
        if (contentJson.isBlank()) return ""
        val array = runCatching { JSONTokener(contentJson).nextValue() as? JSONArray }.getOrNull() ?: return contentJson
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("text")?.takeIf(String::isNotBlank) }
            .joinToString("\n")
    }
}
