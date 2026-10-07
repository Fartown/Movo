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
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolContract
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.fail
import io.github.fartown.movo.agent.tools.core.invalidArgs
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** 查找类型。把第二版的 kind 拆成 type + location，一个下载的 PDF 可同时命中。 */
internal enum class FileType { IMAGE, VIDEO, AUDIO, DOCUMENT, ANY }

/** 查找位置。recordings=录音，downloads=下载，wechat/qq=聊天图片缓存（必须 Root，只有图片）。 */
internal enum class FileLocation {
    ANY, RECORDINGS, DOWNLOADS, WECHAT, QQ;

    val isChatImages: Boolean get() = this == WECHAT || this == QQ
}

internal data class FileSearchInput(
    val type: FileType,
    val location: FileLocation,
    val query: String?,
    val sinceMillis: Long?,
    val untilMillis: Long?,
    val limit: Int,
    val cursor: String?,
) : ToolInput

/** 单条命中：只给句柄与元数据，不读内容。 */
internal data class FileSearchItem(
    val handle: String,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val timeMillis: Long,
    val path: String,
    val durationSeconds: Long? = null,
    val summaryAvailable: Boolean = false,
    /** 聊天图片的版本：original 原图、image、thumbnail 缩略图；其他位置为空。 */
    val variant: String? = null,
)

internal data class FileSearchOutput(
    val items: List<FileSearchItem>,
    val nextCursor: String?,
    val total: Int?,
) : ToolOutput

/** 可测后端：真实实现走 MediaStore / Root；测试用假实现。 */
internal interface FileSearchBackend {
    fun rootAvailable(): Boolean

    fun search(
        type: FileType,
        location: FileLocation,
        query: String?,
        sinceMillis: Long?,
        untilMillis: Long?,
        limit: Int,
        cursor: String?,
    ): FileSearchOutput
}

/** §F.27 file_search：查找本机文件，返回可交给 file_read 的句柄，不读内容。 */
internal class FileSearchTool(
    private val backend: FileSearchBackend,
) : ToolContract<FileSearchInput, FileSearchOutput> {
    override val name = "file_search"
    override val domain = ToolDomain.FILE
    override val summary =
        "查找本机文件/照片/视频/录音/文档（找相册照片=type:image）：type 选 image/video/audio/document/any，" +
            "location 选 any/recordings/downloads/wechat/qq。返回句柄交给 file_read，本工具不读内容。微信/QQ 聊天图片需 Root。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("type", "文件类型", required = true, enum = FileType.entries.map { it.name.lowercase() })
        // 微信/QQ 聊天图片只能 Root 扫描：没有 Root 时不列这两个位置。
        val locations = FileLocation.entries.filter { env.rootAvailable || !it.isChatImages }
        val chatNote = if (env.rootAvailable) "；wechat/qq 是聊天图片缓存，只能配 type=image 或 any，query 匹配路径" else ""
        string("location", "查找位置，默认 any$chatNote", enum = locations.map { it.name.lowercase() })
        string("query", "文件名关键词", maxLength = 200)
        string("since", "起始时间，ISO 8601（含时区）或日期")
        string("until", "截止时间，ISO 8601（含时区）或日期")
        integer("limit", "返回条数，1–30，默认 10", min = 1, max = 30)
        string("cursor", "续页游标")
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): FileSearchInput {
        val type = args.enum<FileType>("type")
        val location = args.enum<FileLocation>("location", FileLocation.ANY)
        // 聊天缓存目录里只有图片：查视频/音频/文档不静默返回空，直接说明。
        if (location.isChatImages && type != FileType.IMAGE && type != FileType.ANY) {
            invalidArgs("location=${location.name.lowercase()} 只能查聊天图片", "type 改用 image")
        }
        return FileSearchInput(
            type = type,
            location = location,
            query = args.stringOrNull("query")?.trim()?.ifEmpty { null },
            sinceMillis = parseTime(args.stringOrNull("since"), "since"),
            untilMillis = parseTime(args.stringOrNull("until"), "until"),
            limit = args.int("limit", default = 10, range = 1..30),
            cursor = args.stringOrNull("cursor"),
        )
    }

    override fun resolve(input: FileSearchInput, env: ToolEnvironment): CallResolution = CallResolution(
        risk = Risk.READ,
        sensitivity = Sensitivity.PRIVATE,
        resources = emptySet(),
    )

    override fun execute(
        input: FileSearchInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<FileSearchOutput> {
        if (input.location.isChatImages) {
            if (!backend.rootAvailable()) {
                return Verdict.Failed(
                    io.github.fartown.movo.agent.tools.core.ToolError(
                        code = ToolErrorCode.ROOT_REQUIRED,
                        message = "读取聊天图片需要 Root",
                        hint = "没有 Root 时无法访问微信/QQ 私有目录",
                    ),
                )
            }
        }
        ctx.checkCancelled()
        val output = backend.search(
            type = input.type,
            location = input.location,
            query = input.query,
            sinceMillis = input.sinceMillis,
            untilMillis = input.untilMillis,
            limit = input.limit,
            cursor = input.cursor,
        )
        return Verdict.Read(output)
    }

    override fun uiTitle(input: FileSearchInput): String {
        val what = when (input.type) {
            FileType.IMAGE -> "图片"
            FileType.VIDEO -> "视频"
            FileType.AUDIO -> "音频"
            FileType.DOCUMENT -> "文档"
            FileType.ANY -> "文件"
        }
        return "搜索$what" + input.query?.takeIf { it.isNotBlank() }?.let { "「${it.forTitle()}」" }.orEmpty()
    }

    override fun renderForUi(input: FileSearchInput, output: FileSearchOutput): ToolUiView {
        val items = output.items.map { item ->
            ToolUiBlock.Item(
                title = item.name,
                subtitle = listOf(uiBytes(item.sizeBytes), uiTime(item.timeMillis)).joinToString(" · "),
            )
        }
        val total = output.total?.takeIf { it > items.size }
        return ToolUiView(
            summary = when {
                items.isEmpty() -> "没找到"
                total != null -> "找到 $total 个，列出 ${items.size} 个"
                else -> "找到 ${items.size} 个"
            },
            blocks = listOf(ToolUiBlock.Items(items)).filter { items.isNotEmpty() },
        )
    }

    override fun renderForModel(output: FileSearchOutput): ModelContent {
        val items = JSONArray()
        output.items.forEach { item ->
            val obj = JSONObject()
                .put("handle", item.handle)
                .put("name", item.name)
                .put("mime", item.mime)
                .put("size_bytes", item.sizeBytes)
                .put("time", item.timeMillis)
                .put("path", item.path)
            item.durationSeconds?.let { obj.put("duration_seconds", it) }
            if (item.summaryAvailable) obj.put("summary_available", true)
            item.variant?.let { obj.put("variant", it) }
            items.put(obj)
        }
        val data = JSONObject().put("items", items).put("count", output.items.size)
        output.nextCursor?.let { data.put("next_cursor", it) }
        return ModelContent.Json(data)
    }

    private fun parseTime(value: String?, field: String): Long? {
        val raw = value?.trim()?.ifEmpty { null } ?: return null
        return runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
            .recoverCatching { LocalDate.parse(raw).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
            .getOrElse { fail(ToolErrorCode.INVALID_ARGUMENTS, "参数 $field 不是有效的时间：$raw", hint = "用 ISO 8601，如 2026-10-04 或 2026-10-04T12:00:00+08:00") }
    }
}
