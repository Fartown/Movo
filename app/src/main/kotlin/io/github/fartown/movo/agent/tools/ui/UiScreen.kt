package io.github.fartown.movo.agent.tools.ui

import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.ApprovalNeed
import io.github.fartown.movo.agent.tools.core.ApprovalReason
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.InjectionBackend
import io.github.fartown.movo.agent.tools.core.ResourceKey
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.SchemaBuilder
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.TargetIdentity
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolError
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.ToolResource
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.invalidArgs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 屏幕 UI 领域共享类型与工具函数。
 *
 * 本领域最难的两点都落在这里：
 * 1. **观察代际绑定**（合同 §5.1）：index 必带 observation_id；坐标隐式绑最近一次 ui_observe 的 gen；
 *    gen 失效一律 STALE_OBSERVATION，不允许“默认最近观察”兜底。
 * 2. **确认**统一由 [buildUiActionResolution] 声明：模型声明的发送 / 支付等后果、用户设的受保护应用；
 *    读不到坐标点上的节点只记录（readableTarget），不单独确认（2026-10-05 用户反馈：这类界面每一步都弹卡）。
 */

// ---------------------------------------------------------------------------
// 枚举
// ---------------------------------------------------------------------------

/** 内容滚动方向（想看到的内容在哪个方向，与手指方向相反，由后端换算）。 */
internal enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/** 系统键与全局动作。home/back/enter 等不触发自我保护拒绝。 */
internal enum class UiKeyCode {
    BACK, HOME, RECENTS, ENTER, NOTIFICATIONS, QUICK_SETTINGS, LOCK_SCREEN, SCREENSHOT, DISMISS_NOTIFICATIONS
}

/** 文本写入目标语义；写入方式（set_text/paste）由后端选，不改变此语义。 */
internal enum class UiInputMode { APPEND, REPLACE }

/** 模型声明的动作后果：只增加确认，不免除（合同 §0.6）。 */
internal enum class UiEffect { SEND, PAY, TRANSFER, DELETE, SUBMIT;

    fun label(): String = when (this) {
        SEND -> "发送"
        PAY -> "支付"
        TRANSFER -> "转账"
        DELETE -> "删除"
        SUBMIT -> "提交"
    }
}

