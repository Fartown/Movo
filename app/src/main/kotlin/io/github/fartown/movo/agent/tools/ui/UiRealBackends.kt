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
import java.util.concurrent.atomic.AtomicLong

/**
 * 屏幕 UI 领域真实后端。
 *
 * - 剪贴板读写用 Android [ClipboardManager]。
 * - 屏幕观察与注入委托 `agent/device/RootShellDeviceController`：它组合无障碍服务（节点树、dispatchGesture、
 *   performAction、全局动作、截图）和 Root 兜底（uiautomator、input、statusbar），并在动作后等界面稳定。
 * - [RealUiScreenBackend] 把控制器的 JSON 结果翻译成类型化结果，并登记每次观察：index 的内容代际、
 *   坐标的坐标系（[CoordinateFrame]）、coord_space 到真实屏幕的换算。
 * - 读不到或做不到时不冒领 ok：观察 / 等待返回 PermissionRequired，结果不确定的注入返回 OUTCOME_UNKNOWN。
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

/** 一次观察的登记条目：代际、包名、坐标系，以及回放动作所需的元素观察 / 快照 / 坐标空间。 */
private data class ObservationEntry(
    val gen: Long,
    val packageName: String?,
    val elementObservation: RootShellDeviceController.ElementObservation?,
    val snapshot: AgentAccessibilityService.NodeSnapshot?,
    val coordinateSpace: RootShellDeviceController.CoordinateSpace?,
    val frame: CoordinateFrame? = null,
)

/**
 * 最近几次观察的登记：只留最近 [capacity] 次，更早的挤掉（每次都带着节点快照，最多 120 个节点，
 * 长任务一直攒着会占内存）。被挤掉的 observation_id 按已过期处理，模型重新观察即可。
 */
internal class RecentObservations<T>(private val capacity: Int) {
    private val entries = LinkedHashMap<String, T>()

    @Synchronized
    operator fun get(id: String): T? = entries[id]

    @Synchronized
    operator fun set(id: String, value: T) {
        entries.remove(id)
        entries[id] = value
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun size(): Int = entries.size
}

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
    private val observations = RecentObservations<ObservationEntry>(KEPT_OBSERVATIONS)
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

    override fun observationFrame(observationId: String): CoordinateFrame? = observations[observationId]?.frame

