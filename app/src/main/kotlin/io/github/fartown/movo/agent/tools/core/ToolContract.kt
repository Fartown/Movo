package io.github.fartown.movo.agent.tools.core

import org.json.JSONObject

/**
 * 工具子系统的类型化合同（七份合同的代码形态）。文档《Movo 工具子系统合同与架构》描述约定，
 * 这里是权威类型。只定义骨架，先编译通过，再由纵向链路（device_read / ui_tap / terminal_run）接入验证。
 */

internal interface ToolInput
internal interface ToolOutput

/** 给模型的内容：紧凑 JSON 或纯文本（终端用纯文本，避免转义膨胀）。 */
internal sealed interface ModelContent {
    data class Json(val obj: JSONObject) : ModelContent
    data class Text(val text: String) : ModelContent
}

// ---------------------------------------------------------------------------
// 合同一：工具输入/输出
// ---------------------------------------------------------------------------

internal interface ToolContract<I : ToolInput, O : ToolOutput> {
    val name: String
    val domain: ToolDomain
    /** 给模型的描述，≤160 字，只写契约。 */
    val summary: String

    /** 当前环境下是否可用；不可用时附原因码（root-only、缺权限、来源不可用）。默认总可用。 */
    fun availability(env: ToolEnvironment): ToolAvailability = ToolAvailability.Available

    /** 中立 JSON schema；协议特化（strict / Anthropic / Responses）由适配层做（合同 §7.1）。 */
    fun schema(env: ToolEnvironment): JSONObject

    /** 已过 schema 校验的参数 → 不可变类型化输入；约束失败抛 ToolFailure(INVALID_ARGUMENTS)。 */
    fun parse(args: ToolArgs, env: ToolEnvironment): I

    /** 输入 + 环境 → 不可变解析结果，审批/资源/执行共用同一对象（不各查一次）。 */
    fun resolve(input: I, env: ToolEnvironment): CallResolution

    fun execute(input: I, resolution: CallResolution, ctx: ToolContext): Verdict<O>

    /** 结构化输出 → 给模型的内容。 */
    fun renderForModel(output: O): ModelContent

    /** 部分成功时的告警（整体 ok + warnings），透传给模型与 UI；默认无。 */
    fun warnings(output: O): List<ToolWarning> = emptyList()

    /** 要附给模型本回合的图片（file_read 图片、截图、mcp 图片附件）；默认无。 */
    fun images(output: O): List<io.github.fartown.movo.agent.model.AgentModelClient.ModelImage> = emptyList()

    /**
     * 执行卡这一步的标题：动作 + 对象（「点按「搜索系统设置项」」「读取文件 · notes.txt」）。
     * 只用输入、不调后端，在执行前就能算出；为空时用工具的通用叫法。
     */
    fun uiTitle(input: I): String? = null

    /**
     * 结构化输出 → 给界面的视图（摘要一行 + 展开的内容块，工具可视化方案 §4）。从 [O] 派生，与 [renderForModel] 分开。
     * 图片由 [ContractTool] 统一加进视图；机密（SECRET）结果只保留摘要；为空时界面只显示通用的结果摘要。
     */
    fun renderForUi(input: I, output: O): ToolUiView? = null

    /**
     * 手动审批模式下给确认卡的可读预览：标题（卡片大标题）+ 预览盒正文（要执行的动作/关键参数）。
     * 按类别补全的确认本身没有可读文案，工具可据输入给出清晰预览；
     * 返回 null 时回退到按类别的通用标题 + 参数摘要。只对有后果的工具有意义，其余保持默认 null。
     */
    fun approvalPreview(input: I): ApprovalPreview? = null
}

/** 确认卡的可读预览：[title] 为卡片大标题，[detail] 为预览盒正文（首行作对象/强调，换行后为内容）。 */
internal data class ApprovalPreview(val title: String, val detail: String)

