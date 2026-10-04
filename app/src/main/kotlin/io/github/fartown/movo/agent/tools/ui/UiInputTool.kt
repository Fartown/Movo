package io.github.fartown.movo.agent.tools.ui

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
 * §13 ui_input（回读文字）。向输入框写文字：append 在光标插入，replace 替换全部（空即清空）。
 * 写入方式（set_text/paste）由后端选，不改变 append/replace 的目标语义。
 * - 回读一致 → Done(ReadBack("text"))；密码框 / 手机号自动加空格等不一致 → Dispatched + 回读长度。
 * - append 无法插入（光标不可靠、密码框）→ NOT_ACTIONABLE / OUTCOME_UNKNOWN，**不自动降级为 replace**。
 * - submit 是独立送达动作：文字回读一致不代表消息已发送。
 * - 粘贴回退会改剪贴板 → 额外占用 CLIPBOARD 资源。
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
) : ToolOutput

internal class UiInputTool(
    private val registry: UiObservationRegistry,
    private val backend: UiActionBackend,
) : ToolContract<UiInputInput, UiInputOutput> {
    override val name = "ui_input"
    override val domain = ToolDomain.UI
    override val summary =
        "向输入框写文字：默认写当前焦点，可用 index 指定。mode=append 光标插入，replace 替换全部（空即清空）。" +
            "回读一致才算证实；submit 是独立动作，不代表已发送。"

    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.accessibilityUsable || env.rootAvailable) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(ToolErrorCode.PERMISSION_REQUIRED, "需要无障碍权限才能输入")
        }

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("text", "要写入的文字 0–20000；append 时非空", required = true, maxLength = 20000)
        string(
            "mode", "写入语义：append 光标插入（默认）、replace 替换全部",
            enum = UiInputMode.entries.map { it.name.lowercase() },
        )
        integer("index", "输入框节点 index（可选，省略写当前焦点）", min = 0)
        string("observation_id", "给出 index 时必填，绑定其观察代际", maxLength = 64)
        boolean("submit", "写后执行回车/输入法提交动作")
        string("effect", "声明动作后果（只增加确认）", enum = UiEffect.entries.map { it.name.lowercase() })
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
        if (element != null) {
            target = TargetIdentity.Observed(element.observationId, registry.genOf(element.observationId) ?: -1L, element.index)
            pkg = registry.observationPackage(element.observationId)
        } else {
            // 写当前焦点：仍是“有目标”动作（Named），用前台包做自我保护与归因。
            target = TargetIdentity.Named("focus", "")
            pkg = registry.foregroundPackage()
        }
        return buildUiActionResolution(
            backend = backend.backend(env),
            target = target,
            pkg = pkg,
            selfPackage = registry.selfPackage,
            // 写入焦点/指定节点都读得到该可编辑节点。
            readableTarget = true,
            effect = input.effect,
            selfProtect = true,
            // sensitivity=按目标（§1 表）；无法在 resolve 判定密码框，保守取 NORMAL，回读时对敏感内容打码。
            sensitivity = Sensitivity.NORMAL,
            // 粘贴回退占用剪贴板。
            extraResources = setOf(ResourceKey(ToolResource.CLIPBOARD)),
        )
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
            is UiInputResult.Written ->
                if (result.readbackMatches) {
                    // 文字已回读证实在输入框里（submit 另行报告，不等于已发送）。
                    Verdict.Done(
                        UiInputOutput(
                            result.method, true, result.readbackLength, result.submitted,
                            result.afterPackage, result.windowChanged,
                        ),
                        Evidence.ReadBack("text_len=${result.readbackLength}"),
                    )
                } else {
                    // 密码框/自动格式化：回读不一致 → 送达型，给回读长度，不冒领证实。
                    Verdict.Dispatched(
                        UiInputOutput(
                            result.method, false, result.readbackLength, result.submitted,
                            result.afterPackage, result.windowChanged,
                        ),
                    )
                }
            is UiInputResult.NotActionable -> Verdict.Failed(
                ToolError(
                    ToolErrorCode.NOT_ACTIONABLE,
                    "无法写入：${result.reason}",
                    hint = "append 无法插入时不要改用 replace；重新观察或换目标",
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

    override fun renderForModel(output: UiInputOutput): ModelContent = ModelContent.Json(
        JSONObject()
            .put("method", output.method)
            .put("text_verified", output.textVerified)
            .put("readback_length", output.readbackLength)
            .put("submitted", output.submitted)
            .put("after", afterJson(output.packageName, output.windowChanged)),
    )
}
