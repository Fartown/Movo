package io.github.fartown.movo.ui.app

import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.data.db.ConversationMessageEntity
import io.github.fartown.movo.ui.model.AgentChatMessageUi
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.MonitorEventKindUi
import io.github.fartown.movo.ui.model.MonitorEventMessageUi
import io.github.fartown.movo.ui.model.SuggestionChipsMessageUi
import io.github.fartown.movo.ui.model.SystemNoticeCode
import io.github.fartown.movo.ui.model.SystemNoticeMessageUi
import io.github.fartown.movo.ui.model.ThinkingMessageUi
import io.github.fartown.movo.ui.model.TokenUsageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.ToolSummaryMessageUi
import io.github.fartown.movo.ui.model.UserMessageUi
import org.json.JSONArray

/*
 * 界面消息与 conversation_messages 一行之间的映射（原 AgentConversationStore 的映射，行为不变）。
 * 不落库的类型（RunTraceMessageUi、正文为空的流式回答）映射为 null。
 */

/** 旧版本给未命名对话存的标题；读出时当作没有标题。 */
internal const val LEGACY_UNNAMED_CONVERSATION_TITLE = "新对话"

internal fun AgentChatMessageUi.toConversationRow(
    conversationId: String,
    sortIndex: Long,
): ConversationMessageEntity? =
    when (this) {
        is UserMessageUi -> ConversationMessageEntity(
            id = id,
            conversationId = conversationId,
            sortIndex = sortIndex,
            type = TYPE_USER,
            content = content,
            imagesJson = images.toJsonArrayString(),
            isEdited = isEdited,
            // 用户消息借用步骤时刻两列存这一轮的起止时刻（不改表结构）。
            startedAt = runStartedAtMillis,
            finishedAt = runFinishedAtMillis,
        )

        is AgentMessageUi -> {
            if (content.isBlank() && isStreaming) {
                null
            } else {
                ConversationMessageEntity(
                    id = id,
                    conversationId = conversationId,
                    sortIndex = sortIndex,
                    type = TYPE_ASSISTANT,
                    content = content,
                    renderMarkdown = renderMarkdown,
                    // 回答行不用工具列：借它存「工具前说明」标记（定稿 21），不改表结构。
                    toolsJson = if (narration) NARRATION_EXTRA else "[]",
                    contextTokens = usage?.contextTokens,
                    inputTokens = usage?.inputTokens,
                    outputTokens = usage?.outputTokens,
                    reasoningTokens = usage?.reasoningTokens,
                    cachedTokens = usage?.cachedTokens,
                )
            }
        }

        is SystemNoticeMessageUi -> ConversationMessageEntity(
            id = id,
            conversationId = conversationId,
            sortIndex = sortIndex,
            type = TYPE_SYSTEM_NOTICE,
            content = code.wireValue,
            resultSummary = detail,
            contextTokens = contextTokens,
            renderMarkdown = false,
        )

        is ThinkingMessageUi -> ConversationMessageEntity(
            id = id,
            conversationId = conversationId,
            sortIndex = sortIndex,
            type = TYPE_THINKING,
            content = content,
            elapsedSeconds = elapsedSeconds,
        )

        is ToolActivityMessageUi -> ConversationMessageEntity(
            id = id,
            conversationId = conversationId,
            sortIndex = sortIndex,
            type = TYPE_TOOL,
            content = command.orEmpty(),
            toolName = toolName,
            toolStatus = status.name,
            argumentsSummary = argumentsSummary,
            resultSummary = resultSummary,
            imageCount = imageCount,
            startedAt = startedAtMillis,
            finishedAt = finishedAtMillis,
            // 截图、个人数据等临时视图只在本次运行中显示，不存（工具可视化方案 §6）。
            toolViewJson = view?.takeUnless { it.transient }?.toJson()?.toString(),
        )

        is ToolSummaryMessageUi -> ConversationMessageEntity(
            id = id,
            conversationId = conversationId,
            sortIndex = sortIndex,
            type = TYPE_TOOL_SUMMARY,
            content = "",
            toolsJson = tools.toJsonArrayString(),
        )

        // 后台监听行复用已有列（不改表结构）：toolName=task id、argumentsSummary=名称、toolStatus=种类、
        // resultSummary=结束原因、elapsedSeconds=序号、startedAt=发生时刻；轮起点与整轮起止时刻存在 toolsJson。
        is MonitorEventMessageUi -> ConversationMessageEntity(
            id = id,
            conversationId = conversationId,
            sortIndex = sortIndex,
            type = TYPE_MONITOR,
            content = text,
            toolName = taskId,
            argumentsSummary = name,
            toolStatus = kind.name,
            resultSummary = reason,
            elapsedSeconds = seq,
            startedAt = atMillis,
            toolsJson = org.json.JSONObject()
                .put("starts_turn", startsTurn)
                .put("history_anchor", historyAnchor)
                .apply {
                    runStartedAtMillis?.let { put("run_started_at", it) }
                    runFinishedAtMillis?.let { put("run_finished_at", it) }
                    exitCode?.let { put("exit_code", it) }
                    limitMs?.let { put("limit_ms", it) }
                }
                .toString(),
            renderMarkdown = false,
        )

        // 推荐追问沿用工具列表字段存文字；旧版本不认识该类型，读取时直接跳过。
        is SuggestionChipsMessageUi -> ConversationMessageEntity(
            id = id,
            conversationId = conversationId,
            sortIndex = sortIndex,
            type = TYPE_SUGGESTIONS,
            content = "",
            toolsJson = prompts.toJsonArrayString(),
        )

        else -> null
    }

