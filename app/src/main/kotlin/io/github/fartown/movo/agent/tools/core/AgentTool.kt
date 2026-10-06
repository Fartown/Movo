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

/** 数据敏感度，决定持久化脱敏与展示遮盖。 */
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
     * 这一步有没有后果（声明的发送 / 付款、Root 命令、改系统设置等）；有后果时返回确认卡文案。
     * 返回 null 时由管线按风险等级兜底：风险为 EXTERNAL 的一律算有后果。
     * 是否真的弹卡由权限模式决定（YOLO 不弹，手动审批弹）。
     */
    fun approval(args: ToolArgs, ctx: ToolContext): ApprovalNeed? = null

    /** 审批卡片上的动作说明。 */
    fun approvalTitle(args: ToolArgs): String = name

    /** 执行。超时由工具自己处理：只读超时返回 TIMEOUT，动作超时返回 OUTCOME_UNKNOWN。 */
    /** 执行卡这一步的标题（动作 + 对象）；为空时界面用通用叫法。 */
    fun stepTitle(args: ToolArgs, env: ToolEnvironment): String? = null

    fun execute(args: ToolArgs, ctx: ToolContext): ToolOutcome
}

/** 一个领域的工具集合，以及这个领域在系统提示中的用法分节。 */
internal interface ToolProvider : AutoCloseable {
    val tools: List<AgentTool>
    val promptSection: PromptSection? get() = null
    /** 按当前环境给出用法分节；分节里提到的工具不可用时，提供方可以去掉相应的句子。 */
    fun promptSection(env: ToolEnvironment): PromptSection? = promptSection
    /** 分节讲的是所有工具通用的规则（如工具结果怎么读）：只要有任何工具可用就注入，不看本领域工具是否可用。 */
    val promptSectionCoversAllTools: Boolean get() = false
    override fun close() = Unit
}

internal data class PromptSection(
    val id: String,
    val domain: ToolDomain,
    val text: String,
)
