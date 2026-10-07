package io.github.fartown.movo.agent.tools.ui

import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.ApprovalCategory
import io.github.fartown.movo.agent.tools.core.ApprovalNeed
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.Evidence
import io.github.fartown.movo.agent.tools.core.InjectionBackend
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.ResourceKey
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.TargetIdentity
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

/**
 * §13 ui_input（回读文字）。向输入框写文字：append 接在已有内容末尾，replace 整段替换（空即清空）；
 * 密码框总是整段写入。写入方式（直接设置文字 / 粘贴）由后端选，不改变 append/replace 的目标语义。
 * - 回读一致 → Done(ReadBack("text"))；读回的和写入的不一致（自动格式化、限长、编辑器改了换行）→ Dispatched，
 *   结果里给出输入框里实际的文字，让模型据此判断，不用再观察一次；密码框读不回 → Dispatched。
 * - append 读不到原文（不知道接在哪）→ NOT_ACTIONABLE，**不自动降级为 replace**（免得覆盖原有内容）；
 *   提示模型：框是空的就用 replace，要保留原文就先看清原文再用 replace 写完整内容。
 * - submit 是独立送达动作：文字回读一致不代表消息已发送；没提交成功时给出原因。
 * - 粘贴会临时改剪贴板 → 额外占用 CLIPBOARD 资源。
 */

internal data class UiInputInput(
    val text: String,
    val mode: UiInputMode,
    val element: UiTarget.Element?,
    val submit: Boolean,
    val effect: UiEffect?,
) : ToolInput

internal data class UiInputOutput(
    val method: String,
    val textVerified: Boolean,
    val readbackLength: Int,
    val submitted: Boolean,
    val packageName: String?,
    val windowChanged: Boolean,
    /** 读回的实际文字（和写入的不一致时）。 */
    val readback: String? = null,
    val clipboardWritten: Boolean = false,
    val submitError: String? = null,
) : ToolOutput