    /** 现在的坐标系：屏幕实际宽高、前台窗口代际（无障碍连着才有）、前台应用。都是轻量读取，不抓树。 */
    override fun currentFrame(): CoordinateFrame {
        val (width, height) = displaySize() ?: (0 to 0)
        return CoordinateFrame(
            width = width,
            height = height,
            windowGen = AgentAccessibilityService.current()?.windowFrameGeneration(),
            packageName = foregroundPackage(),
        )
    }

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
        // 窗口代际在观察之前取：观察途中换了窗口，下一次坐标动作按坐标系已变处理（宁可多观察一次）。
        val frameBefore = currentFrame()
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
        // 只要截图（nodes=false）时没有节点快照：前台应用照样告诉模型（观察前读到的，或控制器读到的焦点窗口）。
        val foreground = pkg ?: frameBefore.packageName
            ?: details?.optJSONObject("focus")?.optString("package")?.takeIf { it.isNotBlank() }
        val frame = frameBefore.copy(packageName = foreground)
        val shot = details?.optJSONObject("screenshot")
        val screen = runCatching { controller.screenDimensions() }.getOrNull()
        val coordWidth = coord?.screenshotWidth ?: screen?.first ?: 0
        val coordHeight = coord?.screenshotHeight ?: screen?.second ?: 0
        observations[observationId] = ObservationEntry(gen, pkg, element, snapshot, coord, frame)
        latest = ObservationRef(observationId, gen, coordWidth, coordHeight)
        latestCoordinateSpace = coord
        // 截图只截到部分窗口（多窗口、弹窗、输入法截不到）：标出来，免得模型把缺的内容当成屏幕上没有。
        val partial = obs.image != null && shot?.optBoolean("partial", false) == true
        return UiObserveResult.Observed(
            observationId = observationId,
            gen = gen,
            packageName = foreground,
            coordWidth = coordWidth,
            coordHeight = coordHeight,
            focusedIndex = element?.nodes?.firstOrNull { it.focused }?.index,
            nodes = element?.nodes.orEmpty().map { it.toObservedNode() },
            nodesTruncated = element?.truncated ?: false,
            screenshotAttached = obs.image != null,
            screenshotQuality = if (obs.image != null) {
                shot?.optString("quality")?.takeIf { it.isNotBlank() } ?: "complete"
            } else null,
            screenshot = obs.image,
            screenshotFailure = if (request.screenshot && obs.image == null) {
                shot?.optString("failure")?.takeIf { it.isNotBlank() } ?: "SCREENSHOT_UNAVAILABLE"
            } else null,
            screenshotPartial = partial,
            screenshotMissingWindows = if (partial) shot.optJSONArray("missing_window_ids")?.length() ?: 0 else 0,
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
        // 实时抓一棵树，命中覆盖该点、面积最小（最深）的可点击节点；读不到返回 null → readableTarget=false。
        // 抓树慢（只有 Root 时是 uiautomator dump），工具只在要弹确认卡时才调用。
        val (sx, sy) = screenPoint(x, y) ?: return null
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
        val (json, touch) = when (val target = request.target) {
            is UiTarget.Element -> {
                val eo = observations[target.observationId]?.elementObservation
                    ?: return UiInjectResult.NotActionable("观察已失效，重新 ui_observe")
                val json = if (request.holdMs > 0) {
                    controller.longPressElement(eo, target.index, request.holdMs, allowGestureFallback = env.touchscreen)
                } else {
                    controller.tapElement(eo, target.index, allowGestureFallback = env.touchscreen)
                }
                // 节点动作的指示画在节点中心（和旧引擎一样）。
                json to eo.nodes.firstOrNull { it.index == target.index }
                    ?.let { node -> UiTouch.Press(node.centerX, node.centerY, request.holdMs) }
            }
            is UiTarget.Point -> pressAt(target.x, target.y, request.holdMs) ?: return outOfScreen(target.x, target.y)
            is UiTarget.Area -> pressAt(target.centerX, target.centerY, request.holdMs)
                ?: return outOfScreen(target.centerX, target.centerY)
        }
        return injectResult(json, methodFallback = if (request.holdMs > 0) "long_click" else "click", before = before, touch = touch)
    }

    /** 坐标点按 / 长按：换算到屏幕后执行，带回按下的位置；越出 coord_space 返回 null。 */
    private fun pressAt(x: Double, y: Double, holdMs: Int): Pair<String, UiTouch>? {
        val (sx, sy) = screenPoint(x, y) ?: return null
        val json = guardPoint { if (holdMs > 0) controller.longPress(sx, sy, holdMs) else controller.tap(sx, sy) }
        return json to UiTouch.Press(sx, sy, holdMs)
    }

    override fun swipe(request: UiSwipeRequest, env: ToolEnvironment): UiInjectResult {
        if (backend(env) == InjectionBackend.NONE) return UiInjectResult.PermissionRequired
        val before = foregroundPackage()
        val (x1, y1) = screenPoint(request.x, request.y) ?: return outOfScreen(request.x, request.y)
        val (x2, y2) = screenPoint(request.x2, request.y2) ?: return outOfScreen(request.x2, request.y2)
        val holdMs = request.holdMs ?: 0
        // hold_ms：无障碍连续笔画先按住再拖；控制器在没有无障碍时直接报不可用，不回退 Root（input swipe 按不住）。
        val json = guardPoint {
            if (holdMs > 0) controller.holdAndDrag(x1, y1, x2, y2, holdMs, request.durationMs)
            else controller.swipe(x1, y1, x2, y2, request.durationMs)
        }
        return injectResult(
            json, methodFallback = "gesture", before = before,
            touch = UiTouch.Drag(x1, y1, x2, y2, request.durationMs),
        )
    }

