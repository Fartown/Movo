package io.github.fartown.movo.agent.tools.core

import io.github.fartown.movo.agent.model.AgentModelClient
import org.json.JSONObject

/**
 * 工具结果的三种状态。
 *
 * OK 表示工具完成了它承诺的事；动作类工具必须经过确认才能报 OK。
 * ERROR 表示确定没有产生效果（或只读工具失败）。
 * UNKNOWN 表示动作可能已生效但无法确认，模型必须先核实，不得直接重试。
 */
internal enum class ToolStatus(val wire: String) {
    OK("ok"),
    ERROR("error"),
    UNKNOWN("unknown"),
}

/** 模型下一步该怎么做。 */
internal enum class Retry(val wire: String) {
    /** 策略或能力拒绝，换方式绕过也不行，应当告诉用户。 */
    NEVER("never"),
    /** 修正参数后可以重试。 */
    FIX("fix"),
    /** 先重新观察或查询状态，再决定。 */
    OBSERVE("observe"),
    /** 暂时性失败，稍后可以原样重试一次。 */
    LATER("later"),
    /** 需要用户操作（授权、开开关、确认）后才能继续。 */
    USER("user"),
}

/**
 * 全部工具共用的错误码表。原来的细分码进 [ToolError.detail]，只用于日志与界面。
 * 新增码必须同时更新 docs/research/tool-redesign/Movo 工具重构方案.md 第 5.4 节。
 */
internal enum class ToolErrorCode(val retry: Retry, val status: ToolStatus = ToolStatus.ERROR) {
    // 调用层（运行时产生）
    INVALID_ARGUMENTS(Retry.FIX),
    UNKNOWN_TOOL(Retry.FIX),
    CALL_TRUNCATED(Retry.FIX),
    CALL_UNEXPECTED(Retry.FIX),
    INTERRUPTED(Retry.OBSERVE, ToolStatus.UNKNOWN),
    /** 取消且确认未派发；派发后被取消按证据走 unknown/已送达，不追认未执行。 */
    CANCELLED(Retry.NEVER),

    // 可用性与策略（不得绕过）
    DISABLED(Retry.USER),
    /** 策略拒绝：自身界面、锁屏、无交互入口。 */
    POLICY_DENIED(Retry.NEVER),
    PERMISSION_REQUIRED(Retry.USER),
    ROOT_REQUIRED(Retry.NEVER),
    UNSUPPORTED(Retry.NEVER),
    USER_DECLINED(Retry.NEVER),
    APPROVAL_TIMEOUT(Retry.USER),
    /** ask_user 回答超时。 */
    ANSWER_TIMEOUT(Retry.USER),
    BUSY(Retry.LATER),

    // 目标与状态
    NOT_FOUND(Retry.FIX),
    AMBIGUOUS(Retry.FIX),
    STALE_OBSERVATION(Retry.OBSERVE),
    NOT_ACTIONABLE(Retry.OBSERVE),
    CONFLICT(Retry.FIX),
    TOO_LARGE(Retry.FIX),
    LIMIT_REACHED(Retry.FIX),

    // 执行
    TIMEOUT(Retry.LATER),
    OUTCOME_UNKNOWN(Retry.OBSERVE, ToolStatus.UNKNOWN),
    SYSTEM_REJECTED(Retry.FIX),
    SOURCE_UNAVAILABLE(Retry.LATER),
    NETWORK_ERROR(Retry.LATER),
    /** MCP 等外部服务返回错误（取对方原文进 detail）。 */
    EXTERNAL_ERROR(Retry.FIX),
    INTERNAL_ERROR(Retry.LATER),

    /** 循环守卫拦截。 */
    LOOP_DETECTED(Retry.OBSERVE),
    /** 用户在本批动作执行中插话，剩余动作未执行；先看用户补充再决定。 */
    SUPERSEDED(Retry.OBSERVE),
}

internal data class ToolError(
    val code: ToolErrorCode,
    val message: String,
    val hint: String? = null,
    /** 原来的细分码或系统原因，例如 COLOROS_MEMORY_DATABASE_TOO_LARGE、background_start_blocked。 */
    val detail: String? = null,
    val retry: Retry = code.retry,
)

