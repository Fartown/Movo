package io.github.fartown.movo.agent.tools.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import io.github.fartown.movo.agent.device.RootShellDeviceController
import io.github.fartown.movo.agent.overlay.GestureIndicator
import io.github.fartown.movo.agent.tools.core.InjectionBackend
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.core.AgentLogger
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 屏幕 UI 领域真实后端。
 *
 * 剪贴板读写用 Android [ClipboardManager]，已是真实实现。
 * 屏幕观察与注入（无障碍树 / dispatchGesture / performAction / root uiautomator / 截图编码）接线到
 * 现有 `agent/accessibility/AgentAccessibilityService` 与 `agent/device/RootShellDeviceController`：
 * 这些细节（代际指纹、提交点节点命中、坐标空间缩放、截图编码）**本子任务留 TODO、签名完整、返回保守占位**，
 * 由主流程在集中接线阶段补齐（见返回报告的 TODO 清单）。占位返回不会冒领 ok：
 * 观察/等待缺实现返回 PermissionRequired，注入返回 OUTCOME_UNKNOWN。
 */

// ---------------------------------------------------------------------------
// 剪贴板（真实实现）
// ---------------------------------------------------------------------------

internal class RealClipboardReadBackend(
    private val context: Context,
    /** Movo 自己在前台（有界面显示着）。 */
    private val inForeground: () -> Boolean = ::movoInForeground,
) : ClipboardReadBackend {
    override fun read(): ClipboardReadResult {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ClipboardReadResult.Unavailable
        // Android 10+ 只有前台应用、默认输入法、有焦点的窗口能读剪贴板。被拒时 hasPrimaryClip() 也返回 false、
        // getPrimaryClip() 返回 null，和「确实是空的」分不开：Movo 不在前台时按被拒报，不冒充「空」。
        val hasClip = runCatching { cm.hasPrimaryClip() }.getOrDefault(false)
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            return when {
                hasClip -> ClipboardReadResult.Unavailable
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !inForeground() -> ClipboardReadResult.Rejected
                else -> ClipboardReadResult.Empty
            }
        }
        val full = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        if (full.isEmpty()) return ClipboardReadResult.Empty
        val sensitive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            clip.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        val limit = 8000
        val truncated = full.length > limit
        val text = if (truncated) full.substring(0, limit) else full
        return ClipboardReadResult.Text(text, truncated, sensitive)
    }
}

/** 本进程是不是前台（Movo 的界面显示着）；前台服务不算。 */
private fun movoInForeground(): Boolean =
    android.app.ActivityManager.RunningAppProcessInfo()
        .also(android.app.ActivityManager::getMyMemoryState)
        .importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

