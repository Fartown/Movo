package io.github.fartown.movo.agent.tools.core

import org.json.JSONObject

/** 模型视角的领域：决定命名前缀、系统提示分节与设置分组。 */
internal enum class ToolDomain {
    DEVICE,
    APP,
    UI,
    CLOCK_MEDIA,
    PERSONAL,
    FILE,
    TERMINAL,
    BROWSER,
    MEMORY,
    SKILL,
    MCP,
    META,
}

/** 执行后果，决定审批。 */
internal enum class Risk {
    /** 只读。 */
    READ,
    /** 本机可逆写：GUI 一般动作、写剪贴板、写工作区文件、开关 Wi‑Fi。 */
    LOCAL,
    /** 对外或不可逆：发送、支付、删除、安装、改系统设置、冻结应用、写持久记忆。 */
    EXTERNAL,
}

/** 数据敏感度，决定持久化脱敏、展示遮盖与污点标记。 */
internal enum class Sensitivity {
    NORMAL,
    /** 个人数据：结果不进入持久会话。 */
    PRIVATE,
    /** 凭据、验证码、密码类：结果与参数都不进入持久会话。 */
    SECRET,
    ;

    val persistsRaw: Boolean get() = this == NORMAL
}

/** 需要独占的资源；同一资源上的调用串行执行。 */
internal enum class ToolResource {
    SCREEN,
    CLIPBOARD,
    TERMINAL,
    BROWSER,
    MEMORY,
    SKILLS,
    CONVERSATION,
    /** 媒体/音频：media_control 等按它串行，避免并发派发媒体键。 */
    AUDIO,
    /** 设备无线电/系统设置/应用状态：device_toggle、setting_write、app_control 按它串行。 */
    DEVICE,
}

internal sealed interface Concurrency {
    data object Parallel : Concurrency
    data class Exclusive(val resource: ToolResource) : Concurrency
}

internal enum class Exposure {
    /** 满足可用条件时进入每轮目录。 */
    RESIDENT,
    /** 通过 tool_search 加载后进入目录。 */
    DEFERRED,
}

/** 工具在当前环境下是否可用；不可用时附带原因码，供 tool_search 与界面说明。 */
internal sealed interface ToolAvailability {
    data object Available : ToolAvailability
    data class Unavailable(val code: ToolErrorCode, val reason: String) : ToolAvailability
}

/**
 * 一个工具的执行侧定义。只描述执行语义，不包含任何界面展示（图标、标题、卡片），
 * 展示由界面侧的登记表负责。
 */
internal interface AgentTool {
    val name: String
    val domain: ToolDomain
    val exposure: Exposure get() = Exposure.RESIDENT

    /** 给模型的描述：只写契约（做什么、返回什么、关键上限），1–3 句。 */
    val description: String

    /** 按当前环境投影参数 Schema（例如只列出可用的 source、section）。 */
    fun parameters(env: ToolEnvironment): JSONObject

    fun availability(env: ToolEnvironment): ToolAvailability = ToolAvailability.Available

    fun risk(args: ToolArgs, env: ToolEnvironment): Risk
    fun sensitivity(args: ToolArgs, env: ToolEnvironment): Sensitivity = Sensitivity.NORMAL
    fun concurrency(args: ToolArgs, env: ToolEnvironment): Concurrency = Concurrency.Parallel

    /**
     * 工具自己对审批的诉求（受保护应用、声明的后果、root 命令等）。返回 null 时由管线按风险等级和
     * 污点状态决定：风险为 EXTERNAL 时一律确认。工具只能增加确认，不能免除管线的确认。
     */
    fun approval(args: ToolArgs, ctx: ToolContext): ApprovalNeed? = null

    /** 审批卡片上的动作说明。 */
    fun approvalTitle(args: ToolArgs): String = name

    /**
     * 执行成功后给本轮打哪类污点（实施方案 5.1）。默认不打；读到不可信内容或个人数据的工具才声明。
     * 结果敏感度只决定脱敏，不再决定污点：终端输出、写记忆等工具自己的结果不污染后续调用。
     */
    fun taintKinds(args: ToolArgs, outcome: ToolOutcome): Set<TaintKind> = emptySet()

    /** 执行。超时由工具自己处理：只读超时返回 TIMEOUT，动作超时返回 OUTCOME_UNKNOWN。 */
    fun execute(args: ToolArgs, ctx: ToolContext): ToolOutcome
}

/** 一个领域的工具集合，以及这个领域在系统提示中的用法分节。 */
internal interface ToolProvider : AutoCloseable {
    val tools: List<AgentTool>
    val promptSection: PromptSection? get() = null
    /** 按当前环境给出用法分节；分节里提到的工具不可用时，提供方可以去掉相应的句子。 */
    fun promptSection(env: ToolEnvironment): PromptSection? = promptSection
    override fun close() = Unit
}

internal data class PromptSection(
    val id: String,
    val domain: ToolDomain,
    val text: String,
)