/** 包名 → 应用名（取不到时用包名），审批卡上不出现包名。 */
internal fun appLabel(pkg: String?): String {
    if (pkg.isNullOrBlank()) return "当前应用"
    val context = io.github.fartown.movo.agent.runtime.AgentAppContext.resolve() ?: return pkg
    return runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

/** ui_wait 的文字匹配方式。 */
internal enum class WaitMatch { CONTAINS, EXACT, PREFIX, REGEX }

// ---------------------------------------------------------------------------
// 界面目标（§0.5 压平：index / x,y / x,y,x2,y2）
// ---------------------------------------------------------------------------

internal sealed interface UiTarget {
    /** 节点引用：observationId 指向模型实际看过的那次观察，index 为其中的节点序号。 */
    data class Element(val observationId: String, val index: Int) : UiTarget
    /** 坐标点（在最近一次 ui_observe 的 coord_space 中）。 */
    data class Point(val x: Double, val y: Double) : UiTarget
    /** 矩形区域，操作其中心。 */
    data class Area(val x1: Double, val y1: Double, val x2: Double, val y2: Double) : UiTarget {
        val centerX: Double get() = (x1 + x2) / 2.0
        val centerY: Double get() = (y1 + y2) / 2.0
    }
}

/** 一次观察的身份：供坐标隐式绑定。 */
internal data class ObservationRef(val observationId: String, val gen: Long)

/** 节点探针：观察节点、提交点识别命中节点、ui_wait 命中节点共用的轻量视图。 */
internal data class UiNodeProbe(
    val text: String? = null,
    val desc: String? = null,
    val role: String? = null,
    val bounds: List<Int> = emptyList(),
    val clickable: Boolean = false,
)

// ---------------------------------------------------------------------------
// 后端接口（可测；真实实现见 UiRealBackends.kt）
// ---------------------------------------------------------------------------

/**
 * 观察登记表：ui_observe 写入，所有动作工具在 resolve/execute 时读它做代际校验与包名归因。
 * 真实实现由同一个对象同时实现 [UiObserveBackend] / [UiActionBackend]，测试可用单一假实现。
 */
internal interface UiObservationRegistry {
    /** 本应用（Movo 自己）的包名，用于自我保护黑名单。 */
    val selfPackage: String

    /** 该 observation_id 当前仍有效的代际号；失效或未知返回 null（→ STALE_OBSERVATION）。 */
    fun genOf(observationId: String): Long?

    /** 最近一次 ui_observe（供坐标隐式绑定）；从未观察过返回 null。 */
    fun latest(): ObservationRef?

    /** 当前可信前台包名；覆盖层/多窗/安全窗无法可信归因时返回 null（合同 §5.3）。 */
    fun foregroundPackage(): String?

    /** 某次观察归属的包名；未知返回 null。 */
    fun observationPackage(observationId: String): String?

    /** 某次观察里第 [index] 个节点（给确认卡写「点按「转账」」用）；未知返回 null。 */
    fun observedNode(observationId: String, index: Int): UiNodeProbe? = null
}

/** ui_observe 的后端：观察并登记一次新代际。 */
internal interface UiObserveBackend : UiObservationRegistry {
    fun observe(request: UiObserveRequest, env: ToolEnvironment): UiObserveResult
}

internal data class UiObserveRequest(
    val screenshot: Boolean,
    val nodes: Boolean,
    val maxNodes: Int,
    val query: String?,
)

internal data class UiObservedNode(
    val index: Int,
    val text: String? = null,
    val desc: String? = null,
    val role: String? = null,
    val viewId: String? = null,
    val bounds: List<Int> = emptyList(),
    val checked: Boolean? = null,
    val selected: Boolean? = null,
    val editable: Boolean? = null,
    val password: Boolean? = null,
    val enabled: Boolean? = null,
    val actions: List<String> = emptyList(),
)

internal sealed interface UiObserveResult {
    data class Observed(
        val observationId: String,
        val gen: Long,
        val packageName: String?,
        val coordWidth: Int,
        val coordHeight: Int,
        val focusedIndex: Int?,
        val nodes: List<UiObservedNode>,
        val nodesTruncated: Boolean,
        val screenshotAttached: Boolean,
        val screenshotQuality: String?,
        /** 附给模型的截图；无则 null。 */
        val screenshot: AgentModelClient.ModelImage?,
    ) : UiObserveResult

    /** 无障碍不可用且无 root：PERMISSION_REQUIRED。 */
    data object PermissionRequired : UiObserveResult
}

/** 注入后端：tap/swipe/scroll/input/key/wait + 提交点可读性探针。 */
internal interface UiActionBackend {
    /** 当前可用的注入后端；NONE 表示既无无障碍也无 root。 */
    fun backend(env: ToolEnvironment): InjectionBackend

    /** 坐标处能否实时读到可信节点（提交点识别）；读不到返回 null → readableTarget=false。 */
    fun readableNodeAtPoint(x: Double, y: Double): UiNodeProbe?

    fun tap(request: UiTapRequest, env: ToolEnvironment): UiInjectResult
    fun swipe(request: UiSwipeRequest, env: ToolEnvironment): UiInjectResult
    fun scroll(request: UiScrollRequest, env: ToolEnvironment): UiScrollResult
    fun input(request: UiInputRequest, env: ToolEnvironment): UiInputResult
    fun key(request: UiKeyRequest, env: ToolEnvironment): UiInjectResult
    fun waitFor(request: UiWaitRequest, env: ToolEnvironment): UiWaitResult
}

internal data class UiTapRequest(val target: UiTarget, val holdMs: Int, val backend: InjectionBackend)
internal data class UiSwipeRequest(
    val x: Double, val y: Double, val x2: Double, val y2: Double,
    val durationMs: Int, val holdMs: Int?, val backend: InjectionBackend,
)
internal data class UiScrollRequest(
    val direction: ScrollDirection, val element: UiTarget.Element?, val untilText: String?,
    val backend: InjectionBackend,
)
internal data class UiInputRequest(
    val text: String, val mode: UiInputMode, val element: UiTarget.Element?, val submit: Boolean,
    val backend: InjectionBackend,
)
internal data class UiKeyRequest(val key: UiKeyCode, val backend: InjectionBackend)
internal data class UiWaitRequest(
    val text: String?, val match: WaitMatch, val gone: Boolean,
    val packageName: String?, val durationMs: Int?, val timeoutMs: Int,
)

/** tap / swipe / key 的送达型结果。 */
internal sealed interface UiInjectResult {
    data class Dispatched(val method: String, val afterPackage: String?, val windowChanged: Boolean) : UiInjectResult
    /** 系统拒绝派发，确定没执行。 */
    data object SystemRejected : UiInjectResult
    data class NotActionable(val reason: String) : UiInjectResult
    /** 派发了但无法确认是否生效。 */
    data object OutcomeUnknown : UiInjectResult
    data object PermissionRequired : UiInjectResult
}

/** scroll 的可判定结果：moved 可读。 */
internal sealed interface UiScrollResult {
    data class Finished(val moved: Boolean, val atBoundary: Boolean?, val afterPackage: String?) : UiScrollResult
    /** 界面反向移动：DIRECTION_MISMATCH → OUTCOME_UNKNOWN。 */
    data object DirectionMismatch : UiScrollResult
    data class NotActionable(val reason: String) : UiScrollResult
    data object OutcomeUnknown : UiScrollResult
    data object PermissionRequired : UiScrollResult
}

/** input 的结果：回读文字一致与否、是否已提交。 */
internal sealed interface UiInputResult {
    data class Written(
        val method: String,
        val readbackMatches: Boolean,
        val readbackLength: Int,
        val submitted: Boolean,
        val afterPackage: String?,
        val windowChanged: Boolean,
    ) : UiInputResult
    /** 无焦点 / 不可编辑 / append 无法插入——不自动降级为 replace。 */
    data class NotActionable(val reason: String) : UiInputResult
    data object OutcomeUnknown : UiInputResult
    data object SystemRejected : UiInputResult
    data object PermissionRequired : UiInputResult
}

internal sealed interface UiWaitResult {
    data class Finished(val matched: Boolean, val elapsedMs: Long, val node: UiNodeProbe?) : UiWaitResult
    /** 等文字/应用但无障碍不可用。 */
    data object PermissionRequired : UiWaitResult
}

// ---------------------------------------------------------------------------
// Schema / 解析助手
// ---------------------------------------------------------------------------

/** 压平的界面目标 schema（§0.5）：index(+observation_id) / x,y / x,y,x2,y2。 */
internal fun SchemaBuilder.flatTarget(allowCoordinates: Boolean = true, allowArea: Boolean = true) {
    integer("index", "目标节点 index（来自 observation_id 指向的那次 ui_observe）", min = 0)
    string("observation_id", "index 对应的观察 id；用 index 时必填，绑定其观察代际", maxLength = 64)
    if (allowCoordinates) {
        number("x", "坐标点横坐标（在最近一次 ui_observe 的 coord_space 中）")
        number("y", "坐标点纵坐标")
        if (allowArea) {
            number("x2", "区域右下角横坐标（给出 x,y,x2,y2 即区域，操作其中心）")
            number("y2", "区域右下角纵坐标")
        }
    }
}

/** 解析压平的界面目标；字段冲突或缺失报 INVALID_ARGUMENTS 并指出哪个字段错。 */
internal fun parseFlatTarget(args: ToolArgs, allowCoordinates: Boolean = true, allowArea: Boolean = true): UiTarget {
    val hasIndex = args.has("index")
    val hasX = args.has("x")
    val hasY = args.has("y")
    val hasX2 = args.has("x2")
    val hasY2 = args.has("y2")
    if (hasIndex) {
        if (hasX || hasY || hasX2 || hasY2) invalidArgs("index 与坐标互斥", "二选一：index 或 x,y[,x2,y2]")
        val obs = args.stringOrNull("observation_id")
            ?: invalidArgs("用 index 时必须提供 observation_id", "observation_id 来自 index 所属那次 ui_observe")
        return UiTarget.Element(obs, args.int("index"))
    }
    if (!allowCoordinates) invalidArgs("必须提供 index")
    if (!hasX || !hasY) invalidArgs("缺少目标：提供 index，或 x,y")
    if (hasX2 || hasY2) {
        if (!(hasX2 && hasY2)) invalidArgs("区域需要同时提供 x2 和 y2")
        if (!allowArea) invalidArgs("该工具不支持区域目标")
        return UiTarget.Area(args.double("x"), args.double("y"), args.double("x2"), args.double("y2"))
    }
    return UiTarget.Point(args.double("x"), args.double("y"))
}

// ---------------------------------------------------------------------------
// resolve / execute 共享逻辑
// ---------------------------------------------------------------------------

/**
 * 构造一次“有目标 ui_* 动作”的解析结果。什么时候要确认（2026-10-05 用户反馈后收窄，只剩这三种）：
 * - 模型声明了后果（发送、提交、删除；支付、转账为深色确认、不能「本次任务内都允许」）；
 * - 目标在用户自己设的受保护应用里（默认没有）；
 * - 用户设过受保护应用，但认不出当前是哪个应用。
 * 读不到坐标点上的节点（地图、画布、游戏、节点很多的列表）不再单独确认，否则这类界面每一步都弹卡。
 * [selfProtect] 且目标命中 Movo 自身 → 审批前直接 reject(POLICY_DENIED)。[action] 是写给用户的这一步，
 * 例如「点按「转账」」「输入文字」，显示在确认卡灰底块第一行。
 */
internal fun buildUiActionResolution(
    backend: InjectionBackend,
    target: TargetIdentity,
    pkg: String?,
    selfPackage: String,
    readableTarget: Boolean,
    effect: UiEffect?,
    selfProtect: Boolean,
    sensitivity: Sensitivity = Sensitivity.NORMAL,
    extraResources: Set<ResourceKey> = emptySet(),
    /** resolve 预检出的观察失效（[genError]）：在确认之前就拒绝。 */
    stale: ToolError? = null,
    action: String = "操作屏幕",
): CallResolution {
    val hasTarget = target != TargetIdentity.None
    val reject = if (selfProtect && hasTarget && pkg != null && pkg == selfPackage) {
        ToolError(
            code = ToolErrorCode.POLICY_DENIED,
            message = "不对 Movo 自己的界面执行有目标的操作",
            hint = "切换到目标应用后再操作；home/back 等全局键不受此限制",
        )
    } else {
        stale
    }
    val protectedApps = io.github.fartown.movo.agent.tools.core.ProtectedApps
    val toolApproval = when {
        reject != null -> null
        effect != null -> {
            val payment = effect == UiEffect.PAY || effect == UiEffect.TRANSFER
            ApprovalNeed(
                reason = if (payment) ApprovalReason.PAYMENT else ApprovalReason.DECLARED_EFFECT,
                title = "在「${appLabel(pkg)}」里${effect.label()}？",
                detail = "下一步：$action\n确认后 Movo 才会${effect.label()}。",
                taskScope = pkg,
                allowTaskScope = !payment && effect != UiEffect.DELETE,
            )
        }
        pkg != null && protectedApps.isProtected(pkg) -> ApprovalNeed(
            reason = ApprovalReason.PROTECTED_APP,
            title = "在「${appLabel(pkg)}」里继续操作？",
            detail = "下一步：$action\n你把「${appLabel(pkg)}」设为了受保护应用，在里面每一步都会先问你。",
            taskScope = pkg,
            allowTaskScope = false,
        )
        pkg == null && hasTarget && protectedApps.anyConfigured() -> ApprovalNeed(
            reason = ApprovalReason.PROTECTED_APP,
            title = "继续操作屏幕？",
            detail = "下一步：$action\n认不出现在是哪个应用，没法判断是不是你设的受保护应用。",
            allowTaskScope = false,
        )
        else -> null
    }
    return CallResolution(
        risk = Risk.LOCAL,
        sensitivity = sensitivity,
        resources = setOf(ResourceKey(ToolResource.SCREEN)) + extraResources,
        backend = backend,
        target = target,
        readableTarget = readableTarget,
        packageAttributed = pkg != null,
        toolApproval = toolApproval,
        reject = reject,
        // 界面点击/滑动/输入是本地交互，污点不拦它们；发送、支付由声明的后果与受保护应用兜底。
        exfiltrates = false,
    )
}

/** 节点在确认卡上的叫法：文字 → 描述 → 无。超长截断，换行压成空格。 */
internal fun UiNodeProbe.displayName(): String? =
    (text ?: desc)?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { if (it.length > 20) it.take(20) + "…" else it }

/** 代际再校验：失效返回 Verdict.Failed(STALE_OBSERVATION)，有效返回 null。 */
internal fun checkGen(registry: UiObservationRegistry, observationId: String?, boundGen: Long): Verdict.Failed? =
    genError(registry, observationId, boundGen)?.let { Verdict.Failed(it) }

/**
 * 代际校验的错误本体。resolve 阶段先用它预检（观察不存在、已过期时直接拒绝，不先弹确认卡让用户白点一次），
 * execute 前再用 [checkGen] 复核（确认期间页面可能变了）。
 */
internal fun genError(registry: UiObservationRegistry, observationId: String?, boundGen: Long): ToolError? {
    if (observationId.isNullOrEmpty()) {
        return ToolError(ToolErrorCode.STALE_OBSERVATION, "没有可用的屏幕观察", hint = "先调用 ui_observe")
    }
    val live = registry.genOf(observationId)
        ?: return ToolError(
            ToolErrorCode.STALE_OBSERVATION,
            "observation_id=$observationId 已过期",
            hint = "重新 ui_observe 后再用新的 index/坐标",
        )
    if (live != boundGen) {
        return ToolError(
            ToolErrorCode.STALE_OBSERVATION,
            "observation_id=$observationId 的代际已变（$boundGen → $live）",
            hint = "窗口内容/方向已变，重新 ui_observe",
        )
    }
    return null
}

/** 动作后现场：after{package, window_changed}。 */
internal fun afterJson(packageName: String?, windowChanged: Boolean): JSONObject =
    JSONObject().apply {
        packageName?.let { put("package", it) }
        put("window_changed", windowChanged)
    }

internal fun UiNodeProbe.toJson(): JSONObject = JSONObject().apply {
    text?.let { put("text", it) }
    desc?.let { put("desc", it) }
    role?.let { put("role", it) }
    if (bounds.isNotEmpty()) put("bounds", JSONArray(bounds))
}

/** 本领域送达型动作输出的公共形态：after{package,window_changed} + 可选 method/key。 */
internal data class UiAfter(
    val packageName: String?,
    val windowChanged: Boolean,
    /** click/long_click/gesture（tap 用）；其它动作为 null。 */
    val method: String? = null,
    /** ui_key 的键名。 */
    val key: String? = null,
) : ToolOutput
