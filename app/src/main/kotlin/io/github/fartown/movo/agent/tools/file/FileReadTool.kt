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
import io.github.fartown.movo.agent.tools.core.ModelInput
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
import org.json.JSONObject

/** PDF 读取方式；mode 仅对 PDF 生效。 */
internal enum class FileReadMode { AUTO, TEXT, IMAGE }

/** 文件类型（由后端按 mime/扩展名判定）。 */
internal enum class FileKind { TEXT, IMAGE, PDF, VIDEO, AUDIO, UNKNOWN }

internal data class FileReadInput(
    val file: String,
    /** 以下均为“是否提供”敏感：不适用参数不静默忽略，报 INVALID_ARGUMENTS。 */
    val offsetLine: Int?,
    val limitLines: Int?,
    val pages: String?,
    val mode: FileReadMode,
    val modeExplicit: Boolean,
    val frames: Int?,
) : ToolInput

internal data class FileReadOutput(
    val data: JSONObject,
    val imageAttached: Boolean,
) : ToolOutput

// ---- 后端返回类型 ----
internal data class ResolvedFile(
    val kind: FileKind,
    val path: String,
    val exists: Boolean,
    val sizeBytes: Long,
    val mime: String?,
)

internal data class TextRead(
    val content: String,
    val encoding: String,
    val totalLines: Int,
    /** 还有后续时给下一段起始行号，否则 null。 */
    val nextOffsetLine: Int?,
)

internal data class ImageRead(val width: Int, val height: Int)

/** 以下三类为“默认关闭 / 尚未实现”占位，真实后端一律抛 UNSUPPORTED（见定义清单 §28 转写、§7.4）。 */
internal data class PdfRead(val pageCount: Int, val pages: List<JSONObject>)
internal data class VideoRead(val durationSeconds: Long, val frames: List<JSONObject>)
internal data class AudioTranscript(val text: String, val coverage: Double)

/** 可测后端：真实实现读本机文件 / content URI；测试用假实现。 */
internal interface FileReadBackend {
    /** 解析来源（句柄、URI、绝对路径），判定类型；文件不存在返回 null。 */
    fun resolve(file: String): ResolvedFile?

    fun readText(file: String, offsetLine: Int, limitLines: Int): TextRead

    /** 暂存图片并返回尺寸。实际把图片喂给模型需要 ContractTool 透传 images（见报告 core 需求）。 */
    fun readImage(file: String): ImageRead

    // 默认关闭 / API34 降级：签名留全，实现返回 UNSUPPORTED。
    fun readPdf(file: String, pages: String?, mode: FileReadMode): PdfRead
    fun readVideo(file: String, frames: Int): VideoRead
    fun transcribeAudio(file: String): AudioTranscript
}