internal class RealClipboardWriteBackend(
    private val context: Context,
) : ClipboardWriteBackend {
    override fun write(text: String, sensitive: Boolean): ClipboardWriteResult {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ClipboardWriteResult.Rejected
        val clip = ClipData.newPlainText("movo", text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        return runCatching {
            cm.setPrimaryClip(clip)
            // 回读证实（后台可能读不回 → Dispatched）。
            val back = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            if (back == text) ClipboardWriteResult.ReadBackOk else ClipboardWriteResult.ReadBackLimited
        }.getOrElse { ClipboardWriteResult.Rejected }
    }
}

// ---------------------------------------------------------------------------
// 屏幕观察 + 注入：委托 RootShellDeviceController（无障碍 + root 兜底 + settle）
// ---------------------------------------------------------------------------

/** 一次观察的登记条目：代际、包名，以及回放动作所需的元素观察 / 快照 / 坐标空间。 */
private data class ObservationEntry(
    val gen: Long,
    val packageName: String?,
    val elementObservation: RootShellDeviceController.ElementObservation?,
    val snapshot: AgentAccessibilityService.NodeSnapshot?,
    val coordinateSpace: RootShellDeviceController.CoordinateSpace?,
)

/**
 * 同一对象实现 [UiObserveBackend] 与 [UiActionBackend]：观察登记与动作共享代际状态。
 * 实际观察/注入委托 [RootShellDeviceController]（它已组合无障碍服务 + root uiautomator 兜底 + 动作后 settle），
 * 本类负责把控制器的结果翻译成类型化契约结果，并维护「观察 id → 代际」的新鲜度校验。
 */
internal class RealUiScreenBackend(
    private val context: Context,
    private val logger: AgentLogger,
    private val rootAvailable: () -> Boolean,
    private val screenshotExcludedPackages: () -> Set<String> = { emptySet() },
) : UiObserveBackend, UiActionBackend {

    override val selfPackage: String = context.packageName

    private val controller = RootShellDeviceController(logger, screenshotExcludedPackages, rootAvailable)
    private val observations = ConcurrentHashMap<String, ObservationEntry>()
    private val genCounter = AtomicLong(0L)
    @Volatile private var latest: ObservationRef? = null
    @Volatile private var latestCoordinateSpace: RootShellDeviceController.CoordinateSpace? = null

    // ---- UiObservationRegistry ----

    /** 代际校验：有无障碍快照时返回窗口当前代际（变了/服务换了→与绑定代际不符或 null→STALE）；root 观察无窗口代际，返回登记值。 */
    override fun genOf(observationId: String): Long? {
        val entry = observations[observationId] ?: return null
        val snapshot = entry.snapshot ?: return entry.gen
        return AgentAccessibilityService.current()?.currentGenerationOf(snapshot)
    }

    override fun latest(): ObservationRef? = latest

    override fun foregroundPackage(): String? =
        AgentAccessibilityService.current()?.currentPackageName()?.takeIf { it.isNotBlank() }

    override fun observationPackage(observationId: String): String? = observations[observationId]?.packageName

    override fun observedNode(observationId: String, index: Int): UiNodeProbe? =
        observations[observationId]?.elementObservation?.nodes?.firstOrNull { it.index == index }?.let { node ->
            UiNodeProbe(
                text = node.text.ifBlank { null },
                desc = node.desc.ifBlank { null },
                role = node.className.ifBlank { null },
                bounds = listOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom),
                clickable = node.clickable,
                password = node.password,
            )
        }

    override fun focusedInputIsPassword(): Boolean? = AgentAccessibilityService.current()?.focusedInputIsPassword()

    // ---- UiObserveBackend ----

    override fun observe(request: UiObserveRequest, env: ToolEnvironment): UiObserveResult {
        if (!env.accessibilityUsable && !env.rootAvailable) return UiObserveResult.PermissionRequired
        val obs = controller.observe(
            includeScreenshot = request.screenshot,
            includeUiTree = request.nodes,
            maxNodes = request.maxNodes,
        )
        val details = runCatching { JSONObject(obs.content) }.getOrNull()
        if (details?.optBoolean("ok", true) == false) {
            return UiObserveResult.Unavailable(details.optString("message", "无法读取当前屏幕"))
        }
        val element = obs.elementObservation
        val coord = obs.coordinateSpace
        val snapshot = element?.accessibilitySnapshot
        val gen = snapshot?.contentGeneration ?: genCounter.incrementAndGet()
        val observationId = element?.id ?: "obs-${genCounter.incrementAndGet()}"
        val pkg = element?.packageName?.takeIf { it.isNotBlank() }
        observations[observationId] = ObservationEntry(gen, pkg, element, snapshot, coord)
        latest = ObservationRef(observationId, gen)
        latestCoordinateSpace = coord
        val screen = runCatching { controller.screenDimensions() }.getOrNull()
        return UiObserveResult.Observed(
            observationId = observationId,
            gen = gen,
            packageName = pkg,
            coordWidth = coord?.screenshotWidth ?: screen?.first ?: 0,
            coordHeight = coord?.screenshotHeight ?: screen?.second ?: 0,
            focusedIndex = element?.nodes?.firstOrNull { it.focused }?.index,
            nodes = element?.nodes.orEmpty().map { it.toObservedNode() },
            nodesTruncated = element?.truncated ?: false,
            screenshotAttached = obs.image != null,
            screenshotQuality = if (obs.image != null) "default" else null,
            screenshot = obs.image,
            screenshotFailure = if (request.screenshot && obs.image == null) {
                details?.optJSONObject("screenshot")?.optString("failure")
                    ?.takeIf { it.isNotBlank() } ?: "SCREENSHOT_UNAVAILABLE"
            } else null,
        )
    }

    private fun RootShellDeviceController.UiNode.toObservedNode(): UiObservedNode = UiObservedNode(
        index = index,
        text = text.ifBlank { null },
        desc = desc.ifBlank { null },
        role = className.ifBlank { null },
        viewId = viewId.ifBlank { null },
        bounds = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
        editable = editable,
        password = password,
        enabled = enabled,
        actions = buildList {
            if (clickable) add("click")
            if (longClickable) add("long_click")
            if (scrollable) add("scroll")
        },
    )

    // ---- UiActionBackend ----

    override fun backend(env: ToolEnvironment): InjectionBackend = when {
        env.accessibilityUsable -> InjectionBackend.ACCESSIBILITY
        env.rootAvailable || rootAvailable() -> InjectionBackend.ROOT_INPUT
        else -> InjectionBackend.NONE
    }

    override fun readableNodeAtPoint(x: Double, y: Double): UiNodeProbe? {
        // 实时抓一棵树，命中覆盖该点、面积最小（最深）的可点击节点；读不到返回 null → readableTarget=false → 中央确认。
        val (sx, sy) = screenPoint(x, y)
        val obs = runCatching {
            controller.observe(includeScreenshot = false, includeUiTree = true, maxNodes = 120)
        }.getOrNull() ?: return null
        val nodes = obs.elementObservation?.nodes.orEmpty()
        val hit = nodes
            .filter { it.clickable && it.bounds.contains(sx, sy) }
            .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }
            ?: nodes
                .filter { it.bounds.contains(sx, sy) }
                .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }
            ?: return null
        return UiNodeProbe(
            text = hit.text.ifBlank { null },
            desc = hit.desc.ifBlank { null },
            role = hit.className.ifBlank { null },
            bounds = listOf(hit.bounds.left, hit.bounds.top, hit.bounds.right, hit.bounds.bottom),
            clickable = hit.clickable,
        )
    }

    override fun tap(request: UiTapRequest, env: ToolEnvironment): UiInjectResult {
        if (backend(env) == InjectionBackend.NONE) return UiInjectResult.PermissionRequired
        val before = foregroundPackage()
        val json = when (val target = request.target) {
            is UiTarget.Element -> {
                val eo = observations[target.observationId]?.elementObservation
                    ?: return UiInjectResult.NotActionable("观察已失效，重新 ui_observe")
                if (request.holdMs > 0) controller.longPressElement(eo, target.index, request.holdMs, allowGestureFallback = env.touchscreen)
                else controller.tapElement(eo, target.index, allowGestureFallback = env.touchscreen)
            }
            is UiTarget.Point -> screenPoint(target.x, target.y).let { (sx, sy) ->
                if (request.holdMs > 0) controller.longPress(sx, sy, request.holdMs) else controller.tap(sx, sy)
            }
            is UiTarget.Area -> screenPoint(target.centerX, target.centerY).let { (sx, sy) ->
                if (request.holdMs > 0) controller.longPress(sx, sy, request.holdMs) else controller.tap(sx, sy)
            }
        }
        return injectResult(json, methodFallback = if (request.holdMs > 0) "long_click" else "click", before = before)
    }

    override fun swipe(request: UiSwipeRequest, env: ToolEnvironment): UiInjectResult {
        if (backend(env) == InjectionBackend.NONE) return UiInjectResult.PermissionRequired
        val before = foregroundPackage()
        val (x1, y1) = screenPoint(request.x, request.y)
        val (x2, y2) = screenPoint(request.x2, request.y2)
        val json = controller.swipe(x1, y1, x2, y2, request.durationMs)
        return injectResult(json, methodFallback = "gesture", before = before)
    }

    override fun scroll(request: UiScrollRequest, env: ToolEnvironment): UiScrollResult {
        if (backend(env) == InjectionBackend.NONE) return UiScrollResult.PermissionRequired
        val before = foregroundPackage()
        val direction = request.direction.name.lowercase()
        val json = if (request.element != null) {
            val eo = observations[request.element.observationId]?.elementObservation
                ?: return UiScrollResult.NotActionable("观察已失效，重新 ui_observe")
            controller.scrollElement(eo, request.element.index, direction)
        } else {
            controller.scroll(direction)
        }
        val obj = parse(json) ?: return UiScrollResult.OutcomeUnknown
        if (obj.optBoolean("ok", false)) {
            val atBoundary = if (obj.isNull("at_boundary")) null else obj.optBoolean("at_boundary")
            return UiScrollResult.Finished(
                moved = obj.optBoolean("moved", false),
                atBoundary = atBoundary,
                afterPackage = foregroundPackage() ?: before,
            )
        }
        val code = obj.optString("code")
        return when {
            code.contains("MISMATCH") -> UiScrollResult.DirectionMismatch
            code == "ACTION_OUTCOME_UNKNOWN" -> UiScrollResult.OutcomeUnknown
            else -> UiScrollResult.NotActionable(obj.optString("message").ifBlank { code.ifBlank { "滚动未生效" } })
        }
    }

    override fun focus(element: UiTarget.Element, direction: ScrollDirection?, env: ToolEnvironment): UiInjectResult {
        val service = AgentAccessibilityService.current() ?: return UiInjectResult.PermissionRequired
        val snapshot = observations[element.observationId]?.snapshot ?: return UiInjectResult.NotActionable("观察已失效，请重新观察")
        val before = foregroundPackage()
        val key = when (direction) {
            ScrollDirection.UP -> android.view.View.FOCUS_UP
            ScrollDirection.DOWN -> android.view.View.FOCUS_DOWN
            ScrollDirection.LEFT -> android.view.View.FOCUS_LEFT
            ScrollDirection.RIGHT -> android.view.View.FOCUS_RIGHT
            null -> null
        }
        val result = service.focusNode(snapshot, element.index, key)
        return when {
            result.ok -> UiInjectResult.Dispatched("ACTION_FOCUS", foregroundPackage(), foregroundPackage() != before)
            result.code == "ACTION_OUTCOME_UNKNOWN" -> UiInjectResult.OutcomeUnknown
            else -> UiInjectResult.NotActionable(result.message)
        }
    }

    override fun input(request: UiInputRequest, env: ToolEnvironment): UiInputResult {
        if (backend(env) == InjectionBackend.NONE) return UiInputResult.PermissionRequired
        val before = foregroundPackage()
        // append 和 replace 都按 index 指定的输入框写（没给 index 写当前焦点）；接在哪、用什么方式写由控制器决定。
        val eo = request.element?.let { observations[it.observationId]?.elementObservation }
        val json = controller.writeText(
            text = request.text,
            index = request.element?.index,
            observation = eo,
            append = request.mode == UiInputMode.APPEND,
        )
        val obj = parse(json) ?: return UiInputResult.OutcomeUnknown
        if (!obj.optBoolean("ok", false)) {
            val code = obj.optString("code")
            return if (code == "ACTION_OUTCOME_UNKNOWN") UiInputResult.OutcomeUnknown
            else UiInputResult.NotActionable(obj.optString("message").ifBlank { code.ifBlank { "无法写入" } }, code)
        }
        // 规范 9.5 输入文字指示：写进去之后给这个输入框描边。
        obj.optJSONArray("bounds")?.takeIf { it.length() == 4 }?.let { b ->
            GestureIndicator.showInput(context, android.graphics.Rect(b.optInt(0), b.optInt(1), b.optInt(2), b.optInt(3)))
        }
        var submitted = false
        var submitError: String? = null
        if (request.submit) {
            val submit = parse(controller.pressKey("ENTER"))
            submitted = submit?.optBoolean("ok", false) ?: false
            if (!submitted) submitError = submit?.optString("message")?.ifBlank { null } ?: "回车没有被接受"
        }
        val after = foregroundPackage()
        val verified = obj.optBoolean("verified", true)
        return UiInputResult.Written(
            method = obj.optString("method").ifBlank { "set_text" },
            readbackMatches = verified,
            readbackLength = request.text.length,
            submitted = submitted,
            afterPackage = after ?: before,
            windowChanged = after != null && after != before,
            readback = if (verified || obj.isNull("readback")) null else obj.optString("readback"),
            clipboardWritten = obj.optBoolean("clipboard_written", false),
            submitError = submitError,
        )
    }

    override fun key(request: UiKeyRequest, env: ToolEnvironment): UiInjectResult {
        if (backend(env) == InjectionBackend.NONE) return UiInjectResult.PermissionRequired
        val button = when (request.key) {
            UiKeyCode.BACK -> "BACK"
            UiKeyCode.HOME -> "HOME"
            UiKeyCode.RECENTS -> "RECENTS"
            UiKeyCode.ENTER -> "ENTER"
            UiKeyCode.NOTIFICATIONS -> "NOTIFICATIONS"
            UiKeyCode.QUICK_SETTINGS -> "QUICK_SETTINGS"
            // 控制器 pressKey 暂不支持这些系统动作，保守返回不可执行（不冒领 ok）。
            UiKeyCode.LOCK_SCREEN, UiKeyCode.SCREENSHOT, UiKeyCode.DISMISS_NOTIFICATIONS ->
                return UiInjectResult.NotActionable("该系统键暂不支持")
        }
        val before = foregroundPackage()
        return injectResult(controller.pressKey(button), methodFallback = "key", before = before)
    }

    override fun waitFor(request: UiWaitRequest, env: ToolEnvironment, checkCancelled: () -> Unit): UiWaitResult {
        checkCancelled()
        if (request.durationMs != null) {
            val waitMs = minOf(request.durationMs, request.timeoutMs).toLong().coerceAtLeast(0)
            val start = System.nanoTime()
            while (true) {
                checkCancelled()
                val remaining = waitMs - (System.nanoTime() - start) / 1_000_000
                if (remaining <= 0) break
                Thread.sleep(minOf(remaining, 100))
            }
            return UiWaitResult.Finished(matched = request.durationMs <= request.timeoutMs,
                elapsedMs = (System.nanoTime() - start) / 1_000_000, node = null)
        }
        if (!env.accessibilityUsable && !env.rootAvailable) return UiWaitResult.PermissionRequired
        val start = System.currentTimeMillis()
        val json = when {
            request.text != null -> controller.waitForText(
                request.text, request.timeoutMs, includeDesc = true, matchMode = request.match.name.lowercase(),
                checkCancelled = checkCancelled,
            )
            request.packageName != null -> controller.waitForPackage(request.packageName, request.timeoutMs, checkCancelled)
            else -> return UiWaitResult.Finished(matched = false, elapsedMs = 0, node = null)
        }
        val obj = parse(json)
        val matched = obj?.optBoolean("ok", false) == true && obj.optBoolean("matched", true)
        return UiWaitResult.Finished(matched = matched, elapsedMs = System.currentTimeMillis() - start, node = null)
    }

    // ---- 工具 ----

    /** 把目标坐标（最近一次观察的坐标空间，通常是截图像素）换算为真实屏幕坐标。 */
    private fun screenPoint(x: Double, y: Double): Pair<Int, Int> {
        val cs = latestCoordinateSpace
        if (cs != null) {
            val p = runCatching { cs.fromScreenshot(x.toInt(), y.toInt()) }.getOrNull()
            if (p != null) return p.x to p.y
        }
        return x.toInt() to y.toInt()
    }

    private fun parse(json: String): JSONObject? = runCatching { JSONObject(json) }.getOrNull()

    private fun injectResult(json: String, methodFallback: String, before: String?): UiInjectResult {
        val obj = parse(json) ?: return UiInjectResult.OutcomeUnknown
        if (obj.optBoolean("ok", false)) {
            val after = foregroundPackage()
            return UiInjectResult.Dispatched(
                method = obj.optString("method").ifBlank { methodFallback },
                afterPackage = after ?: before,
                windowChanged = after != null && after != before,
            )
        }
        val code = obj.optString("code")
        return when {
            code == "ACTION_OUTCOME_UNKNOWN" -> UiInjectResult.OutcomeUnknown
            code == "ACCESSIBILITY_UNAVAILABLE" || code == "PERMISSION_REQUIRED" -> UiInjectResult.PermissionRequired
            code == "SYSTEM_REJECTED" -> UiInjectResult.SystemRejected
            else -> UiInjectResult.NotActionable(obj.optString("message").ifBlank { code.ifBlank { "未执行" } })
        }
    }
}