internal fun ConversationMessageEntity.toChatMessage(): AgentChatMessageUi? =
    when (type) {
        TYPE_USER -> UserMessageUi(
            id = id,
            content = content,
            images = imagesJson.toStringList(),
            isEdited = isEdited,
            runStartedAtMillis = startedAt,
            runFinishedAtMillis = finishedAt,
        )

        TYPE_ASSISTANT -> AgentMessageUi(
            id = id,
            content = content,
            isStreaming = false,
            renderMarkdown = renderMarkdown ?: true,
            narration = toolsJson == NARRATION_EXTRA,
            usage = TokenUsageUi(
                contextTokens = contextTokens,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                reasoningTokens = reasoningTokens,
                cachedTokens = cachedTokens,
            ).takeUnless { it.isEmpty },
        )

        TYPE_SYSTEM_NOTICE -> SystemNoticeCode.fromWireValue(content)?.let { code ->
            SystemNoticeMessageUi(
                id = id,
                code = code,
                detail = resultSummary,
                contextTokens = contextTokens,
            )
        }

        TYPE_THINKING -> ThinkingMessageUi(
            id = id,
            content = content,
            isStreaming = false,
            elapsedSeconds = elapsedSeconds,
            collapsed = true,
        )

        TYPE_TOOL -> ToolActivityMessageUi(
            id = id,
            toolName = toolName.orEmpty(),
            status = toolStatus.orEmpty().toToolStatus(),
            argumentsSummary = argumentsSummary.orEmpty(),
            command = content.takeIf(String::isNotBlank),
            resultSummary = resultSummary,
            imageCount = imageCount,
            startedAtMillis = startedAt,
            finishedAtMillis = finishedAt,
            view = io.github.fartown.movo.agent.tools.core.ToolUiView.fromJsonString(toolViewJson),
        )

        TYPE_TOOL_SUMMARY -> ToolSummaryMessageUi(
            id = id,
            tools = toolsJson.toStringList(),
        )

        TYPE_SUGGESTIONS -> toolsJson.toStringList().takeIf { it.isNotEmpty() }?.let { prompts ->
            SuggestionChipsMessageUi(id = id, prompts = prompts)
        }

        TYPE_MONITOR -> {
            val extra = runCatching { org.json.JSONObject(toolsJson) }.getOrNull()
            val kind = runCatching { MonitorEventKindUi.valueOf(toolStatus.orEmpty()) }.getOrDefault(MonitorEventKindUi.Event)
            // 旧版本把到期行的时长存在正文里。
            val legacyLimit = if (kind == MonitorEventKindUi.Ended && resultSummary == "TIMEOUT" && extra?.has("limit_ms") != true) {
                content.toLongOrNull()
            } else {
                null
            }
            MonitorEventMessageUi(
                id = id,
                taskId = toolName.orEmpty(),
                name = argumentsSummary.orEmpty(),
                kind = kind,
                seq = elapsedSeconds ?: 0,
                atMillis = startedAt ?: 0L,
                text = if (legacyLimit != null) "" else content,
                reason = resultSummary,
                // 旧版本把「已停止」「已中断」也记成了一轮的起点；它们不唤醒 Movo，不是起点。
                startsTurn = extra?.optBoolean("starts_turn") == true &&
                    !(kind == MonitorEventKindUi.Ended && resultSummary in NON_WAKING_MONITOR_REASONS),
                historyAnchor = extra?.optBoolean("history_anchor") == true,
                runStartedAtMillis = extra?.takeIf { it.has("run_started_at") }?.optLong("run_started_at"),
                runFinishedAtMillis = extra?.takeIf { it.has("run_finished_at") }?.optLong("run_finished_at"),
                exitCode = extra?.takeIf { it.has("exit_code") }?.optInt("exit_code"),
                limitMs = extra?.takeIf { it.has("limit_ms") }?.optLong("limit_ms") ?: legacyLimit,
            )
        }

        else -> null
    }

private fun String.toToolStatus(): ToolActivityStatusUi =
    runCatching { ToolActivityStatusUi.valueOf(this) }.getOrNull()
        ?.let { status ->
            if (status == ToolActivityStatusUi.Running) ToolActivityStatusUi.Unknown else status
        }
        ?: ToolActivityStatusUi.Unknown

private fun List<String>.toJsonArrayString(): String =
    JSONArray().also { array ->
        forEach { array.put(it) }
    }.toString()

internal fun String.toStringList(): List<String> =
    runCatching {
        val array = JSONArray(this)
        buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

internal fun List<ConversationMessageEntity>.toLegacyHistory(): List<AgentModelClient.ConversationMessage> =
    mapNotNull { message ->
        when (message.type) {
            TYPE_USER -> AgentModelClient.ConversationMessage(
                role = "user",
                content = message.content,
            )
            TYPE_ASSISTANT -> message.content
                .takeIf { it.isNotBlank() }
                ?.let { content ->
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = content,
                    )
                }
            else -> null
        }
    }


private const val TYPE_USER = "user"
private const val TYPE_ASSISTANT = "assistant"
private const val TYPE_SYSTEM_NOTICE = "system_notice"
private const val TYPE_THINKING = "thinking"
private const val NARRATION_EXTRA = "{\"narration\":true}"
private const val TYPE_TOOL = "tool"
private const val TYPE_TOOL_SUMMARY = "tool_summary"
private const val TYPE_SUGGESTIONS = "suggestions"
private const val TYPE_MONITOR = "monitor"
private val NON_WAKING_MONITOR_REASONS = setOf("STOPPED_BY_USER", "STOPPED_BY_AGENT", "SESSION_END", "INTERRUPTED")
