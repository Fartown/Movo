package io.github.fartown.movo.agent.model

import io.github.fartown.movo.diagnostics.DiagnosticLevel
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import org.json.JSONArray
import org.json.JSONObject

/** Counts and schema only. Never retain response text, reasoning, or tool arguments. */
internal class ResponsesOutputDiagnostics {
    private var textDoneChars = 0L
    private var refusalEvents = 0

    fun observe(type: String, event: JSONObject) {
        if (type == "response.output_text.done") textDoneChars += event.optString("text").length
        if (type == "response.refusal.delta" || type == "response.refusal.done") refusalEvents++
    }

    fun validate(
        trace: ModelRequestTrace,
        terminalType: String?,
        response: JSONObject,
        assistant: JSONObject,
        streamTextChars: Int,
        streamReasoningChars: Int,
    ) {
        val output = response.optJSONArray("output") ?: JSONArray()
        val itemTypes = linkedMapOf<String, Int>()
        val partTypes = linkedMapOf<String, Int>()
        var terminalTextChars = 0L
        var refusals = refusalEvents
        var unsupported = 0
        var hostedOutput = false
        fun count(target: MutableMap<String, Int>, type: String, allowed: Set<String>): String {
            val safeType = type.takeIf { it in allowed } ?: "other"
            target[safeType] = (target[safeType] ?: 0) + 1
            return safeType
        }
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i)
            val type = count(itemTypes, item?.optString("type").orEmpty(), ITEM_TYPES)
            if (type == "other") unsupported++
            if (type in HOSTED_TYPES) hostedOutput = true
            val parts = item?.optJSONArray("content") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j)
                when (count(partTypes, part?.optString("type").orEmpty(), PART_TYPES)) {
                    "output_text" -> terminalTextChars += part?.optString("text").orEmpty().length
                    "refusal" -> refusals++
                    "other" -> unsupported++
                }
            }
        }
        val text = assistant.optString("content")
        val blank = text.isBlank() || text.trim() == "null"
        val calls = assistant.optJSONArray("tool_calls")?.length() ?: 0
        val noAnswer = blank && calls == 0
        val failure = if (!noAnswer) null else when {
            refusals > 0 -> AgentModelFailure("MODEL_REFUSAL", false, "模型拒绝了本次请求，未返回正文；可在设置 → 运行日志查看响应结构。")
            terminalType != "response.completed" -> AgentModelFailure(
                if (assistant.optString("finish_reason") == "length") "MODEL_OUTPUT_LIMIT" else "MODEL_OUTPUT_INCOMPLETE",
                false, "模型输出未完成且没有正文（${assistant.optString("finish_reason")}）；可在设置 → 运行日志查看终止原因。",
            )
            streamTextChars > 0 || textDoneChars > 0 || terminalTextChars > 0 -> AgentModelFailure(
                "RESPONSE_OUTPUT_MISMATCH", false, "模型已返回文本，但响应终态与解析结果不一致；可在设置 → 运行日志查看详情。",
            )
            unsupported > 0 -> AgentModelFailure("RESPONSE_UNSUPPORTED_OUTPUT", false, "模型返回了尚不支持的输出结构；可在设置 → 运行日志查看详情。")
            else -> AgentModelFailure(
                "MODEL_EMPTY_RESPONSE", !hostedOutput,
                "模型服务已结束响应，但没有返回正文或工具调用；这不是网络超时。可尝试关闭模型思考或切换模型，并在设置 → 运行日志查看请求编号。",
            )
        }
        trace.record("response.output", if (failure == null) DiagnosticLevel.INFO else DiagnosticLevel.WARN, mapOf(
            "code" to (failure?.code ?: "OK"),
            "response_id" to MemoryDiagnostics.token(response.optString("id")),
            "response_status" to MemoryDiagnostics.token(response.optString("status")),
            "terminal_type" to terminalType,
            "terminal_item_types" to itemTypes.entries.joinToString(",") { "${it.key}:${it.value}" },
            "terminal_part_types" to partTypes.entries.joinToString(",") { "${it.key}:${it.value}" },
            "stream_text_chars" to streamTextChars,
            "stream_text_done_chars" to textDoneChars,
            "stream_reasoning_chars" to streamReasoningChars,
            "terminal_text_chars" to terminalTextChars,
            "parsed_text_chars" to text.length,
            "parsed_tool_calls" to calls,
            "terminal_hosted_tool" to hostedOutput,
            "refusal_parts_or_events" to refusals,
            "incomplete_reason" to MemoryDiagnostics.token(response.optJSONObject("incomplete_details")?.optString("reason")),
        ))
        if (failure != null) throw failure
    }

    private companion object {
        val ITEM_TYPES = setOf("message", "reasoning", "function_call", "web_search_call", "file_search_call", "computer_call", "code_interpreter_call", "image_generation_call", "mcp_call", "mcp_list_tools", "mcp_approval_request")
        val PART_TYPES = setOf("output_text", "refusal", "reasoning_text", "summary_text")
        val HOSTED_TYPES = ITEM_TYPES - setOf("message", "reasoning", "function_call")
    }
}
