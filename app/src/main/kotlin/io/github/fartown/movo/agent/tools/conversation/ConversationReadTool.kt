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
    /** 从第几条消息读起（entries 里的 index）；和 cursor 二选一。 */
    val startIndex: Int? = null,
) : ToolInput

/**
 * 游标：下一次从第 [index] 条消息的第 [offset] 个字读起；[snapshotSize] 是第一次读时的消息条数，
 * 续读只在这些消息里读（本次任务还在往后追加步骤，不追着读新产生的），条数变少（对话被改过）就判失效。
 */
internal data class ConversationCursor(val index: Int, val offset: Int, val snapshotSize: Int)

/** 读到的一条（或一条的一段）：[offset] 是这一段在整条消息里的起点，[complete] 表示读到了这条的结尾。 */
internal data class ConversationReadEntry(
    val index: Int,
    val role: String,
    val text: String,
    val offset: Int,
    val complete: Boolean,
)

internal data class ConversationReadOutput(
    val totalMessages: Int,
    val entries: List<ConversationReadEntry>,
    val hasMore: Boolean,
    val nextCursor: ConversationCursor?,
) : ToolOutput

/**
 * conversation_read（只读，§40）：读当前对话的完整记录——数据库里没压缩过的原文（journal），
 * 加上本次任务到目前为止已经做过的步骤（和重构前 conversation_history 同一个数据源）。
 * 每条消息带正文、工具调用（工具名、调用 id、参数）和工具结果；单条超过 max_chars 时按 next_cursor 在同一条里接着读。
 * 可用性：仅当本次运行绑定了持久会话（env.conversationBound）时进入目录。
 */
internal class ConversationReadTool(
    private val backend: ConversationBackend,
) : ToolContract<ConversationReadInput, ConversationReadOutput> {
    override val name = "conversation_read"
    override val domain = ToolDomain.MEMORY
    override val summary =
        "读这次对话的完整记录（没压缩过的原文，含本次任务已做的步骤、工具调用参数和工具结果），核对旧指令、操作细节、工具结果时用。" +
            "可用 query 过滤、index 从某条读起；没读完给 next_cursor，长消息也能接着读后半段。图片不在记录里。"

    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.conversationBound) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(ToolErrorCode.DISABLED, "当前运行未绑定持久会话")
        }

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("query", "关键词过滤（≤500），只返回包含该词的消息", maxLength = 500)
        integer("index", "从第几条消息读起（entries 里的 index，0 起）；和 cursor 二选一", min = 0)
        string("cursor", "续读游标：上一次返回的 next_cursor")
        integer("max_chars", "本次返回正文的字符预算（256–8000）", min = 256, max = 8000)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): ConversationReadInput {
        val cursor = args.stringOrNull("cursor")?.let { raw ->
            val parts = raw.split(':').map { it.toIntOrNull() }
            if (parts.size != 3 || parts.any { it == null || it < 0 }) {
                fail(ToolErrorCode.INVALID_ARGUMENTS, "cursor 格式应为上一次返回的 next_cursor")
            }
            ConversationCursor(parts[0]!!, parts[1]!!, parts[2]!!)
        }
        val startIndex = args.intOrNull("index")?.also {
            if (it < 0) fail(ToolErrorCode.INVALID_ARGUMENTS, "index 从 0 开始")
        }
        if (cursor != null && startIndex != null) fail(ToolErrorCode.INVALID_ARGUMENTS, "cursor 和 index 只能给一个")
        return ConversationReadInput(
            query = args.stringOrNull("query")?.trim()?.takeIf { it.isNotEmpty() },
            cursor = cursor,
            maxChars = args.int("max_chars", default = 8000, range = 256..8000),
            startIndex = startIndex,
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
        // 续读固定在第一次读时的那些消息里；对话被改过（条数变少）旧游标失效。
        val size = input.cursor?.snapshotSize ?: history.size
        if (size > history.size) {
            return Verdict.Failed(
                ToolError(
                    ToolErrorCode.STALE_OBSERVATION,
                    "会话历史已变化，游标失效",
                    hint = "不带 cursor 重新读取",
                ),
            )
        }
        var index = (input.cursor?.index ?: input.startIndex ?: 0).coerceAtMost(size)
        var offset = input.cursor?.offset ?: 0
        var remaining = input.maxChars
        val collected = mutableListOf<ConversationReadEntry>()
        while (index < size && collected.size < MAX_ENTRIES && remaining > 0) {
            val entry = history[index]
            val text = entry.text
            if ((input.query != null && !text.contains(input.query, ignoreCase = true)) || offset >= text.length && offset > 0) {
                index++
                offset = 0
                continue
            }
            var end = minOf(text.length, offset + remaining)
            if (end in (offset + 1) until text.length && text[end - 1].isHighSurrogate()) end--
            if (end == offset && offset < text.length) break
            collected += ConversationReadEntry(entry.index, entry.role, text.substring(offset, end), offset, end == text.length)
            remaining -= end - offset
            if (end < text.length) {
                offset = end // 这一条没读完：下次从这里接着读
                break
            }
            index++
            offset = 0
        }
        val hasMore = index < size
        return Verdict.Read(
            ConversationReadOutput(
                totalMessages = size,
                entries = collected,
                hasMore = hasMore,
                nextCursor = if (hasMore) ConversationCursor(index, offset, size) else null,
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
                    .put("text", e.text)
                    .apply {
                        if (e.offset > 0) put("offset", e.offset)
                        if (!e.complete) put("complete", false)
                    },
            )
        }
        return ModelContent.Json(
            JSONObject()
                .put("total_messages", output.totalMessages)
                .put("entries", entries)
                .put("has_more", output.hasMore)
                .apply { output.nextCursor?.let { put("next_cursor", "${it.index}:${it.offset}:${it.snapshotSize}") } },
        )
    }

    private companion object {
        const val MAX_ENTRIES = 20
    }
}