/** §F.28 file_read：读文件并转成模型可理解的内容，按类型自动处理。 */
internal class FileReadTool(
    private val backend: FileReadBackend,
) : ToolContract<FileReadInput, FileReadOutput> {
    override val name = "file_read"
    override val domain = ToolDomain.FILE
    override val summary =
        "读文件：text 按行返回并给续读 offset；image 直接附（需模型支持视觉）。" +
            "PDF、视频、音频转写默认关闭。file 可传 file_search 的句柄、附件 URI 或绝对路径。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("file", "句柄、附件 URI、绝对路径、file://、content://", required = true, maxLength = 1024)
        integer("offset", "文本起始行号（从 1 开始），仅 text", min = 1)
        integer("limit", "文本返回行数，仅 text", min = 1, max = 20_000)
        string("pages", "PDF 页范围，如 \"1-3\"，仅 pdf")
        string("mode", "PDF 处理方式，仅 pdf", enum = FileReadMode.entries.map { it.name.lowercase() })
        integer("frames", "视频抽帧数，1–12，仅 video", min = 1, max = 12)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): FileReadInput = FileReadInput(
        file = args.nonBlank("file"),
        offsetLine = args.intOrNull("offset"),
        limitLines = args.intOrNull("limit"),
        pages = args.stringOrNull("pages"),
        mode = args.enum<FileReadMode>("mode", FileReadMode.AUTO),
        modeExplicit = args.has("mode"),
        frames = if (args.has("frames")) args.int("frames", default = 6, range = 1..12) else null,
    )

    override fun resolve(input: FileReadInput, env: ToolEnvironment): CallResolution {
        val sensitivity = when (sourceClass(input.file)) {
            SourceClass.HANDLE -> Sensitivity.PRIVATE
            SourceClass.ATTACHMENT -> FileSupport.sensitivityOf(input.file)
            SourceClass.PATH -> FileSupport.sensitivityOf(input.file)
        }
        return CallResolution(risk = Risk.READ, sensitivity = sensitivity, resources = emptySet())
    }

    override fun execute(
        input: FileReadInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<FileReadOutput> {
        // 开关门禁：任意路径看“终端”，句柄与附件看“敏感读取”（定义清单 §0.5 文件句柄）。
        val switches = ctx.env.switches
        when (sourceClass(input.file)) {
            SourceClass.PATH -> if (!switches.terminal) {
                return disabled("读取任意路径需要开启「文件与终端」开关")
            }
            SourceClass.HANDLE, SourceClass.ATTACHMENT -> if (!switches.sensitiveRead) {
                return disabled("读取句柄与附件需要开启「读取个人数据」开关")
            }
        }

        ctx.checkCancelled()
        val resolved = backend.resolve(input.file)
            ?: return Verdict.Failed(ToolError(ToolErrorCode.NOT_FOUND, "文件不存在或无法访问：${input.file}"))

        // mode 只对 PDF 生效；auto 时按真实类型。
        val kind = when {
            input.mode == FileReadMode.TEXT -> FileKind.TEXT
            else -> resolved.kind
        }
        validateParams(input, kind)

        return when (kind) {
            FileKind.TEXT -> readTextVerdict(input, resolved)
            FileKind.IMAGE -> readImageVerdict(input, resolved, ctx.env)
            FileKind.PDF -> {
                backend.readPdf(resolved.path, input.pages, input.mode) // 抛 UNSUPPORTED
                unsupported("PDF 读取默认关闭", "pdf_default_off_api34_render_degrade_todo")
            }
            FileKind.VIDEO -> {
                backend.readVideo(resolved.path, input.frames ?: 6) // 抛 UNSUPPORTED
                unsupported("视频抽帧默认关闭", "video_default_off_todo")
            }
            FileKind.AUDIO -> {
                backend.transcribeAudio(resolved.path) // 抛 UNSUPPORTED
                unsupported("音频转写默认关闭", "audio_transcription_default_off")
            }
            FileKind.UNKNOWN -> unsupported("不支持的文件格式", "unsupported_format")
        }
    }

    private fun readTextVerdict(input: FileReadInput, resolved: ResolvedFile): Verdict<FileReadOutput> {
        val offset = input.offsetLine ?: 1
        val limit = input.limitLines ?: DEFAULT_TEXT_LINES
        val read = backend.readText(resolved.path, offset, limit)
        val data = JSONObject()
            .put("kind", "text")
            .put("path", resolved.path)
            .put("encoding", read.encoding)
            .put("content", read.content)
        read.nextOffsetLine?.let {
            data.put("next_offset", it)
            data.put("truncated", JSONObject().put("shown", limit).put("total", read.totalLines).put("unit", "lines"))
        }
        return Verdict.Read(FileReadOutput(data, imageAttached = false))
    }

    private fun readImageVerdict(
        input: FileReadInput,
        resolved: ResolvedFile,
        env: ToolEnvironment,
    ): Verdict<FileReadOutput> {
        if (ModelInput.IMAGE !in env.modelInputs) {
            return unsupported("当前模型不支持图片输入", "model_no_vision")
        }
        val image = backend.readImage(resolved.path)
        // NOTE(core)：ContractTool 目前只透传 renderForModel 的 JSON/Text，不透传 images。
        // 这里先把 image_attached 元数据放进 JSON；真正附图待 core 增加 images 透传。
        val data = JSONObject()
            .put("kind", "image")
            .put("path", resolved.path)
            .put("width", image.width)
            .put("height", image.height)
            .put("image_attached", true)
        return Verdict.Read(FileReadOutput(data, imageAttached = true))
    }

    override fun uiTitle(input: FileReadInput): String = "读取文件 · ${input.file.fileName().forTitle(30)}"

    override fun renderForUi(input: FileReadInput, output: FileReadOutput): ToolUiView {
        val data = output.data
        val path = data.optString("path").ifBlank { input.file }
        return when (data.optString("kind")) {
            "text" -> {
                val content = data.optString("content")
                val shown = content.lines().size
                val total = data.optJSONObject("truncated")?.optInt("total")?.takeIf { it > shown }
                ToolUiView(
                    summary = if (total != null) "$shown / $total 行" else "$shown 行",
                    blocks = listOf(ToolUiBlock.Output(content, label = path)).filter { content.isNotBlank() },
                )
            }
            "image" -> ToolUiView(summary = "图片 · ${data.optInt("width")}×${data.optInt("height")}")
            else -> ToolUiView(summary = "已读取")
        }
    }

    override fun renderForModel(output: FileReadOutput): ModelContent = ModelContent.Json(output.data)

    /** 不适用参数不静默忽略（定义清单 §28）。 */
    private fun validateParams(input: FileReadInput, kind: FileKind) {
        fun reject(param: String, onlyFor: String): Nothing =
            fail(ToolErrorCode.INVALID_ARGUMENTS, "参数 $param 只适用于 $onlyFor 文件，当前是 ${kind.name.lowercase()}")
        if (input.offsetLine != null && kind != FileKind.TEXT) reject("offset", "text")
        if (input.limitLines != null && kind != FileKind.TEXT) reject("limit", "text")
        if (input.pages != null && kind != FileKind.PDF) reject("pages", "pdf")
        if (input.modeExplicit && kind != FileKind.PDF && input.mode != FileReadMode.TEXT) reject("mode", "pdf")
        if (input.frames != null && kind != FileKind.VIDEO) reject("frames", "video")
    }

    private fun sourceClass(file: String): SourceClass = when {
        FileSupport.decodeHandlePath(file) != null -> SourceClass.HANDLE
        file.startsWith("content://") || file.startsWith("file://") -> SourceClass.ATTACHMENT
        else -> SourceClass.PATH
    }

    private fun disabled(message: String): Verdict<FileReadOutput> =
        Verdict.Failed(ToolError(ToolErrorCode.DISABLED, message))

    private fun unsupported(message: String, detail: String): Verdict<FileReadOutput> =
        Verdict.Failed(ToolError(ToolErrorCode.UNSUPPORTED, message, detail = detail))

    private enum class SourceClass { HANDLE, ATTACHMENT, PATH }

    private companion object {
        const val DEFAULT_TEXT_LINES = 2_000
    }
}
