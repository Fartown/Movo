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
import org.json.JSONObject

internal data class MemoryReadInput(
    val query: String?,
    val scope: MemoryScopeArg,
    val offset: Int,
    val limit: Int?,
) : ToolInput

internal data class MemoryReadOutput(
    val scope: MemoryScopeArg,
    val revision: String,
    val startLine: Int,
    val content: String,
    val lineCount: Int,
    val matchedLines: Int?,
    val hasMore: Boolean,
) : ToolOutput

/**
 * memory_read（只读，§36）：读长期记忆。可按关键词检索或按行读。
 * 返回 revision（clear 时需要）与正文：正文渲染成纯文本并带「起始行号」，不逐行塞行号——
 * 逐行行号会诱导模型把 "N: " 写进 memory_write 的 old_text，破坏唯一匹配。
 */
internal class MemoryReadTool(
    private val backend: MemoryBackend,
) : ToolContract<MemoryReadInput, MemoryReadOutput> {
    override val name = "memory_read"
    override val domain = ToolDomain.MEMORY
    override val summary =
        "读长期记忆。可按关键词 query 检索，或用 offset/limit 按行读。返回 revision（clear 时需要）与正文。" +
            "角色会话用 scope 区分 user、character。"

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
        integer("offset", "按行读的起始行号（从 1 开始）", min = 1)
        integer("limit", "最多读取的行数", min = 1)
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

    /** 按行分页：从 offset 行起，受行数与字符预算双重限制。 */
    private fun renderPage(input: MemoryReadInput, load: MemoryLoad.Ok, lines: List<String>): MemoryReadOutput {
        if (lines.isEmpty()) {
            return MemoryReadOutput(input.scope, load.revision, 1, "", 0, null, hasMore = false)
        }
        val startIndex = (input.offset - 1).coerceIn(0, lines.size)
        val builder = StringBuilder()
        var index = startIndex
        var emitted = 0
        while (index < lines.size) {
            if (input.limit != null && emitted >= input.limit) break
            val line = lines[index]
            val extra = if (builder.isEmpty()) line.length else line.length + 1
            if (builder.isNotEmpty() && builder.length + extra > MAX_CHARS) break
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(line)
            index++
            emitted++
            if (builder.length >= MAX_CHARS) break
        }
        return MemoryReadOutput(
            scope = input.scope,
            revision = load.revision,
            startLine = startIndex + 1,
            content = builder.toString(),
            lineCount = load.lineCount,
            matchedLines = null,
            hasMore = index < lines.size,
        )
    }

    /** 关键词检索：命中行 ±1 行上下文，非连续段之间用「…」分隔；整体受字符预算限制。 */
    private fun renderSearch(input: MemoryReadInput, load: MemoryLoad.Ok, lines: List<String>): MemoryReadOutput {
        val query = input.query!!
        val matched = lines.indices.filter { lines[it].contains(query, ignoreCase = true) }
        val included = linkedSetOf<Int>()
        matched.forEach { i ->
            for (c in (i - 1)..(i + 1)) if (c in lines.indices) included += c
        }
        val builder = StringBuilder()
        var last: Int? = null
        var rendered = 0
        for (i in included) {
            val gap = when {
                builder.isEmpty() -> ""
                last != null && i > last!! + 1 -> "\n…\n"
                else -> "\n"
            }
            val line = lines[i]
            if (builder.isNotEmpty() && builder.length + gap.length + line.length > MAX_CHARS) break
            builder.append(gap).append(line)
            last = i
            rendered++
            if (builder.length >= MAX_CHARS) break
        }
        return MemoryReadOutput(
            scope = input.scope,
            revision = load.revision,
            startLine = (included.firstOrNull() ?: 0) + 1,
            content = builder.toString(),
            lineCount = load.lineCount,
            matchedLines = matched.size,
            hasMore = rendered < included.size,
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
            .put("start_line", output.startLine)
            .put("line_count", output.lineCount)
            .put("content", output.content)
            .put("has_more", output.hasMore)
            .apply { output.matchedLines?.let { put("matched_lines", it) } },
    )

    private companion object {
        /** 单次返回正文的字符上限，与旧实现 12000 字对齐。 */
        const val MAX_CHARS = 12_000
    }
}