internal class UiInputTool(
    private val registry: UiObservationRegistry,
    private val backend: UiActionBackend,
) : ToolContract<UiInputInput, UiInputOutput> {
    override val name = "ui_input"
    override val domain = ToolDomain.UI
    override val summary =
        "向输入框写文字：默认写当前焦点，可用 index 指定。mode=append 接在框里已有内容末尾，replace 整段替换（空即清空）；" +
            "框是空的、密码框，或读不到原文时用 replace。回读一致才算证实；不一致时结果里给出框里实际的文字。" +
            "submit 是独立动作，不代表已发送。"

    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.accessibilityUsable || env.rootAvailable) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(ToolErrorCode.PERMISSION_REQUIRED, "需要无障碍权限才能输入")
        }

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("text", "要写入的文字 0–20000；append 时非空", required = true, maxLength = 20000)
        string(
            "mode", "写入语义：append 接在已有内容末尾（默认）、replace 整段替换；密码框总是整段写入",
            enum = UiInputMode.entries.map { it.name.lowercase() },
        )
        integer("index", "输入框节点 index（可选，省略写当前焦点）；append 和 replace 都按它写", min = 0)
        string("observation_id", "给出 index 时必填，绑定其观察代际", maxLength = 64)
        boolean("submit", "写后执行回车/输入法提交动作")
        string("effect", "这一步的后果：带 submit 会发送或提交时声明", enum = UiEffect.entries.map { it.name.lowercase() })
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): UiInputInput {
        val text = args.string("text")
        if (text.codePointCount(0, text.length) > 20000) {
            invalidArgs("text 超过 20000 字", "分多次写入")
        }
        val mode = args.enum("mode", UiInputMode.APPEND)
        if (mode == UiInputMode.APPEND && text.isEmpty()) {
            invalidArgs("append 模式 text 不能为空", "清空请用 mode=replace")
        }
        val element = if (args.has("index")) {
            val obs = args.stringOrNull("observation_id")
                ?: invalidArgs("给出 index 时必须提供 observation_id")
            UiTarget.Element(obs, args.int("index"))
        } else {
            null
        }
        return UiInputInput(
            text = text,
            mode = mode,
            element = element,
            submit = args.bool("submit", false),
            effect = if (args.has("effect")) args.enum<UiEffect>("effect") else null,
        )
    }

    override fun resolve(input: UiInputInput, env: ToolEnvironment): CallResolution {
        val element = input.element
        val target: TargetIdentity
        val pkg: String?
        val password: Boolean
        var stale: ToolError? = null
        if (element != null) {
            val gen = registry.genOf(element.observationId) ?: -1L
            target = TargetIdentity.Observed(element.observationId, gen, element.index)
            pkg = registry.observationPackage(element.observationId)
            stale = genError(registry, element.observationId, gen)
            password = registry.observedNode(element.observationId, element.index)?.password == true
        } else {
            // 写当前焦点：仍是“有目标”动作（Named），用前台包做自我保护与归因。
            target = TargetIdentity.Named("focus", "")
            pkg = registry.foregroundPackage()
            password = registry.focusedInputIsPassword() == true
        }
        val resolution = buildUiActionResolution(
            backend = backend.backend(env),
            target = target,
            pkg = pkg,
            selfPackage = registry.selfPackage,
            // 写入焦点/指定节点都读得到该可编辑节点。
            readableTarget = true,
            effect = input.effect,
            selfProtect = true,
            // 密码框按机密处理（结果在历史里脱敏）；其余取 NORMAL，回读时对敏感内容打码。
            sensitivity = if (password) Sensitivity.SECRET else Sensitivity.NORMAL,
            // 粘贴回退占用剪贴板。
            extraResources = setOf(ResourceKey(ToolResource.CLIPBOARD)),
            stale = stale,
            action = inputAction(input, password),
        )
        // 往密码框输入归为「输入密码」，优先于模型声明的后果（手动审批时固定会问）。
        if (!password || resolution.reject != null) return resolution
        return resolution.copy(
            category = ApprovalCategory.PASSWORD,
            toolApproval = ApprovalNeed(
                category = ApprovalCategory.PASSWORD,
                title = "在「${appLabel(pkg)}」里输入密码？",
                detail = "下一步：${inputAction(input, password)}",
                appPackage = pkg,
            ),
        )
    }

    /** 确认卡上的这一步：「输入「明天见」」，带 submit 时「输入「明天见」并提交」；密码不上卡。 */
    private fun inputAction(input: UiInputInput, password: Boolean): String {
        val oneLine = input.text.replace(Regex("\\s+"), " ").trim()
        val preview = if (oneLine.length > 20) oneLine.take(20) + "…" else oneLine
        val typed = when {
            preview.isEmpty() -> "清空输入框"
            password -> "在密码框输入 ${input.text.length} 个字符"
            else -> "输入「$preview」"
        }
        return if (input.submit) "${typed}并提交" else typed
    }

    override fun execute(input: UiInputInput, resolution: CallResolution, ctx: ToolContext): Verdict<UiInputOutput> {
        ctx.checkCancelled()
        if (resolution.backend == InjectionBackend.NONE) {
            return Verdict.Failed(ToolError(ToolErrorCode.PERMISSION_REQUIRED, "无障碍不可用，无法输入"))
        }
        (resolution.target as? TargetIdentity.Observed)?.let {
            checkGen(registry, it.observationId, it.gen)?.let { stale -> return stale }
        }
        val result = backend.input(
            UiInputRequest(input.text, input.mode, input.element, input.submit, resolution.backend), ctx.env,
        )
        return when (result) {
            is UiInputResult.Written -> {
                val output = UiInputOutput(
                    result.method, result.readbackMatches, result.readbackLength, result.submitted,
                    result.afterPackage, result.windowChanged,
                    readback = result.readback.takeUnless { result.readbackMatches },
                    clipboardWritten = result.clipboardWritten,
                    submitError = result.submitError,
                )
                if (result.readbackMatches) {
                    // 文字已回读证实在输入框里（submit 另行报告，不等于已发送）。
                    Verdict.Done(output, Evidence.ReadBack("text_len=${result.readbackLength}"))
                } else {
                    // 读回不一致（自动格式化、限长、换行被改）或密码框读不回：送达型，带上实际文字，不冒领证实。
                    Verdict.Dispatched(output)
                }
            }
            is UiInputResult.NotActionable -> Verdict.Failed(
                ToolError(
                    ToolErrorCode.NOT_ACTIONABLE,
                    "无法写入：${result.reason}",
                    hint = notActionableHint(result.code),
                    detail = result.code.ifBlank { null },
                ),
            )
            is UiInputResult.OutcomeUnknown -> Verdict.Unknown(
                reason = "写入已派发但无法确认", next = "先 ui_observe 查看输入框内容，不要直接重复",
            )
            is UiInputResult.SystemRejected -> Verdict.Failed(
                ToolError(ToolErrorCode.SYSTEM_REJECTED, "系统拒绝写入，确定未执行"),
            )
            is UiInputResult.PermissionRequired -> Verdict.Failed(
                ToolError(ToolErrorCode.PERMISSION_REQUIRED, "无障碍不可用，无法输入"),
            )
        }
    }

    /**
     * 标题写输入的内容；密码框（或判断不了是不是密码框）只写字数，密码不能出现在执行卡和对话记录里。
     * 只查进程内的观察记录和当前焦点，不抓新树。
     */
    override fun uiTitle(input: UiInputInput): String {
        val password = input.element?.let { registry.observedNode(it.observationId, it.index)?.password }
            ?: registry.focusedInputIsPassword()
        val oneLine = input.text.replace(Regex("\\s+"), " ").trim()
        val typed = when {
            oneLine.isEmpty() -> "清空输入框"
            password == true -> "在密码框输入 ${input.text.length} 个字符"
            password == null -> "输入 ${input.text.length} 个字"
            else -> "输入「${oneLine.forTitle()}」"
        }
        return if (input.submit) "${typed}并提交" else typed
    }

    /** 没写进去时告诉模型下一步：按后端细分码给出路，和报错正文一致。 */
    private fun notActionableHint(code: String): String = when (code) {
        "NO_FOCUSED_EDITABLE" -> "先 ui_tap 点一下输入框让它获得焦点，或用 ui_observe 的 index 指定输入框"
        "TEXT_CONTENT_UNAVAILABLE" ->
            "框是空的就用 mode=replace；要保留原有内容，先 ui_observe 看清原文，再用 mode=replace 写入完整内容"
        "NOT_EDITABLE" -> "这个节点不能输入，换一个可编辑的输入框"
        "TEXT_TOO_LONG" -> "分几次用 mode=append 写入"
        "TEXT_INPUT_REJECTED" -> "这个输入框不接受写入；可以先 ui_tap 点开它再试，或换个入口"
        else -> "重新 ui_observe 看看输入框的状态，再决定怎么写"
    }

    override fun renderForUi(input: UiInputInput, output: UiInputOutput): ToolUiView {
        val verb = when {
            output.textVerified -> "已输入"
            output.readback != null -> "已输入（框里显示的和写入的不完全一样）"
            else -> "已输入（读不回来，没法核对）"
        }
        val submitted = if (output.submitted) "$verb · 已提交" else verb
        return ToolUiView(summary = afterSummary(submitted, output.packageName, output.windowChanged))
    }

    override fun renderForModel(output: UiInputOutput): ModelContent = ModelContent.Json(
        JSONObject()
            .put("method", output.method)
            .put("text_verified", output.textVerified)
            .put("readback_length", output.readbackLength)
            .apply {
                // 输入框里实际的文字（和写入的不一致时）：模型据此判断要不要改，不用再观察一次。
                output.readback?.let { put("readback", clipReadback(it)) }
                if (output.clipboardWritten) put("clipboard_written", true)
                output.submitError?.let { put("submit_error", it) }
            }
            .put("submitted", output.submitted)
            .put("after", afterJson(output.packageName, output.windowChanged)),
    )

    /** 读回的文字太长时只留首尾，中间写明省略了多少字。 */
    private fun clipReadback(text: String): String =
        if (text.length <= READBACK_MAX_CHARS) {
            text
        } else {
            val half = READBACK_MAX_CHARS / 2
            text.take(half) + "…（中间省略 ${text.length - half * 2} 字）…" + text.takeLast(half)
        }

    private companion object {
        const val READBACK_MAX_CHARS = 600
    }
}