internal data class ToolWarning(
    val code: ToolErrorCode,
    val message: String,
)

/** 截断信息：shown 与 total 的单位由工具自定（字符、条目、行），模型据此决定是否续读。 */
internal data class Truncation(
    val shown: Int,
    val total: Int? = null,
    val unit: String = "chars",
    val nextCursor: String? = null,
    val spillPath: String? = null,
)

/**
 * 工具执行的结构化结果。[data] 是全量结构化结果，界面和日志使用；
 * 给模型的内容由 [ToolProjection] 投影，只保留模型需要的字段。
 */
internal data class ToolOutcome(
    val status: ToolStatus,
    val data: JSONObject? = null,
    val error: ToolError? = null,
    val warnings: List<ToolWarning> = emptyList(),
    val truncation: Truncation? = null,
    val images: List<AgentModelClient.ModelImage> = emptyList(),
    /** 由工具按参数或结果计算的敏感度；为空时取工具声明。 */
    val sensitivity: Sensitivity? = null,
    /**
     * 以“头部 + 正文”纯文本呈现给模型的正文（终端输出等），避免把大段输出塞进 JSON 字符串产生转义膨胀。
     * 为空时用紧凑 JSON 呈现。
     */
    val textBody: String? = null,
    /** 送达/回读区别：Done→true，Dispatched→false，只读/错误为 null（review B2）。 */
    val effectVerified: Boolean? = null,
    /** 验证证据摘要（给 UI/日志；模型投影只用 status）。 */
    val evidence: String? = null,
    /** 执行卡这一步的界面视图（工具可视化方案）；不发给模型。 */
    val view: ToolUiView? = null,
    /**
     * 合同工具给出的原始结果类型：read / done / dispatched / backgrounded / unknown / failed（见 [Verdict]）。
     * 只给运行日志用；不经合同的结果（参数错误、审批拒绝等）为空。
     */
    val verdict: String? = null,
) {
    val isOk: Boolean get() = status == ToolStatus.OK

    fun withWarnings(extra: List<ToolWarning>): ToolOutcome =
        if (extra.isEmpty()) this else copy(warnings = warnings + extra)

    companion object {
        fun ok(
            data: JSONObject = JSONObject(),
            truncation: Truncation? = null,
            images: List<AgentModelClient.ModelImage> = emptyList(),
            warnings: List<ToolWarning> = emptyList(),
            sensitivity: Sensitivity? = null,
            textBody: String? = null,
        ): ToolOutcome = ToolOutcome(
            status = ToolStatus.OK,
            data = data,
            truncation = truncation,
            images = images,
            warnings = warnings,
            sensitivity = sensitivity,
            textBody = textBody,
        )

        fun error(
            code: ToolErrorCode,
            message: String,
            hint: String? = null,
            detail: String? = null,
            data: JSONObject? = null,
            retry: Retry = code.retry,
        ): ToolOutcome = ToolOutcome(
            status = code.status,
            data = data,
            error = ToolError(code, message, hint, detail, retry),
        )

        /** 动作可能已生效但无法确认。 */
        fun unknown(
            message: String,
            hint: String = "先观察或查询确认结果，不要直接重复这个动作",
            detail: String? = null,
            data: JSONObject? = null,
        ): ToolOutcome = error(ToolErrorCode.OUTCOME_UNKNOWN, message, hint, detail, data)
    }
}

/** 工具内部用来提前结束并返回结构化错误；管线会把它转成 [ToolOutcome]。 */
internal class ToolFailure(
    val code: ToolErrorCode,
    override val message: String,
    val hint: String? = null,
    val detail: String? = null,
    val data: JSONObject? = null,
) : RuntimeException(message) {
    fun toOutcome(): ToolOutcome = ToolOutcome.error(code, message, hint, detail, data)
}

internal fun fail(
    code: ToolErrorCode,
    message: String,
    hint: String? = null,
    detail: String? = null,
): Nothing = throw ToolFailure(code, message, hint, detail)

internal fun invalidArgs(message: String, hint: String? = null): Nothing =
    throw ToolFailure(ToolErrorCode.INVALID_ARGUMENTS, message, hint)
