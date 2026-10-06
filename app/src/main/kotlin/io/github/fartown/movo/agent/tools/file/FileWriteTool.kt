package io.github.fartown.movo.agent.tools.file

import io.github.fartown.movo.agent.tools.core.TerminalBody
import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.fileName
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.uiBytes
import io.github.fartown.movo.agent.tools.core.uiTime
import io.github.fartown.movo.agent.tools.core.ApprovalCategory
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.Evidence
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
import io.github.fartown.movo.agent.tools.core.fail
import io.github.fartown.movo.agent.tools.core.objectSchema
import io.github.fartown.movo.agent.tools.core.ApprovalPreview
import org.json.JSONObject

internal enum class FileWriteMode { OVERWRITE, APPEND }

internal data class FileWriteInput(
    val path: String,
    val content: String,
    val mode: FileWriteMode,
) : ToolInput

internal data class FileWriteOutput(
    val path: String,
    val bytesWritten: Long,
    val created: Boolean,
    val evidenceText: String,
) : ToolOutput

internal data class FileWriteResult(
    val bytesWritten: Long,
    /** 回读内容哈希（优先证据）；拿不到则为 null，退回 size 回读。 */
    val sha256Hex: String?,
    /** 回读到的文件大小（字节）。 */
    val verifiedSize: Long,
)

/** 可测后端：真实实现写工作区 / 共享存储 / Root 路径；测试用假实现。 */
internal interface FileWriteBackend {
    fun exists(path: String): Boolean

    /** 写入并回读；写不成功抛 ToolFailure。 */
    fun write(path: String, content: String, append: Boolean): FileWriteResult
}

/** §F.29 file_write：写文本文件（覆盖或追加），自动建父目录。写工作区以外的路径归为「写工作区以外的文件」。 */
internal class FileWriteTool(
    private val backend: FileWriteBackend,
) : ToolContract<FileWriteInput, FileWriteOutput> {
    override val name = "file_write"
    override val domain = ToolDomain.FILE
    override val summary =
        "写文本文件：mode 选 overwrite 或 append，自动建父目录。写完回读校验。相对路径写在工作区内。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("path", "目标路径（相对为工作区内；绝对路径按分区判定风险）", required = true, maxLength = 1024)
        string("content", "要写入的文本，≤512KiB", required = true)
        string("mode", "写入方式，默认 overwrite", enum = FileWriteMode.entries.map { it.name.lowercase() })
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): FileWriteInput {
        val content = args.string("content")
        if (content.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            fail(ToolErrorCode.TOO_LARGE, "写入内容超过 512KiB", hint = "分多次写或改用 terminal_run")
        }
        return FileWriteInput(
            path = args.nonBlank("path"),
            content = content,
            mode = args.enum<FileWriteMode>("mode", FileWriteMode.OVERWRITE),
        )
    }

    override fun resolve(input: FileWriteInput, env: ToolEnvironment): CallResolution {
        val zone = FileSupport.zoneOf(input.path)
        val append = input.mode == FileWriteMode.APPEND
        // 工作区内可逆写=LOCAL；共享存储覆盖=LOCAL、追加=EXTERNAL；工作区与共享存储以外=EXTERNAL。
        val risk = when (zone) {
            FileSupport.PathZone.WORKSPACE -> Risk.LOCAL
            FileSupport.PathZone.SHARED -> if (append) Risk.EXTERNAL else Risk.LOCAL
            FileSupport.PathZone.EXTERNAL -> Risk.EXTERNAL
        }
        return CallResolution(
            risk = risk,
            sensitivity = FileSupport.sensitivityOf(input.path),
            resources = emptySet(),
            category = if (zone == FileSupport.PathZone.WORKSPACE) null else ApprovalCategory.FILES,
        )
    }

    override fun approvalPreview(input: FileWriteInput): ApprovalPreview = ApprovalPreview(
        title = if (input.mode == FileWriteMode.APPEND) "往工作区以外的文件末尾追加内容？" else "写入工作区以外的文件？",
        detail = input.path.take(160),
    )

    override fun execute(
        input: FileWriteInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<FileWriteOutput> {
        if (!ctx.env.switches.terminal) {
            return Verdict.Failed(ToolError(ToolErrorCode.DISABLED, "写文件需要开启「文件与终端」开关"))
        }
        ctx.checkCancelled()
        val existedBefore = backend.exists(input.path)
        val result = backend.write(input.path, input.content, append = input.mode == FileWriteMode.APPEND)

        // 回读证实：优先内容哈希，否则回读 size（定义清单 §29 验证证据）。
        val evidence = if (result.sha256Hex != null) {
            Evidence.ContentHash("SHA-256", result.sha256Hex)
        } else {
            Evidence.ReadBack("size=${result.verifiedSize}")
        }
        val output = FileWriteOutput(
            path = input.path,
            bytesWritten = result.bytesWritten,
            created = !existedBefore,
            evidenceText = when (evidence) {
                is Evidence.ContentHash -> "sha256=${result.sha256Hex}"
                else -> "size=${result.verifiedSize}"
            },
        )
        return Verdict.Done(output, evidence)
    }

    override fun uiTitle(input: FileWriteInput): String =
        (if (input.mode == FileWriteMode.APPEND) "追加到文件 · " else "写入文件 · ") + input.path.fileName().forTitle(30)

    override fun renderForUi(input: FileWriteInput, output: FileWriteOutput): ToolUiView = ToolUiView(
        summary = when {
            output.created -> "新建 · ${uiBytes(output.bytesWritten)}"
            input.mode == FileWriteMode.APPEND -> "追加 ${input.content.lines().size} 行"
            else -> "已覆盖 · ${uiBytes(output.bytesWritten)}"
        },
        blocks = listOf(
            ToolUiBlock.Output(input.content, label = if (input.mode == FileWriteMode.APPEND) "追加的内容 · ${output.path}" else output.path),
        ).filter { input.content.isNotBlank() },
    )

    override fun renderForModel(output: FileWriteOutput): ModelContent = ModelContent.Json(
        JSONObject()
            .put("path", output.path)
            .put("bytes_written", output.bytesWritten)
            .put("created", output.created),
    )

    private companion object {
        const val MAX_BYTES = 512 * 1024
    }
}
