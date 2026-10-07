package io.github.fartown.movo.agent.tools.file

import io.github.fartown.movo.agent.tools.core.TerminalBody
import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.fileName
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.uiBytes
import io.github.fartown.movo.agent.tools.core.uiTime
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.Risk
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
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONArray
import org.json.JSONObject

internal data class FileListInput(
    val path: String?,
    val hidden: Boolean,
    val limit: Int,
    val cursor: String?,
) : ToolInput

internal data class DirEntry(
    val name: String,
    /** file、dir、link（Root 列出的符号链接）、other。 */
    val type: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
)

internal data class FileListOutput(
    val path: String,
    val entries: List<DirEntry>,
    val nextCursor: String?,
) : ToolOutput

/** 可测后端：真实实现列工作区 / Root 目录；测试用假实现。 */
internal interface FileListBackend {
    fun list(path: String?, hidden: Boolean, limit: Int, cursor: String?): FileListOutput
}

/** §F.30 file_list：列目录内容（名称、类型、大小、修改时间），默认 Movo 工作区。 */
internal class FileListTool(
    private val backend: FileListBackend,
) : ToolContract<FileListInput, FileListOutput> {
    override val name = "file_list"
    override val domain = ToolDomain.FILE
    override val summary =
        "列目录：返回 name、type、size_bytes、modified_at。默认 Movo 工作区，支持 hidden 与分页（limit+cursor）；" +
            "App 读不了的目录有 Root 时用 Root 列。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("path", "目录路径，默认工作区", maxLength = 1024)
        boolean("hidden", "是否包含隐藏项，默认 false")
        integer("limit", "返回条数，1–200，默认 80", min = 1, max = 200)
        string("cursor", "续页游标")
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): FileListInput = FileListInput(
        path = args.stringOrNull("path")?.trim()?.ifEmpty { null },
        hidden = args.bool("hidden", default = false),
        limit = args.int("limit", default = 80, range = 1..200),
        cursor = args.stringOrNull("cursor"),
    )

    override fun resolve(input: FileListInput, env: ToolEnvironment): CallResolution = CallResolution(
        risk = Risk.READ,
        sensitivity = input.path?.let { FileSupport.sensitivityOf(it) } ?: io.github.fartown.movo.agent.tools.core.Sensitivity.NORMAL,
        resources = emptySet(),
    )

    override fun execute(
        input: FileListInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<FileListOutput> {
        if (!ctx.env.switches.terminal) {
            return Verdict.Failed(ToolError(ToolErrorCode.DISABLED, "列目录需要开启「文件与终端」开关"))
        }
        ctx.checkCancelled()
        val output = backend.list(input.path, input.hidden, input.limit, input.cursor)
        return Verdict.Read(fitToBudget(output, input.cursor))
    }

    /**
     * 一页控制在 [MAX_PAGE_CHARS] 以内：长文件名多的目录，200 条可能超过给模型的 24000 字上限，
     * 整份结果会被截成一段纯文本、next_cursor 也跟着丢了。超了就少给几条，next_cursor 指向没给的第一条
     * （App 列和 Root 列的游标都是偏移）。
     */
    private fun fitToBudget(output: FileListOutput, cursor: String?): FileListOutput {
        var used = 0
        var kept = 0
        for (entry in output.entries) {
            val cost = entryJson(entry).toString().length + 1
            if (kept > 0 && used + cost > MAX_PAGE_CHARS) break
            used += cost
            kept++
        }
        if (kept == output.entries.size) return output
        val start = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        return output.copy(entries = output.entries.take(kept), nextCursor = (start + kept).toString())
    }

    override fun uiTitle(input: FileListInput): String =
        "列出目录" + input.path?.takeIf { it.isNotBlank() }?.let { " · ${it.forTitle(30)}" }.orEmpty()

    override fun renderForUi(input: FileListInput, output: FileListOutput): ToolUiView {
        val items = output.entries.map { entry ->
            ToolUiBlock.Item(
                title = entry.name,
                subtitle = listOfNotNull(
                    if (entry.type == "dir") "文件夹" else uiBytes(entry.sizeBytes),
                    entry.modifiedAtMillis.takeIf { it > 0 }?.let { uiTime(it) },
                ).joinToString(" · "),
            )
        }
        val more = if (output.nextCursor != null) " · 还有更多" else ""
        return ToolUiView(
            summary = if (items.isEmpty()) "空目录" else "${items.size} 项$more",
            blocks = listOf(ToolUiBlock.Items(items)).filter { items.isNotEmpty() },
        )
    }

    override fun renderForModel(output: FileListOutput): ModelContent {
        val entries = JSONArray()
        output.entries.forEach { entry -> entries.put(entryJson(entry)) }
        val data = JSONObject().put("path", output.path).put("entries", entries).put("count", output.entries.size)
        output.nextCursor?.let { data.put("next_cursor", it) }
        return ModelContent.Json(data)
    }

    private fun entryJson(entry: DirEntry): JSONObject = JSONObject()
        .put("name", entry.name)
        .put("type", entry.type)
        .put("size_bytes", entry.sizeBytes)
        .put("modified_at", entry.modifiedAtMillis)

    private companion object {
        /** 一页条目给模型的上限（JSON 字数），给路径和外层留出余量，整份不超过 24000 字。 */
        const val MAX_PAGE_CHARS = 20_000
    }
}