// ---------------------------------------------------------------------------
// 合同三：解析后的调用（不可变，审批/资源/执行/恢复共用）
// ---------------------------------------------------------------------------

/** GUI 注入后端；root 与非 root 不是一条路（合同 §5.3）。差异在验证强度与是否绕过系统限制。 */
internal enum class InjectionBackend {
    /** dispatchGesture / performAction；受 FLAG_SECURE、密码框、系统弹窗限制；有节点时验证强，无节点凭裸坐标派发时同样弱。 */
    ACCESSIBILITY,
    /** input/uiautomator 按坐标注入；绕过上述限制、能点安全窗，几乎无法回读。 */
    ROOT_INPUT,
    /** 非 GUI 工具。 */
    NONE,
}

/** 资源实例键：按类型 + 实例区分，支持一次占多个资源（合同 §12）。 */
internal data class ResourceKey(val resource: ToolResource, val instance: String = "") {
    override fun toString(): String = if (instance.isEmpty()) resource.name else "${resource.name}:$instance"
}

/** 目标身份：GUI 节点/坐标绑观察代际（gen）；文件/会话/MCP 绑各自实例身份。 */
internal sealed interface TargetIdentity {
    data object None : TargetIdentity
    data class Observed(val observationId: String, val gen: Long, val index: Int?) : TargetIdentity
    data class Coordinate(val observationId: String, val gen: Long) : TargetIdentity
    data class Named(val kind: String, val value: String) : TargetIdentity
}

/**
 * 不可变的调用解析结果。由 [ToolContract.resolve] 在准备阶段生成，审批、资源获取、临派发复核、
 * 执行、收尾都读它。这一步属于哪类有后果的动作由 [category] 声明，是否弹卡由审批设置决定。
 */
internal data class CallResolution(
    val risk: Risk,
    val sensitivity: Sensitivity,
    val resources: Set<ResourceKey>,
    val backend: InjectionBackend = InjectionBackend.NONE,
    val target: TargetIdentity = TargetIdentity.None,
    /**
     * 本次动作是否读到了可信节点（坐标点下有没有节点）。只作记录，不触发确认：
     * 读不到节点的界面（地图、画布、游戏、节点很多的列表）里每点一下都弹卡，用户无法使用（2026-10-05 用户反馈）。
     */
    val readableTarget: Boolean = true,
    /** 前台包名是否可信归因（只作记录）。 */
    val packageAttributed: Boolean = true,
    /** 工具自己给出的确认卡文案（例如模型声明的发送 / 付款），优先于按 [category] 补全。 */
    val toolApproval: ApprovalNeed? = null,
    /** 恢复资格（不用可空 null 二义，见 review I6）。 */
    val recovery: RecoverySpec = RecoverySpec.NonReplayable,
    /**
     * 审批前就确定拒绝（自我保护黑名单、锁屏等策略拒绝）。非空时在审批之前短路返回，
     * 不打扰用户弹确认卡（review：策略拒绝不应先弹卡再拒，设备/clockmedia 子任务反馈）。
     */
    val reject: ToolError? = null,
    /** 这一步属于哪类有后果的动作（权限模式方案）；普通操作为空。 */
    val category: ApprovalCategory? = null,
    /** 界面操作所在的应用，用于「某个应用里先问我」；非界面操作为空。 */
    val appPackage: String? = null,
) {
    /**
     * 这一步可能要问用户时的描述：工具自带文案优先，否则按类别 / 应用生成空文案，由 [ContractTool] 补全。
     * 要不要真的弹卡由审批设置（[ApprovalPolicy.shouldAsk]）决定。
     */
    fun consequence(): ApprovalNeed? = when {
        toolApproval != null -> toolApproval
        category != null || appPackage != null -> ApprovalNeed(category, title = "", detail = "", appPackage = appPackage)
        else -> null
    }
}

