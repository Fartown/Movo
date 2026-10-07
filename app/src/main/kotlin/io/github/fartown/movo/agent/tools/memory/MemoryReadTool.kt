package io.github.fartown.movo.agent.tools.memory

import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.MemoryScope
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

internal data class MemoryReadInput(
    val query: String?,
    val scope: MemoryScopeArg,
    val offset: Int,
    val limit: Int?,
    val maxChars: Int = MemoryReadTool.DEFAULT_CHARS,
) : ToolInput

/** 检索结果里一段连续的行：[startLine] 是这段第一行的行号。 */
internal data class MemorySegment(val startLine: Int, val text: String)

internal data class MemoryReadOutput(
    val scope: MemoryScopeArg,
    val revision: String,
    val startLine: Int,
    val content: String,
    val lineCount: Int,
    val matchedLines: Int?,
    val hasMore: Boolean,
    /** 检索时命中的各段（带行号）；按行读时为空。 */
    val segments: List<MemorySegment> = emptyList(),
    /** 还有没给的内容时，下一次从第几行读（检索时是从第几行接着找）。 */
    val nextOffset: Int? = null,
) : ToolOutput

/**
 * memory_read（只读，§36）：读长期记忆。可按关键词检索或按行读。
 * 返回 revision（clear 时需要）与正文：按行读时正文渲染成纯文本并带「起始行号」，不逐行塞行号——
 * 逐行行号会诱导模型把 "N: " 写进 memory_write 的 old_text，破坏唯一匹配。
 * 检索结果分成若干连续段，每段带起始行号（重构前每行带行号），据此可以再按 offset 读附近。
 * 单次字符预算 max_chars 默认 12000，最多 20000：一次工具结果给模型的总上限是 24000 字（含转义），
 * 重构前的 32000 放不下；读不完的给 next_offset 接着读。
 */
