package io.github.fartown.movo.agent.tools.ui

import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.ApprovalNeed
import io.github.fartown.movo.agent.tools.core.ApprovalCategory
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
 * 1. **观察绑定**（合同 §5.1）：index 必带 observation_id，绑那次观察的内容代际，失效一律 STALE_OBSERVATION，
 *    不允许“默认最近观察”兜底。坐标隐式绑最近一次 ui_observe 的坐标系（[CoordinateFrame]）：只在从没观察过、
 *    屏幕方向或尺寸变了、前台窗口 / 应用换了时拒绝，页面内容刷新、滚动、文字变化都不算（见 [coordinateError]）。
 * 2. **审批分类**统一由 [buildUiActionResolution] 声明：模型声明的发送 / 支付等后果、所在应用；
 *    要不要弹卡由权限模式决定。读不到坐标点上的节点只记录（readableTarget），不单独确认。
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

/**
 * 文本写入目标语义：APPEND 接在输入框已有内容的末尾，REPLACE 整段替换。
 * 写入方式（直接设置文字 / 粘贴）由后端选，不改变此语义。
 */
internal enum class UiInputMode { APPEND, REPLACE }

/** 模型声明的动作后果：手动审批时用来判断这一步属于哪类高敏动作（合同 §0.6）。 */
internal enum class UiEffect { SEND, PAY, TRANSFER, DELETE, SUBMIT;

    fun label(): String = when (this) {
        SEND -> "发送"
        PAY -> "支付"
        TRANSFER -> "转账"
        DELETE -> "删除"
        SUBMIT -> "提交"
    }

    fun category(): ApprovalCategory = when (this) {
        PAY, TRANSFER -> ApprovalCategory.PAYMENT
        DELETE -> ApprovalCategory.DELETE
        SEND, SUBMIT -> ApprovalCategory.SEND
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

/**
 * 一次观察的身份：供坐标隐式绑定。[coordWidth] × [coordHeight] 是它返回给模型的 coord_space
 * （附了截图是截图像素，否则是屏幕），坐标的合法范围按它算；未知为 0，不查范围。
 */
internal data class ObservationRef(
    val observationId: String,
    val gen: Long,
    val coordWidth: Int = 0,
    val coordHeight: Int = 0,
)

/**
 * 坐标系：同一个 (x, y) 还是不是指同一块屏幕。坐标动作只看它，不看页面内容。
 * - [width] × [height] 是屏幕实际宽高，横竖屏切换时互换；读不到为 0；
 * - [windowGen] 是前台窗口代际：新 Activity、对话框、弹出菜单、输入法这类窗口出现才 +1，
 *   内容刷新、滚动、文字变化、Movo 自己的浮窗都不算；没有无障碍时读不到，为 null；
 * - [packageName] 是前台应用；读不到为 null。
 * 读不到的项不参与比较。
 */
internal data class CoordinateFrame(
    val width: Int,
    val height: Int,
    val windowGen: Long?,
    val packageName: String?,
)

/**
 * 动作实际按在屏幕上的位置（真实屏幕坐标），成功后给手势指示用（规范 9.5）。
 * [Press] 是点按（[holdMs] > 0 为长按），[Drag] 是滑动 / 拖动。
 */
internal sealed interface UiTouch {
    data class Press(val x: Int, val y: Int, val holdMs: Int) : UiTouch
    data class Drag(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Int) : UiTouch
}

/** 节点探针：观察节点、提交点识别命中节点、ui_wait 命中节点共用的轻量视图。 */
internal data class UiNodeProbe(
    val text: String? = null,
    val desc: String? = null,
    val role: String? = null,
    val bounds: List<Int> = emptyList(),
    val clickable: Boolean = false,
    val password: Boolean = false,
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

    /** 某次观察时的坐标系；未知返回 null（不据此拒绝）。 */
    fun observationFrame(observationId: String): CoordinateFrame? = null

    /** 现在的坐标系；读不到返回 null（不据此拒绝）。 */
    fun currentFrame(): CoordinateFrame? = null

    /** 当前可信前台包名；覆盖层/多窗/安全窗无法可信归因时返回 null（合同 §5.3）。 */
    fun foregroundPackage(): String?

    /** 某次观察归属的包名；未知返回 null。 */
    fun observationPackage(observationId: String): String?

    /** 某次观察里第 [index] 个节点（给确认卡写「点按「转账」」用）；未知返回 null。 */
    fun observedNode(observationId: String, index: Int): UiNodeProbe? = null

    /** 当前输入焦点是不是密码框；读不到返回 null。 */
    fun focusedInputIsPassword(): Boolean? = null
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
        val screenshotFailure: String? = null,
    ) : UiObserveResult

    /** 无障碍不可用且无 root：PERMISSION_REQUIRED。 */
    data object PermissionRequired : UiObserveResult

    data class Unavailable(val reason: String) : UiObserveResult
}

/** 注入后端：tap/swipe/scroll/input/key/wait + 提交点可读性探针。 */
internal interface UiActionBackend {
    /** 当前可用的注入后端；NONE 表示既无无障碍也无 root。 */
    fun backend(env: ToolEnvironment): InjectionBackend