// ---------------------------------------------------------------------------
// 合同二：能力视图（请求视图带版本；全集/执行时权限两层延后，标 TODO）
// ---------------------------------------------------------------------------

/** 一次模型请求的工具视图：请求内不可变，带版本号。Provider/校验器/路由器共用同一版本。 */
internal data class RequestView(
    val version: String,
    val toolNames: Set<String>,
)

// ---------------------------------------------------------------------------
// 合同四：执行与恢复
// ---------------------------------------------------------------------------

/** 调用生命周期阶段（合同 §3.1）。ok/error/unknown 来自阶段证据，不从异常类型反推。 */
internal enum class CallStage {
    PREPARING, REJECTED, DISPATCHED, RUNNING, DETACHED, COMPLETED, CANCELLING, CANCELLED
}

/** 验证证据：必须可归因到本次动作，不能被已有状态蒙混（合同 §4.2）。 */
internal sealed interface Evidence {
    /** 回读到的状态与本次请求一致。 */
    data class ReadBack(val what: String) : Evidence
    /** 新出现、可归因到本次请求的条目；requestMarker 让“可归因”可校验（如本次 HH:MM）。 */
    data class AttributedNew(val what: String, val requestMarker: String) : Evidence
    /** 内容哈希匹配（file_write 等，不能只用 size）。 */
    data class ContentHash(val algorithm: String, val hash: String) : Evidence
}

/** 恢复合同：逐 tool+action 声明重放资格（合同 §4、F04、review I6）。 */
internal sealed interface RecoverySpec {
    /** 显式不可重放、无查询证据：中断后保留 unknown，禁止自动重放。 */
    data object NonReplayable : RecoverySpec
    /** 幂等可原样重放。 */
    data object Replayable : RecoverySpec
    /** 带 mutation 身份、可查询恢复。 */
    data class Queryable(val mutationId: String, val queryHint: String, val retentionMs: Long) : RecoverySpec
}

/** 执行结果。回读型未证实走 Unknown（不是 Dispatched），绝不冒领 ok。 */
internal sealed interface Verdict<out O : ToolOutput> {
    /** 只读：读取成功，无“效果”可验证（不带 effect_verified）。 */
    data class Read<O : ToolOutput>(val output: O) : Verdict<O>
    /** 回读型：效果已验证。 */
    data class Done<O : ToolOutput>(val output: O, val evidence: Evidence) : Verdict<O>
    /** 送达型：系统已接收派发，effect_verified=false，需再观察。 */
    data class Dispatched<O : ToolOutput>(val output: O) : Verdict<O>
    /** 转后台：返回 job 句柄（terminal_run 超时转后台 / background，合同 §3.3）。 */
    data class Backgrounded<O : ToolOutput>(val output: O, val job: JobRef) : Verdict<O>
    /** 可能生效也可能没生效（含回读型未证实）。 */
    data class Unknown(val reason: String, val next: String) : Verdict<Nothing>
    data class Failed(val error: ToolError) : Verdict<Nothing>
}

/** 后台任务句柄（合同 §3.3）。退出码与通知投递由宿主按 run 归属处理。 */
internal data class JobRef(
    val jobId: String,
    val keepAlive: Boolean,
    val startedAtMillis: Long,
    val reason: String,
)

// ---------------------------------------------------------------------------
// 合同五：资源租约（owner + 代际 + 实例键）—— 串行基线下最小，标 TODO 延后接线
// ---------------------------------------------------------------------------

/** 资源租约：设备级资源由宿主持有；落点见 AgentExecutionService.ExecutionLeaseRegistry。 */
internal data class ResourceLease(
    val key: ResourceKey,
    val owner: String,
    val generation: Long,
)

/** 资源停稳状态：看门狗到时只有确认停稳或移交隔离才回收，不凭到时强制释放（合同 §12、F02）。 */
internal enum class StopState { RUNNING, STOP_REQUESTED, CONFIRMED_STOPPED, ISOLATED }