    override fun findText(text: String): UiNodeProbe? =
        screenNodes()?.firstMatching(textMatcher(text, WaitMatch.CONTAINS))

    override fun showTouch(touch: UiTouch) = UiTouchFeedback.show(context, touch)

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
        // 规范 9.5 输入文字指示：写进去之后给这个输入框描边（和点按、滑动指示一样只在触屏设备上画）。
        if (env.touchscreen) {
            obj.optJSONArray("bounds")?.takeIf { it.length() == 4 }?.let { b ->
                GestureIndicator.showInput(context, android.graphics.Rect(b.optInt(0), b.optInt(1), b.optInt(2), b.optInt(3)))
            }
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
            lineBreaksLost = obj.optBoolean("line_breaks_lost", false),
        )
    }

    override fun key(request: UiKeyRequest, env: ToolEnvironment): UiInjectResult {
        if (backend(env) == InjectionBackend.NONE) return UiInjectResult.PermissionRequired
        // 控制器按键名：无障碍全局动作优先，做不到时有 Root 就用按键 / statusbar 命令回退。
        val button = when (request.key) {
            UiKeyCode.BACK -> "BACK"
            UiKeyCode.HOME -> "HOME"
            UiKeyCode.RECENTS -> "RECENTS"
            UiKeyCode.ENTER -> "ENTER"
            UiKeyCode.NOTIFICATIONS -> "NOTIFICATIONS"
            UiKeyCode.QUICK_SETTINGS -> "QUICK_SETTINGS"
            UiKeyCode.LOCK_SCREEN -> "LOCK_SCREEN"
            UiKeyCode.SCREENSHOT -> "SCREENSHOT"
            UiKeyCode.DISMISS_NOTIFICATIONS -> "DISMISS_NOTIFICATIONS"
        }
        val before = foregroundPackage()
        return injectResult(controller.pressKey(button), methodFallback = "key", before = before)
    }

    override fun waitFor(request: UiWaitRequest, env: ToolEnvironment, checkCancelled: () -> Unit): UiWaitResult {
        checkCancelled()
        if (request.durationMs != null) {
            // 只等时长：等满 duration_ms，不受 timeout_ms（等文字 / 应用的超时）截断。
            val waitMs = request.durationMs.toLong().coerceAtLeast(0)
            val start = System.nanoTime()
            while (true) {
                checkCancelled()
                val remaining = waitMs - (System.nanoTime() - start) / 1_000_000
                if (remaining <= 0) break
                Thread.sleep(minOf(remaining, 100))
            }
            return UiWaitResult.Finished(matched = true, elapsedMs = (System.nanoTime() - start) / 1_000_000, node = null)
        }
        if (!env.accessibilityUsable && !env.rootAvailable) return UiWaitResult.PermissionRequired
        if (request.text != null) return waitForText(request, request.text, checkCancelled)
        val start = System.currentTimeMillis()
        val json = when {
            request.packageName != null -> controller.waitForPackage(request.packageName, request.timeoutMs, checkCancelled)
            else -> return UiWaitResult.Finished(matched = false, elapsedMs = 0, node = null)
        }
        val obj = parse(json)
        val matched = obj?.optBoolean("ok", false) == true && obj.optBoolean("matched", true)
        // 没等到时告诉模型现在前台是谁（权限弹窗、广告页、别的应用截走了），和重构前的 last_package 一样。
        val current = if (matched) null else {
            obj?.optString("last_package")?.takeIf { it.isNotBlank() } ?: foregroundPackage()
        }
        return UiWaitResult.Finished(
            matched = matched, elapsedMs = System.currentTimeMillis() - start, node = null, currentPackage = current,
        )
    }

    /**
     * 等文字出现或消失（[UiWaitRequest.gone]）：每隔一会儿读一次当前屏幕节点，按 [textCheck] 判定。
     * 出现时带回命中的节点；读不到屏幕不算消失；到超时返回 matched=false。
     */
    private fun waitForText(request: UiWaitRequest, text: String, checkCancelled: () -> Unit): UiWaitResult {
        val needle = text.trim()
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        while (true) {
            checkCancelled()
            val check = textCheck(screenNodes(), needle, request.match, request.gone, request.includeDesc)
            if (check.met) return UiWaitResult.Finished(matched = true, elapsedMs = elapsed(), node = check.node)
            val remaining = request.timeoutMs - elapsed()
            if (remaining <= 0) return UiWaitResult.Finished(matched = false, elapsedMs = elapsed(), node = null)
            Thread.sleep(minOf(remaining, TEXT_POLL_MS))
        }
    }

    /** 当前屏幕节点（单次查询，不登记观察）；既没有无障碍也没有 Root 时返回 null。 */
    private fun screenNodes(): List<UiNodeProbe>? =
        controller.currentNodes(SCREEN_QUERY_NODES)?.map { node ->
            UiNodeProbe(
                text = node.text.ifBlank { null },
                desc = node.desc.ifBlank { null },
                role = node.className.ifBlank { null },
                bounds = listOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom),
                clickable = node.clickable,
                password = node.password,
            )
        }

    // ---- 工具 ----

    /**
     * 把目标坐标（最近一次观察的坐标空间，通常是截图像素）换算为真实屏幕坐标。
     * 越出截图范围时返回 null，不悄悄按屏幕坐标用（那会点到别处）。
     */
    private fun screenPoint(x: Double, y: Double): Pair<Int, Int>? {
        if (x < 0 || y < 0) return null
        val cs = latestCoordinateSpace ?: return x.toInt() to y.toInt()
        val p = runCatching { cs.fromScreenshot(x.toInt(), y.toInt()) }.getOrNull() ?: return null
        return p.x to p.y
    }

    /** 坐标越界（工具层已按 coord_space 预检，这里兜底）：说清楚，不报内部错误。 */
    private fun outOfScreen(x: Double, y: Double): UiInjectResult =
        UiInjectResult.NotActionable("坐标 (${x.toInt()}, ${y.toInt()}) 超出屏幕范围，按最近一次 ui_observe 的 coord_space 给坐标")

    /** 控制器对越出屏幕的坐标用 require 抛错：转成不可执行的结果，不让管线兜成「内部错误，可以重试」。 */
    private inline fun guardPoint(action: () -> String): String =
        try {
            action()
        } catch (error: IllegalArgumentException) {
            JSONObject().put("ok", false).put("code", "INVALID_ARGUMENT")
                .put("message", error.message ?: "坐标超出屏幕范围").toString()
        }

    /** 屏幕实际宽高（随横竖屏互换）。无障碍连着时用它的窗口服务，否则用默认显示屏。 */
    private fun displaySize(): Pair<Int, Int>? =
        AgentAccessibilityService.current()?.displaySize() ?: runCatching {
            val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return null
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(point)
            point.x to point.y
        }.getOrNull()?.takeIf { (w, h) -> w > 0 && h > 0 }

    private fun parse(json: String): JSONObject? = runCatching { JSONObject(json) }.getOrNull()

    private fun injectResult(json: String, methodFallback: String, before: String?, touch: UiTouch? = null): UiInjectResult {
        val obj = parse(json) ?: return UiInjectResult.OutcomeUnknown
        if (obj.optBoolean("ok", false)) {
            val after = foregroundPackage()
            return UiInjectResult.Dispatched(
                method = obj.optString("method").ifBlank { methodFallback },
                afterPackage = after ?: before,
                windowChanged = after != null && after != before,
                touch = touch,
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

    private companion object {
        /** ui_wait 等文字时多久读一次屏幕。 */
        const val TEXT_POLL_MS = 350L
        /** 等文字、滚动找字时一次读多少个节点。 */
        const val SCREEN_QUERY_NODES = 120
        /** 观察登记只留最近几次：够模型回头用前几次的 index，又不让长任务一直攒节点快照。 */
        const val KEPT_OBSERVATIONS = 8
    }
}
