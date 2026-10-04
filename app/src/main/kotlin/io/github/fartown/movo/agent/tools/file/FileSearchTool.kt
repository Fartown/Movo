package io.github.fartown.movo.agent.tools.file

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
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** 查找类型。把第二版的 kind 拆成 type + location，一个下载的 PDF 可同时命中。 */
internal enum class FileType { IMAGE, VIDEO, AUDIO, DOCUMENT, ANY }

/** 查找位置。recordings=录音，downloads=下载，wechat/qq=聊天图片（必须 Root）。 */
internal enum class FileLocation { ANY, RECORDINGS, DOWNLOADS, WECHAT, QQ }

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
            "location 选 any/recordings/downloads/wechat/qq。返回句柄交给 file_read，本工具不读内容。聊天图片需 Root。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("type", "文件类型", required = true, enum = FileType.entries.map { it.name.lowercase() })
        string("location", "查找位置，默认 any", enum = FileLocation.entries.map { it.name.lowercase() })
        string("query", "文件名关键词", maxLength = 200)
        string("since", "起始时间，ISO 8601（含时区）或日期")
        string("until", "截止时间，ISO 8601（含时区）或日期")
        integer("limit", "返回条数，1–30，默认 10", min = 1, max = 30)
        string("cursor", "续页游标")
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): FileSearchInput = FileSearchInput(
        type = args.enum<FileType>("type"),
        location = args.enum<FileLocation>("location", FileLocation.ANY),
        query = args.stringOrNull("query")?.trim()?.ifEmpty { null },
        sinceMillis = parseTime(args.stringOrNull("since"), "since"),
        untilMillis = parseTime(args.stringOrNull("until"), "until"),
        limit = args.int("limit", default = 10, range = 1..30),
        cursor = args.stringOrNull("cursor"),
    )

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
        if (input.location == FileLocation.WECHAT || input.location == FileLocation.QQ) {
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
