package io.github.fartown.movo.agent.tools.skill

import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.uiFields
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.ToolArgs
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

/** skill_read 的后端读取结果。 */
internal sealed interface SkillReadResult {
    /** 不带 path：SKILL.md 头部信息与正文 + 技能根路径 + 资源文件清单（脚本靠 terminal_run 执行需要路径）。 */
    data class Body(
        val skill: String,
        val rootPath: String,
        val content: String,
        val files: List<String>,
        val name: String = skill,
        val description: String = "",
        /** SKILL.md 开头 --- 之间的字段（name、description、compatibility 等）。 */
        val frontmatter: Map<String, String> = emptyMap(),
    ) : SkillReadResult

    /** 带 path：技能目录内某个文本资源。 */
    data class Resource(
        val skill: String,
        val path: String,
        val rootPath: String,
        val content: String,
    ) : SkillReadResult

    data object NotFound : SkillReadResult

    /** 二进制、不兼容，或「下一次任务才可用」——都映射成 UNSUPPORTED(retry=never)。 */
    data class Unsupported(val reason: String) : SkillReadResult

    data class TooLarge(val reason: String) : SkillReadResult
}

internal interface SkillReadBackend {
    fun read(skill: String, path: String?): SkillReadResult
}

internal data class SkillReadInput(
    val skill: String,
    val path: String?,
    val cursor: Int,
) : ToolInput

internal data class SkillReadOutput(
    val skill: String,
    val path: String?,
    val rootPath: String,
    val content: String,
    val files: List<String>,
    val nextCursor: Int?,
    /** 读 SKILL.md 时才有：名称、描述与 frontmatter。 */
    val header: SkillHeader? = null,
) : ToolOutput

internal data class SkillHeader(val name: String, val description: String, val frontmatter: Map<String, String>)

/**
 * skill_read（只读，§38）：读已安装技能。不带 path 读 SKILL.md 正文（另给 name、description、frontmatter）；
 * 带 path 读技能目录内文本资源。技能索引已在系统提示里，这里不做列表检索。返回 skill、path、content、root_path、files[]。
 */
internal class SkillReadTool(
    private val backend: SkillReadBackend,
) : ToolContract<SkillReadInput, SkillReadOutput> {
    override val name = "skill_read"
    override val domain = ToolDomain.SKILL
    override val summary =
        "读已安装技能。不带 path 读 SKILL.md（name、description、frontmatter 和正文），带 path 读技能目录内文本资源。" +
            "返回 content、root_path、files[]（脚本用 terminal_run 执行需要路径）。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("skill", "技能 id 或名称", required = true)
        string("path", "技能目录内的相对资源路径；省略读 SKILL.md 正文")
        integer("cursor", "续读字符偏移（上次返回的 next_cursor）", min = 0)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): SkillReadInput = SkillReadInput(
        skill = args.nonBlank("skill"),
        path = args.stringOrNull("path")?.trim()?.takeIf { it.isNotEmpty() },
        cursor = args.int("cursor", default = 0, range = 0..Int.MAX_VALUE),
    )

    override fun resolve(input: SkillReadInput, env: ToolEnvironment): CallResolution =
        CallResolution(risk = Risk.READ, sensitivity = Sensitivity.NORMAL, resources = emptySet())

    override fun execute(
        input: SkillReadInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<SkillReadOutput> = when (val result = backend.read(input.skill, input.path)) {
        is SkillReadResult.Body -> page(
            SkillReadOutput(
                result.skill, null, result.rootPath, result.content, result.files, null,
                header = SkillHeader(result.name, result.description, result.frontmatter),
            ),
            input.cursor,
        )
        is SkillReadResult.Resource -> page(
            SkillReadOutput(result.skill, result.path, result.rootPath, result.content, emptyList(), null),
            input.cursor,
        )
        SkillReadResult.NotFound ->
            Verdict.Failed(ToolError(ToolErrorCode.NOT_FOUND, "没有找到技能或资源：${input.skill}"))
        is SkillReadResult.Unsupported ->
            // 「下一次任务才可用」也走这里：UNSUPPORTED 默认 retry=never，本任务内重试永远失败。
            Verdict.Failed(ToolError(ToolErrorCode.UNSUPPORTED, result.reason))
        is SkillReadResult.TooLarge ->
            Verdict.Failed(ToolError(ToolErrorCode.TOO_LARGE, result.reason))
    }

    /** 按 cursor 做字符分页，正文过长时给 next_cursor。 */
    private fun page(output: SkillReadOutput, cursor: Int): Verdict<SkillReadOutput> {
        val full = output.content
        val start = cursor.coerceIn(0, full.length)
        var end = minOf(full.length, start + MAX_CHARS)
        if (end in 1 until full.length && full[end - 1].isHighSurrogate()) end--
        val slice = full.substring(start, end)
        val next = if (end < full.length) end else null
        return Verdict.Read(output.copy(content = slice, nextCursor = next))
    }

    override fun uiTitle(input: SkillReadInput): String =
        "读取技能 · ${input.skill.forTitle()}" + input.path?.takeIf { it.isNotBlank() }?.let { " / ${it.forTitle()}" }.orEmpty()

    override fun renderForUi(input: SkillReadInput, output: SkillReadOutput): ToolUiView = ToolUiView(
        summary = if (output.content.isNotBlank()) "${output.content.lines().size} 行" else "${output.files.size} 个文件",
        blocks = listOfNotNull(
            output.content.takeIf { it.isNotBlank() }?.let { ToolUiBlock.Preview(it, more = output.nextCursor != null) },
            output.files.takeIf { output.content.isBlank() && it.isNotEmpty() }?.let { files ->
                ToolUiBlock.Items(files.map { ToolUiBlock.Item(it) })
            },
        ),
    )

    override fun renderForModel(output: SkillReadOutput): ModelContent = ModelContent.Json(
        JSONObject()
            .put("skill", output.skill)
            .apply {
                output.header?.let { header ->
                    put("name", header.name)
                    put("description", header.description)
                    put("frontmatter", JSONObject().apply { header.frontmatter.forEach { (k, v) -> put(k, v) } })
                }
            }
            .put("path", output.path ?: JSONObject.NULL)
            .put("root_path", output.rootPath)
            .put("content", output.content)
            .put("files", JSONArray(output.files))
            .apply { output.nextCursor?.let { put("next_cursor", it) } },
    )

    private companion object {
        const val MAX_CHARS = 12_000
    }
}
