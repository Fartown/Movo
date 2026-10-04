package io.github.fartown.movo.agent.tools.ui

import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.Evidence
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.ResourceKey
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
import io.github.fartown.movo.agent.tools.core.ToolResource
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.invalidArgs
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONObject

// ===========================================================================
// §16 clipboard_read（只读）
// ===========================================================================

internal data class ClipboardReadInput(val unused: Boolean = true) : ToolInput

internal data class ClipboardReadOutput(
    val text: String,
    val truncated: Boolean,
    val sensitive: Boolean,
) : ToolOutput

internal sealed interface ClipboardReadResult {
    data class Text(val text: String, val truncated: Boolean, val sensitive: Boolean) : ClipboardReadResult
    /** 剪贴板为空（与“被拒”区分）。 */
    data object Empty : ClipboardReadResult
    /** Movo 不在前台，系统拒绝读取。 */
    data object Rejected : ClipboardReadResult
    data object Unavailable : ClipboardReadResult
}

/** 可测后端：真实实现查 ClipboardManager；测试用假实现。 */
internal interface ClipboardReadBackend {
    fun read(): ClipboardReadResult
}

internal class ClipboardReadTool(
    private val backend: ClipboardReadBackend,
) : ToolContract<ClipboardReadInput, ClipboardReadOutput> {
    override val name = "clipboard_read"
    override val domain = ToolDomain.UI
    override val summary =
        "读系统剪贴板文本（最多 8000 字）。Movo 不在前台时系统可能拒绝（与‘空’区分）。"

    /** 受“敏感读取”开关控制（§16）。 */
    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.switches.sensitiveRead) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(ToolErrorCode.DISABLED, "已关闭敏感读取")
        }

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema { }

    override fun parse(args: ToolArgs, env: ToolEnvironment): ClipboardReadInput = ClipboardReadInput()

    override fun resolve(input: ClipboardReadInput, env: ToolEnvironment): CallResolution = CallResolution(
        risk = Risk.READ,
        // private；EXTRA_IS_SENSITIVE 的内容实际是 secret——见报告：无法在 resolve 前得知，SECRET 升级待 core 钩子。
        sensitivity = Sensitivity.PRIVATE,
        resources = setOf(ResourceKey(ToolResource.CLIPBOARD)),
    )

    override fun execute(input: ClipboardReadInput, resolution: CallResolution, ctx: ToolContext): Verdict<ClipboardReadOutput> {
        ctx.checkCancelled()
        return when (val result = backend.read()) {
            is ClipboardReadResult.Text -> Verdict.Read(
                ClipboardReadOutput(result.text, result.truncated, result.sensitive),
            )
            is ClipboardReadResult.Empty -> Verdict.Read(ClipboardReadOutput("", truncated = false, sensitive = false))
            is ClipboardReadResult.Rejected -> Verdict.Failed(
                ToolError(
                    ToolErrorCode.SYSTEM_REJECTED,
                    "Movo 不在前台，系统拒绝读取剪贴板",
                    hint = "把 Movo 切到前台后再读",
                ),
            )
            is ClipboardReadResult.Unavailable -> Verdict.Failed(
                ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "剪贴板暂不可读"),
            )
        }
    }

    override fun renderForModel(output: ClipboardReadOutput): ModelContent =
        ModelContent.Json(JSONObject().put("text", output.text).put("truncated", output.truncated))
}

// ===========================================================================
// §17 clipboard_write（回读/送达）
// ===========================================================================

internal data class ClipboardWriteInput(val text: String, val sensitive: Boolean) : ToolInput

internal data class ClipboardWriteOutput(val chars: Int) : ToolOutput

internal sealed interface ClipboardWriteResult {
    /** 写后回读一致 → Done。 */
    data object ReadBackOk : ClipboardWriteResult
    /** 后台回读受限，无法证实 → Dispatched。 */
    data object ReadBackLimited : ClipboardWriteResult
    data object Rejected : ClipboardWriteResult
}

internal interface ClipboardWriteBackend {
    fun write(text: String, sensitive: Boolean): ClipboardWriteResult
}

internal class ClipboardWriteTool(
    private val backend: ClipboardWriteBackend,
) : ToolContract<ClipboardWriteInput, ClipboardWriteOutput> {
    override val name = "clipboard_write"
    override val domain = ToolDomain.UI
    override val summary =
        "把文本写入系统剪贴板（1–20000 字）。往输入框写字请用 ui_input。sensitive=true 时 Android 13+ 隐藏预览。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("text", "要写入的文本 1–20000 字", required = true, minLength = 1, maxLength = 20000)
        boolean("sensitive", "Android 13+ 隐藏剪贴板预览")
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): ClipboardWriteInput {
        val text = args.string("text")
        val len = text.codePointCount(0, text.length)
        if (len < 1) invalidArgs("text 不能为空")
        if (len > 20000) invalidArgs("text 超过 20000 字")
        return ClipboardWriteInput(text, args.bool("sensitive", false))
    }

    override fun resolve(input: ClipboardWriteInput, env: ToolEnvironment): CallResolution = CallResolution(
        risk = Risk.LOCAL,
        sensitivity = Sensitivity.NORMAL,
        resources = setOf(ResourceKey(ToolResource.CLIPBOARD)),
    )

    override fun execute(input: ClipboardWriteInput, resolution: CallResolution, ctx: ToolContext): Verdict<ClipboardWriteOutput> {
        ctx.checkCancelled()
        val chars = input.text.codePointCount(0, input.text.length)
        return when (backend.write(input.text, input.sensitive)) {
            is ClipboardWriteResult.ReadBackOk -> Verdict.Done(
                ClipboardWriteOutput(chars), Evidence.ReadBack("chars=$chars"),
            )
            is ClipboardWriteResult.ReadBackLimited -> Verdict.Dispatched(ClipboardWriteOutput(chars))
            is ClipboardWriteResult.Rejected -> Verdict.Failed(
                ToolError(ToolErrorCode.SYSTEM_REJECTED, "系统拒绝写入剪贴板"),
            )
        }
    }

    override fun renderForModel(output: ClipboardWriteOutput): ModelContent =
        ModelContent.Json(JSONObject().put("chars", output.chars))
}