internal class MemoryReadTool(
    private val backend: MemoryBackend,
) : ToolContract<MemoryReadInput, MemoryReadOutput> {
    override val name = "memory_read"
    override val domain = ToolDomain.MEMORY
    override val summary =
        "读长期记忆。可按关键词 query 检索（返回命中段和行号），或用 offset/limit 按行读；没读完时给 next_offset。" +
            "返回 revision（clear 时需要）与正文。角色会话用 scope 区分 user、character。"

    /** 记忆未开启时整体不进目录。 */
    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.memoryScope == MemoryScope.DISABLED) {
            ToolAvailability.Unavailable(ToolErrorCode.DISABLED, "记忆未开启")
        } else {
            ToolAvailability.Available
        }

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("query", "关键词检索（≤200），命中行附近返回", maxLength = 200)
        // 仅角色会话暴露 character；普通会话只有 user。
        val scopes = if (env.memoryScope == MemoryScope.CHARACTER) listOf("user", "character") else listOf("user")
        string("scope", "记忆作用域", enum = scopes)
        integer("offset", "起始行号（从 1 开始）；检索时表示从这一行往后找", min = 1)
        integer("limit", "按行读时最多读取的行数", min = 1)
        integer("max_chars", "本次正文的字符预算，1–$MAX_CHARS，默认 $DEFAULT_CHARS", min = 1, max = MAX_CHARS.toLong())
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): MemoryReadInput {
        val defaultScope = if (env.memoryScope == MemoryScope.CHARACTER) MemoryScopeArg.CHARACTER else MemoryScopeArg.USER
        val scope = args.stringOrNull("scope")?.let { raw ->
            MemoryScopeArg.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: fail(ToolErrorCode.INVALID_ARGUMENTS, "未知 scope：$raw")
        } ?: defaultScope
        if (scope == MemoryScopeArg.CHARACTER && env.memoryScope != MemoryScope.CHARACTER) {
            fail(ToolErrorCode.INVALID_ARGUMENTS, "当前不是角色会话，scope 只能是 user")
        }
        return MemoryReadInput(
            query = args.stringOrNull("query")?.trim()?.takeIf { it.isNotEmpty() },
            scope = scope,
            offset = args.int("offset", default = 1, range = 1..Int.MAX_VALUE),
            limit = args.intOrNull("limit")?.also { if (it < 1) fail(ToolErrorCode.INVALID_ARGUMENTS, "limit 至少 1") },
            maxChars = args.int("max_chars", default = DEFAULT_CHARS, range = 1..MAX_CHARS),
        )
    }

    override fun resolve(input: MemoryReadInput, env: ToolEnvironment): CallResolution =
        CallResolution(risk = Risk.READ, sensitivity = Sensitivity.PRIVATE, resources = emptySet())

    override fun execute(
        input: MemoryReadInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<MemoryReadOutput> {
        val load = when (val l = backend.load(input.scope)) {
            is MemoryLoad.Ok -> l
            MemoryLoad.TooLarge ->
                return Verdict.Failed(ToolError(ToolErrorCode.TOO_LARGE, "记忆文件超过 1 MiB 安全上限"))
            MemoryLoad.Unavailable ->
                return Verdict.Failed(ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "记忆暂时读不到"))
        }
        val lines = if (load.content.isEmpty()) emptyList() else load.content.split('\n')
        val output = if (input.query != null) {
            renderSearch(input, load, lines)
        } else {
            renderPage(input, load, lines)
        }
        return Verdict.Read(output)
    }

    /** 按行分页：从 offset 行起，受行数与字符预算双重限制；比预算还长的单行截短。 */
    private fun renderPage(input: MemoryReadInput, load: MemoryLoad.Ok, lines: List<String>): MemoryReadOutput {
        if (lines.isEmpty()) {
            return MemoryReadOutput(input.scope, load.revision, 1, "", 0, null, hasMore = false)
        }
        val startIndex = (input.offset - 1).coerceIn(0, lines.size)
        val budget = Budget(input.maxChars)
        val builder = StringBuilder()
        var index = startIndex
        var emitted = 0
        while (index < lines.size) {
            if (input.limit != null && emitted >= input.limit) break
            val line = lines[index]
            val separator = if (builder.isEmpty()) "" else "\n"
            if (!budget.fits(separator + line)) {
                if (builder.isEmpty()) {
                    builder.append(budget.clip(line)).append(LONG_LINE_MARK)
                    index++
                }
                break
            }
            budget.take(separator + line)
            builder.append(separator).append(line)
            index++
            emitted++
        }
        val hasMore = index < lines.size
        return MemoryReadOutput(
            scope = input.scope,
            revision = load.revision,
            startLine = startIndex + 1,
            content = builder.toString(),
            lineCount = load.lineCount,
            matchedLines = null,
            hasMore = hasMore,
            nextOffset = if (hasMore) index + 1 else null,
        )
    }

    /** 关键词检索：从 offset 行往后找，命中行 ±1 行上下文，连续的行合成一段并带起始行号；整体受字符预算限制。 */
    private fun renderSearch(input: MemoryReadInput, load: MemoryLoad.Ok, lines: List<String>): MemoryReadOutput {
        val query = input.query!!
        val from = (input.offset - 1).coerceIn(0, lines.size)
        val matched = (from until lines.size).filter { lines[it].contains(query, ignoreCase = true) }
        val included = sortedSetOf<Int>()
        matched.forEach { i ->
            for (c in (i - 1)..(i + 1)) if (c in from until lines.size) included += c
        }
        val budget = Budget(input.maxChars)
        val segments = mutableListOf<MemorySegment>()
        var text: StringBuilder? = null
        var segmentStart = 0
        var previous = -2
        var nextOffset: Int? = null
        fun closeSegment() {
            text?.let { segments += MemorySegment(segmentStart, it.toString()) }
            text = null
        }
        for (i in included) {
            val joins = text != null && i == previous + 1
            val piece = if (joins) "\n" + lines[i] else lines[i]
            if (!budget.fits(piece)) {
                if (segments.isEmpty() && text == null) {
                    // 第一行就超出预算：截短给出，下次从下一行接着找。
                    segments += MemorySegment(i + 1, budget.clip(lines[i]) + LONG_LINE_MARK)
                    nextOffset = (i + 2).takeIf { it <= lines.size }
                } else {
                    nextOffset = i + 1
                }
                break
            }
            budget.take(piece)
            if (!joins) {
                closeSegment()
                text = StringBuilder()
                segmentStart = i + 1
            }
            text!!.append(piece)
            previous = i
        }
        closeSegment()
        return MemoryReadOutput(
            scope = input.scope,
            revision = load.revision,
            startLine = segments.firstOrNull()?.startLine ?: (from + 1),
            content = segments.joinToString("\n…\n") { it.text },
            lineCount = load.lineCount,
            matchedLines = matched.size,
            hasMore = nextOffset != null,
            segments = segments,
            nextOffset = nextOffset,
        )
    }

    /** 标题会存进对话记录，不写记忆内容；检索词是你自己说的，可以写。 */
    override fun uiTitle(input: MemoryReadInput): String =
        input.query?.takeIf { it.isNotBlank() }?.let { "在记忆里找「${it.forTitle()}」" } ?: "读取记忆"

    override fun renderForUi(input: MemoryReadInput, output: MemoryReadOutput): ToolUiView = ToolUiView(
        summary = when {
            output.content.isBlank() -> "没有记忆"
            output.matchedLines != null -> "找到 ${output.matchedLines} 行"
            else -> "${output.lineCount} 行"
        },
        blocks = listOf(ToolUiBlock.Preview(output.content, more = output.hasMore)).filter { output.content.isNotBlank() },
    )

    override fun renderForModel(output: MemoryReadOutput): ModelContent = ModelContent.Json(
        JSONObject()
            .put("scope", output.scope.name.lowercase())
            .put("revision", output.revision)
            .put("line_count", output.lineCount)
            .apply {
                if (output.matchedLines != null) {
                    put("matched_lines", output.matchedLines)
                    put("segments", JSONArray().apply {
                        output.segments.forEach { put(JSONObject().put("start_line", it.startLine).put("text", it.text)) }
                    })
                } else {
                    put("start_line", output.startLine)
                    put("content", output.content)
                }
            }
            .put("has_more", output.hasMore)
            .apply { output.nextOffset?.let { put("next_offset", it) } },
    )

    /**
     * 正文预算：按字数算 [maxChars]，同时按 JSON 转义后的长度不超过 [ESCAPED_LIMIT]
     * （换行、引号、斜杠转义后变两个字符），保证整份结果放得进一次工具结果的上限。
     */
    private class Budget(private val maxChars: Int) {
        private var chars = 0
        private var escaped = 0

        fun fits(piece: String): Boolean =
            chars + piece.length <= maxChars && escaped + escapedLength(piece) <= ESCAPED_LIMIT

        fun take(piece: String) {
            chars += piece.length
            escaped += escapedLength(piece)
        }

        /** 把放不下的单行截到剩余预算内。 */
        fun clip(line: String): String {
            var end = minOf(line.length, maxChars - chars)
            while (end > 0 && escaped + escapedLength(line.substring(0, end)) > ESCAPED_LIMIT) end -= maxOf(1, end / 8)
            if (end in 1 until line.length && line[end - 1].isHighSurrogate()) end--
            return line.substring(0, end.coerceAtLeast(0))
        }

        private fun escapedLength(text: String): Int = JSONObject.quote(text).length - 2
    }

    internal companion object {
        /** 单次返回正文的默认字符预算，与重构前默认 12000 字一致。 */
        const val DEFAULT_CHARS = 12_000

        /** 单次字符预算上限（见类注释：重构前 32000，受一次工具结果 24000 字的总上限限制）。 */
        const val MAX_CHARS = 20_000

        /** 正文转义后的长度上限：留出 revision、行号等字段的余量。 */
        private const val ESCAPED_LIMIT = 22_000

        private const val LONG_LINE_MARK = "…（这一行太长，后面省略）"
    }
}
