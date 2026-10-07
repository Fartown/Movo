package io.github.fartown.movo.agent.tools.file

import io.github.fartown.movo.agent.model.AgentModelClient
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

/** 文件类型：先按 mime/扩展名判定；没有扩展名或扩展名不认识时，由后端按文件开头的内容判定（见 [FileSupport.sniffKind]）。 */
internal enum class FileKind { TEXT, IMAGE, PDF, VIDEO, AUDIO, UNKNOWN }

internal data class FileReadInput(
    val file: String,
    /** 只对文本生效：读到图片时不静默忽略，报 INVALID_ARGUMENTS。 */
    val offsetLine: Int?,
    val limitLines: Int?,
) : ToolInput

internal data class FileReadOutput(
    val data: JSONObject,
    /** 附给模型本回合的图片；读文本时为空。 */
    val image: AgentModelClient.ModelImage? = null,
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

/** 读到的图片：尺寸 + 已编码成模型输入的图片（由 [FileReadTool.images] 附给模型）。 */
internal data class ImageRead(val width: Int, val height: Int, val image: AgentModelClient.ModelImage)

/** 可测后端：真实实现读本机文件 / content URI，App 读不到的走 Root；测试用假实现。 */
internal interface FileReadBackend {
    /** 解析来源（句柄、URI、绝对路径），判定类型；文件不存在返回 null。 */
    fun resolve(file: String): ResolvedFile?

    fun readText(file: String, offsetLine: Int, limitLines: Int): TextRead

    /** 读图并编码成模型输入；App 读不到时用 Root 复制到缓存再读。读不出图片时抛 ToolFailure。 */
    fun readImage(file: String): ImageRead
}

/** §F.28 file_read：读文件并转成模型可理解的内容，按类型自动处理。 */
internal class FileReadTool(
    private val backend: FileReadBackend,
) : ToolContract<FileReadInput, FileReadOutput> {
    override val name = "file_read"
    override val domain = ToolDomain.FILE
    override val summary =
        "读文件：文本按行返回并给续读 offset；图片直接附给模型（需模型支持看图）。没有扩展名的文件按内容判断是文本还是图片；" +
            "PDF、视频、音频暂不支持。file 可传 file_search 的句柄、附件 URI 或绝对路径。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("file", "句柄、附件 URI、绝对路径、file://、content://", required = true, maxLength = 1024)
        integer("offset", "文本起始行号（从 1 开始），仅文本", min = 1)
        integer("limit", "文本返回行数，仅文本", min = 1, max = 20_000)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): FileReadInput = FileReadInput(
        file = args.nonBlank("file"),
        offsetLine = args.intOrNull("offset"),
        limitLines = args.intOrNull("limit"),
    )

    /**
     * 开关按来源管（定义清单 §0.5，见 [execute]）：读任意路径要「终端与文件」，读句柄和附件要「读取敏感信息」。
     * 两个都关着时什么都读不了，不进目录（以前仍在目录里，调用了才报 DISABLED）。
     * 只关「终端与文件」时还能读 file_search 的句柄和附件，留在目录里。
     */
    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.switches.terminal || env.switches.sensitiveRead) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(ToolErrorCode.DISABLED, "读取文件需要在 设置 → 工具 里开启「终端与文件」或「读取敏感信息」")
        }

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

        validateParams(input, resolved.kind)

        // PDF、视频、音频没有实现读取：schema 不再暴露 pages/mode/frames，这里如实回不支持。
        return when (resolved.kind) {
            FileKind.TEXT -> readTextVerdict(input, resolved)
            FileKind.IMAGE -> readImageVerdict(resolved, ctx.env)
            FileKind.PDF -> unsupported("暂不支持读取 PDF", "pdf_unsupported")
            FileKind.VIDEO -> unsupported("暂不支持读取视频", "video_unsupported")
            FileKind.AUDIO -> unsupported("暂不支持读取或转写音频", "audio_unsupported")
            FileKind.UNKNOWN -> unsupported("文件内容既不是文本也不是可识别的图片，无法读取", "unsupported_format")
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
        return Verdict.Read(FileReadOutput(data))
    }

    private fun readImageVerdict(
        resolved: ResolvedFile,
        env: ToolEnvironment,
    ): Verdict<FileReadOutput> {
        if (ModelInput.IMAGE !in env.modelInputs) {
            return unsupported("当前模型不支持图片输入", "model_no_vision")
        }
        val read = backend.readImage(resolved.path)
        // image_attached 只在真的附了图时出现：图片经 images() 随本回合交给模型。
        val data = JSONObject()
            .put("kind", "image")
            .put("path", resolved.path)
            .put("width", read.width)
            .put("height", read.height)
            .put("image_attached", true)
        return Verdict.Read(FileReadOutput(data, image = read.image))
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

    /** 图片作为本回合图片附给模型（与 ui_observe 截图、browser_read 截图同一条路）。 */
    override fun images(output: FileReadOutput): List<AgentModelClient.ModelImage> = listOfNotNull(output.image)

    /** 不适用参数不静默忽略（定义清单 §28）。 */
    private fun validateParams(input: FileReadInput, kind: FileKind) {
        fun reject(param: String): Nothing =
            fail(ToolErrorCode.INVALID_ARGUMENTS, "参数 $param 只适用于文本文件，当前是 ${kind.name.lowercase()}")
        if (input.offsetLine != null && kind != FileKind.TEXT) reject("offset")
        if (input.limitLines != null && kind != FileKind.TEXT) reject("limit")
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
