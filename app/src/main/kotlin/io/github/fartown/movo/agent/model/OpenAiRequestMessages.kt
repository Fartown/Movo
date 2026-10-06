package io.github.fartown.movo.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 将 Movo 会话消息投影为 OpenAI-compatible 请求所需的系统指令结构。 */
internal object OpenAiRequestMessages {
    fun forChatCompletions(source: JSONArray): JSONArray {
        val system = collectInstructions(source, SYSTEM_ROLES)
        return JSONArray().also { messages ->
            if (system.isNotBlank()) {
                messages.put(JSONObject().put("role", "system").put("content", system))
            }
            for (index in 0 until source.length()) {
                val message = source.optJSONObject(index) ?: continue
                if (message.optString("role") !in SYSTEM_ROLES) messages.put(JSONObject(message.toString()).apply {
                    remove("_movo_context_summary")
                    remove("_movo_compacted_users")
                    remove("_movo_summary_through_user")
                    remove("_movo_observation")
                    remove("_movo_message_id")
                    remove("_movo_character_profile")
                })
            }
        }
    }

    /** Responses 的 instructions：环境信息（当前时间）除外，它放在 input 最末尾（[ResponsesRequestBuilder]）。 */
    fun responsesInstructions(source: JSONArray): String =
        collectInstructions(source, RESPONSES_INSTRUCTION_ROLES, skipEnvironment = true)

    /** 环境信息（当前时间）的正文；没有时为空。 */
    fun environmentText(source: JSONArray): String =
        buildList {
            for (index in 0 until source.length()) {
                val message = source.optJSONObject(index) ?: continue
                if (!message.optBoolean(AgentPromptBuilder.ENVIRONMENT_MARKER)) continue
                providerMessageText(message.opt("content")).trim().takeIf(String::isNotEmpty)?.let(::add)
            }
        }.joinToString("\n\n")

    private fun collectInstructions(source: JSONArray, roles: Set<String>, skipEnvironment: Boolean = false): String =
        buildList {
            for (index in 0 until source.length()) {
                val message = source.optJSONObject(index) ?: continue
                if (message.optString("role") !in roles) continue
                if (skipEnvironment && message.optBoolean(AgentPromptBuilder.ENVIRONMENT_MARKER)) continue
                providerMessageText(message.opt("content"))
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let(::add)
            }
        }.joinToString("\n\n")

    private val SYSTEM_ROLES = setOf("system")
    private val RESPONSES_INSTRUCTION_ROLES = setOf("system", "developer")
}
