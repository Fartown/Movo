package io.github.fartown.movo.agent.tools.conversation

import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolAvailability
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolContract
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolError
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.fail
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONArray
import org.json.JSONObject

internal data class ConversationReadInput(
    val query: String?,
    val cursor: ConversationCursor?,
    val maxChars: Int,
) : ToolInput

/** 游标：绑定会话快照规模（total）；续读时规模变化即判失效。 */
internal data class ConversationCursor(val startIndex: Int, val snapshotSize: Int)

internal data class ConversationReadOutput(
    val totalMessages: Int,
    val entries: List<ConversationEntryView>,
    val hasMore: Boolean,
    val nextCursor: ConversationCursor?,
) : ToolOutput

/**
 * conversation_read（只读，§40）：读当前会话完整脱敏历史。摘要有损，需核对旧指令、操作细节、工具结果时用。
 * 按消息分页：entries 渲染成「角色: 文本」而非整条 JSON，省 token。
 * 可用性：仅当本次运行绑定了持久会话（env.conversationBound）时进入目录。
 */
internal class ConversationReadTool(
    private val backend: ConversationBackend,
) : ToolContract<ConversationReadInput, ConversationReadOutput> {
    override val name = "conversation_read"
    override val domain = ToolDomain.MEMORY
    override val summary =
        "读当前会话完整脱敏历史。摘要有损，需核对旧指令、操作细节、工具结果时用。" +
            "可用 query 过滤、cursor 续读；敏感工具原文和图片不在历史里。"

    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.conversationBound) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(ToolErrorCode.DISABLED, "当前运行未绑定持久会话")
        }

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("query", "关键词过滤（≤500），只返回包含该词的消息", maxLength = 500)
        string("cursor", "续读游标：上一次返回的 next_cursor")
        integer("max_chars", "本次返回正文的字符预算（256–8000）", min = 256, max = 8000)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): ConversationReadInput {
        val cursor = args.stringOrNull("cursor")?.let { raw ->
            val parts = raw.split(':')
            val start = parts.getOrNull(0)?.toIntOrNull()
            val size = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size != 2 || start == null || size == null || start < 0 || size < 0) {
                fail(ToolErrorCode.INVALID_ARGUMENTS, "cursor 格式应为上一次返回的 next_cursor")
            }
            ConversationCursor(start, size)
        }
        return ConversationReadInput(
            query = args.stringOrNull("query")?.trim()?.takeIf { it.isNotEmpty() },
            cursor = cursor,
            maxChars = args.int("max_chars", default = 8000, range = 256..8000),
        )
    }

    override fun resolve(input: ConversationReadInput, env: ToolEnvironment): CallResolution =
        CallResolution(risk = Risk.READ, sensitivity = Sensitivity.PRIVATE, resources = emptySet())

    override fun execute(
        input: ConversationReadInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<ConversationReadOutput> {
        val history = backend.load()
        val total = history.size
        // 续读：会话规模变化（新消息/压缩）使旧游标失效。
        if (input.cursor != null && input.cursor.snapshotSize != total) {
            return Verdict.Failed(
                ToolError(
                    ToolErrorCode.STALE_OBSERVATION,
                    "会话历史已变化，游标失效",
                    hint = "不带 cursor 重新读取",
                ),
            )
        }
        var index = input.cursor?.startIndex ?: 0
        if (index > total) index = total
        var remaining = input.maxChars
        val collected = mutableListOf<ConversationEntryView>()
        while (index < total && collected.size < MAX_ENTRIES) {
            val entry = history[index]
            if (input.query != null && !entry.text.contains(input.query, ignoreCase = true)) {
                index++
                continue
            }
            val text = entry.text
            if (collected.isNotEmpty() && text.length > remaining) break // 预算用尽，保留整条下回读
            val shown = if (text.length > remaining) safeTruncate(text, remaining) else text
            collected += entry.copy(text = shown)
            remaining -= shown.length
            index++
            if (remaining <= 0) break
        }
        val hasMore = index < total
        return Verdict.Read(
            ConversationReadOutput(
                totalMessages = total,
                entries = collected,
                hasMore = hasMore,
                nextCursor = if (hasMore) ConversationCursor(index, total) else null,
            ),
        )
    }

    override fun uiTitle(input: ConversationReadInput): String =
        input.query?.takeIf { it.isNotBlank() }?.let { "在这次对话里找「${it.forTitle()}」" } ?: "翻看这次对话"

    /** 只写读到哪几条，不把正文再贴一遍。 */
    override fun renderForUi(input: ConversationReadInput, output: ConversationReadOutput): ToolUiView {
        val first = output.entries.minOfOrNull { it.index }
        val last = output.entries.maxOfOrNull { it.index }
        return ToolUiView(
            summary = when {
                first == null || last == null -> "没有找到"
                first == last -> "第 ${first + 1} 条"
                else -> "第 ${first + 1}–${last + 1} 条，共 ${output.totalMessages} 条"
            },
        )
    }

    override fun renderForModel(output: ConversationReadOutput): ModelContent {
        val entries = JSONArray()
        output.entries.forEach { e ->
            entries.put(
                JSONObject()
                    .put("index", e.index)
                    .put("role", e.role)
                    // 「角色: 文本」而非整条 JSON。
                    .put("text", "${e.role}: ${e.text}"),
            )
        }
        return ModelContent.Json(
            JSONObject()
                .put("total_messages", output.totalMessages)
                .put("entries", entries)
                .put("has_more", output.hasMore)
                .apply { output.nextCursor?.let { put("next_cursor", "${it.startIndex}:${it.snapshotSize}") } },
        )
    }

    /** 避免从代理对半截断，退回到不超过 limit 的最大长度。 */
    private fun safeTruncate(text: String, limit: Int): String {
        if (limit <= 0) return ""
        var end = minOf(text.length, limit)
        if (end in 1 until text.length && text[end - 1].isHighSurrogate()) end--
        return text.substring(0, end)
    }

    private companion object {
        const val MAX_ENTRIES = 20
    }
}