    /**
     * 坐标处实时抓树读到的可信节点（给确认卡写「点按「转账」」）；读不到返回 null → readableTarget=false。
     * 要抓整棵树（只有 Root 时是一次 uiautomator dump），只在这一步真的要弹确认卡时才调用。
     */
    fun readableNodeAtPoint(x: Double, y: Double): UiNodeProbe?

    /** 现在屏幕上第一个文字或描述包含 [text] 的节点（单次查询，不登记观察）；没有或读不到返回 null。 */
    fun findText(text: String): UiNodeProbe? = null

    /** 动作成功后在被点的位置显示手势指示、配触感（规范 9.5）；默认不显示。 */
    fun showTouch(touch: UiTouch) = Unit

    fun tap(request: UiTapRequest, env: ToolEnvironment): UiInjectResult
    fun focus(element: UiTarget.Element, direction: ScrollDirection?, env: ToolEnvironment): UiInjectResult =
        UiInjectResult.NotActionable("此后端不支持移动焦点")
    fun swipe(request: UiSwipeRequest, env: ToolEnvironment): UiInjectResult
    fun scroll(request: UiScrollRequest, env: ToolEnvironment): UiScrollResult
    fun input(request: UiInputRequest, env: ToolEnvironment): UiInputResult
    fun key(request: UiKeyRequest, env: ToolEnvironment): UiInjectResult
    fun waitFor(request: UiWaitRequest, env: ToolEnvironment, checkCancelled: () -> Unit = {}): UiWaitResult
}

internal data class UiTapRequest(val target: UiTarget, val holdMs: Int, val backend: InjectionBackend)
internal data class UiSwipeRequest(
    val x: Double, val y: Double, val x2: Double, val y2: Double,
    val durationMs: Int, val holdMs: Int?, val backend: InjectionBackend,
)
/** 滚动一次。until_text 由 ui_scroll 逐次滚动 + [UiActionBackend.findText] 完成，不进请求。 */
internal data class UiScrollRequest(
    val direction: ScrollDirection, val element: UiTarget.Element?,
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
    /** [touch] 是实际按下的屏幕位置（tap / swipe 才有），成功后给手势指示用。 */
    data class Dispatched(
        val method: String,
        val afterPackage: String?,
        val windowChanged: Boolean,
        val touch: UiTouch? = null,
    ) : UiInjectResult
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
        /** 写完后输入框里实际的文字：读得到、但和要写的不一致时才有（自动格式化、限长、换行被改）；密码框为 null。 */
        val readback: String? = null,
        /** 走了粘贴：剪贴板被临时改过（写完已尽量恢复）。 */
        val clipboardWritten: Boolean = false,
        /** 要求了 submit 但没提交成功的原因。 */
        val submitError: String? = null,
    ) : UiInputResult
    /** 没写进去：无焦点、不可编辑、读不到原文没法追加等。[code] 是后端的细分码。 */
    data class NotActionable(val reason: String, val code: String = "") : UiInputResult
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
 * 构造一次“有目标 ui_* 动作”的解析结果。这一步属于哪类有后果的动作由模型声明的 [effect] 决定
 * （付款、转账 → 付款；删除 → 删东西；发送、提交 → 发消息和提交表单），所在应用记在 appPackage，
 * 供手动审批时的「某个应用里先问我」。要不要弹卡由审批设置决定，YOLO 下一律不弹。
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
    /** resolve 预检出的拒绝（观察失效 [genError] / [coordinateError]、坐标越界）：在确认之前就拒绝。 */
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
    val category = effect?.category()
    val appLabel = appLabel(pkg)
    val toolApproval = when {
        reject != null -> null
        effect != null -> ApprovalNeed(
            category = category,
            title = "在「$appLabel」里${effect.label()}？",
            detail = "下一步：$action",
            appPackage = pkg,
        )
        pkg != null -> ApprovalNeed(
            category = null,
            title = "在「$appLabel」里继续操作？",
            detail = "下一步：$action",
            appPackage = pkg,
            reason = "你设了在「$appLabel」里每一步都先问你。",
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
        category = category,
        appPackage = pkg,
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

/**
 * 坐标动作（ui_tap 坐标 / 区域、ui_swipe）的预检与复核：坐标只绑最近一次观察的坐标系。
 * 从没观察过、屏幕方向或尺寸变了、前台窗口 / 应用换了才拒绝；页面内容刷新（视频进度条约 0.12 秒一次）、
 * 滚动、文字变化都不算，否则持续刷新的页面上坐标动作永远追不上。resolve 时预检（不先弹卡让用户白点），
 * execute 前用同一条规则复核（确认期间可能切走了）。
 */
internal fun coordinateError(registry: UiObservationRegistry, observationId: String?): ToolError? {
    if (observationId.isNullOrEmpty()) {
        return ToolError(
            ToolErrorCode.STALE_OBSERVATION,
            "没有可用的屏幕观察",
            hint = "先调用 ui_observe，坐标用它返回的 coord_space",
        )
    }
    val bound = registry.observationFrame(observationId) ?: return null
    val now = registry.currentFrame() ?: return null
    val change = frameChange(bound, now, registry.selfPackage) ?: return null
    return ToolError(
        ToolErrorCode.STALE_OBSERVATION,
        "坐标系已变：$change",
        hint = "重新 ui_observe，按新的 coord_space 给坐标",
    )
}

/**
 * 两个坐标系哪里变了（写给模型看）；没变或比不了返回 null。
 * 前台是 Movo 自己（悬浮卡等）时不算换了应用：有目标的动作另有自我保护拒绝。
 */
internal fun frameChange(bound: CoordinateFrame, now: CoordinateFrame, selfPackage: String): String? {
    val sizeKnown = bound.width > 0 && bound.height > 0 && now.width > 0 && now.height > 0
    if (sizeKnown && (bound.width != now.width || bound.height != now.height)) {
        return "屏幕方向或尺寸变了（${bound.width}x${bound.height} → ${now.width}x${now.height}）"
    }
    val before = bound.packageName?.takeIf { it.isNotBlank() }
    val after = now.packageName?.takeIf { it.isNotBlank() && it != selfPackage }
    if (before != null && after != null && before != after) return "前台应用变了（$before → $after）"
    if (bound.windowGen != null && now.windowGen != null && bound.windowGen != now.windowGen) {
        return "前台窗口变了（打开了新页面、对话框或菜单）"
    }
    return null
}

/**
 * 坐标越界：按观察的 coord_space 检查，报参数错误并写出合法范围（不让控制器抛异常，
 * 被管线兜成「内部错误，可以重试」诱导原样重试）。范围未知时不查。
 */
internal fun coordinateRangeError(ref: ObservationRef?, vararg points: Pair<Double, Double>): ToolError? {
    val width = ref?.coordWidth ?: 0
    val height = ref?.coordHeight ?: 0
    if (width <= 0 || height <= 0) return null
    val (x, y) = points.firstOrNull { (px, py) -> px < 0 || py < 0 || px >= width || py >= height } ?: return null
    return ToolError(
        ToolErrorCode.INVALID_ARGUMENTS,
        "坐标 (${x.coordText()}, ${y.coordText()}) 超出屏幕范围",
        hint = "合法范围：x 0–${width - 1}，y 0–${height - 1}（最近一次 ui_observe 的 coord_space ${width}x$height）",
    )
}

private fun Double.coordText(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

// ---------------------------------------------------------------------------
// 屏幕文字匹配（ui_wait、ui_scroll until_text）
// ---------------------------------------------------------------------------

/** 文字匹配：contains 不分大小写；exact、prefix 区分大小写；regex 部分匹配。 */
internal fun textMatcher(needle: String, match: WaitMatch): (String) -> Boolean {
    // 正则只编译一次：等待时每轮要比对上百个节点。
    val regex = if (match == WaitMatch.REGEX) runCatching { Regex(needle) }.getOrNull() else null
    return { value ->
        when (match) {
            WaitMatch.CONTAINS -> value.contains(needle, ignoreCase = true)
            WaitMatch.EXACT -> value == needle
            WaitMatch.PREFIX -> value.startsWith(needle)
            WaitMatch.REGEX -> regex?.containsMatchIn(value) == true
        }
    }
}

/** 节点的文字或描述命中即算。 */
internal fun List<UiNodeProbe>.firstMatching(matches: (String) -> Boolean): UiNodeProbe? =
    firstOrNull { node -> listOfNotNull(node.text, node.desc).any(matches) }

/** ui_wait 文字条件的一次判定：[met] 是否满足；等出现时 [node] 是命中的节点。 */
internal data class TextCheck(val met: Boolean, val node: UiNodeProbe? = null)

/**
 * ui_wait 文字条件的一次判定。等出现：有节点命中就满足，带回命中的节点。
 * 等消失（[gone]）：要读到了节点、且没有一个命中才算消失；读不到屏幕（null 或空）不算，
 * 免得把「看不见」当成「没有了」。
 */
internal fun textCheck(nodes: List<UiNodeProbe>?, needle: String, match: WaitMatch, gone: Boolean): TextCheck {
    val hit = nodes?.firstMatching(textMatcher(needle, match))
    return if (gone) TextCheck(met = !nodes.isNullOrEmpty() && hit == null) else TextCheck(met = hit != null, node = hit)
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

// ---------------------------------------------------------------------------
// 执行卡（工具可视化方案）：屏幕动作的标题与结果用同一套说法
// ---------------------------------------------------------------------------

/** 点按 / 长按的对象：观察里的节点叫法；坐标时写位置。只查进程内的观察记录，不抓新树。 */
internal fun tapTitle(registry: UiObservationRegistry, target: UiTarget, holdMs: Int): String {
    val verb = if (holdMs > 0) "长按" else "点按"
    return when (target) {
        is UiTarget.Element -> registry.observedNode(target.observationId, target.index)?.displayName()
            ?.let { "$verb「$it」" } ?: "${verb}屏幕上的第 ${target.index} 个元素"
        is UiTarget.Point -> "${verb}屏幕 (${target.x.toInt()}, ${target.y.toInt()})"
        is UiTarget.Area -> "${verb}屏幕区域"
    }
}

/** 动作后的去向：换了应用就写进了哪个应用。 */
internal fun afterSummary(verb: String, packageName: String?, windowChanged: Boolean): String =
    if (windowChanged && !packageName.isNullOrBlank()) "$verb · 进入「${appLabel(packageName)}」" else verb

internal fun ScrollDirection.label(): String = when (this) {
    ScrollDirection.UP -> "向上"
    ScrollDirection.DOWN -> "向下"
    ScrollDirection.LEFT -> "向左"
    ScrollDirection.RIGHT -> "向右"
}

internal fun UiKeyCode.label(): String = when (this) {
    UiKeyCode.BACK -> "返回"
    UiKeyCode.HOME -> "回到桌面"
    UiKeyCode.RECENTS -> "最近任务"
    UiKeyCode.ENTER -> "回车"
    UiKeyCode.NOTIFICATIONS -> "通知栏"
    UiKeyCode.QUICK_SETTINGS -> "快捷设置"
    UiKeyCode.LOCK_SCREEN -> "锁屏"
    UiKeyCode.SCREENSHOT -> "截屏"
    UiKeyCode.DISMISS_NOTIFICATIONS -> "收起通知栏"
}

