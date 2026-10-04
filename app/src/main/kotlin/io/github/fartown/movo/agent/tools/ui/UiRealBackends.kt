package io.github.fartown.movo.agent.tools.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import io.github.fartown.movo.agent.tools.core.InjectionBackend
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.core.AgentLogger
import java.util.concurrent.ConcurrentHashMap

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
) : ClipboardReadBackend {
    override fun read(): ClipboardReadResult {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ClipboardReadResult.Unavailable
        // Android 10+ 仅前台应用可读剪贴板；非前台时系统返回 null。
        // TODO(ui)：null 既可能是“空”也可能是“被拒”，需用前台状态区分（无障碍是否豁免后台限制未核实，§16）。
        val clip = cm.primaryClip ?: return ClipboardReadResult.Empty
        if (clip.itemCount == 0) return ClipboardReadResult.Empty
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
// 屏幕观察 + 注入（登记表真实，观察/注入留 TODO 占位）
// ---------------------------------------------------------------------------

/** 一次观察的登记条目。 */
private data class ObservationEntry(val gen: Long, val packageName: String?)

/**
 * 同一对象实现 [UiObserveBackend] 与 [UiActionBackend]：观察登记与动作共享代际状态。
 * Provider 用它接通 ui_observe 与所有动作工具。
 */
internal class RealUiScreenBackend(
    private val context: Context,
    private val logger: AgentLogger,
    private val rootAvailable: () -> Boolean,
) : UiObserveBackend, UiActionBackend {

    override val selfPackage: String = context.packageName

    private val observations = ConcurrentHashMap<String, ObservationEntry>()
    @Volatile private var latest: ObservationRef? = null

    // ---- UiObservationRegistry ----

    override fun genOf(observationId: String): Long? = observations[observationId]?.gen

    override fun latest(): ObservationRef? = latest

    override fun foregroundPackage(): String? {
        // TODO(ui)：从无障碍焦点窗取前台包（AgentAccessibilityService）；读不到返回 null → 中央确认。
        return null
    }

    override fun observationPackage(observationId: String): String? = observations[observationId]?.packageName

    // ---- UiObserveBackend ----

    override fun observe(request: UiObserveRequest, env: ToolEnvironment): UiObserveResult {
        if (!env.accessibilityUsable && !env.rootAvailable) return UiObserveResult.PermissionRequired
        // TODO(ui)：接 AgentAccessibilityService 读无障碍树；代际 gen 绑窗口指纹（serviceToken/windowId/
        // package/contentGeneration，AS:160-168）+ 方向；坐标空间按服务商固定缩放（AgentModelImageEncoder 要改）；
        // root 下用 uiautomator 读非无障碍树须显式声明并计入“不可信内容”污点；截图用 images() 附图。
        logger.debug { "ui_observe TODO: 观察后端尚未接线" }
        return UiObserveResult.PermissionRequired
    }

    // ---- UiActionBackend ----

    override fun backend(env: ToolEnvironment): InjectionBackend = when {
        env.accessibilityUsable -> InjectionBackend.ACCESSIBILITY
        env.rootAvailable || rootAvailable() -> InjectionBackend.ROOT_INPUT
        else -> InjectionBackend.NONE
    }

    override fun readableNodeAtPoint(x: Double, y: Double): UiNodeProbe? {
        // TODO(ui)：实时抓树命中覆盖该点最深可点击节点（提交点识别）；读不到返回 null → readableTarget=false。
        return null
    }

    override fun tap(request: UiTapRequest, env: ToolEnvironment): UiInjectResult {
        // TODO(ui)：ACCESSIBILITY 走 performAction(ACTION_CLICK)/dispatchGesture；ROOT_INPUT 走 input tap。
        return UiInjectResult.OutcomeUnknown
    }

    override fun swipe(request: UiSwipeRequest, env: ToolEnvironment): UiInjectResult {
        // TODO(ui)：dispatchGesture 轨迹 / input swipe。
        return UiInjectResult.OutcomeUnknown
    }

    override fun scroll(request: UiScrollRequest, env: ToolEnvironment): UiScrollResult {
        // TODO(ui)：performAction(ACTION_SCROLL_*) 或手势，读 moved/at_boundary/direction_mismatch。
        return UiScrollResult.OutcomeUnknown
    }

    override fun input(request: UiInputRequest, env: ToolEnvironment): UiInputResult {
        // TODO(ui)：ACTION_SET_TEXT / 粘贴，回读焦点文字判断一致；append 无法插入返回 NotActionable。
        return UiInputResult.OutcomeUnknown
    }

    override fun key(request: UiKeyRequest, env: ToolEnvironment): UiInjectResult {
        // TODO(ui)：GLOBAL_ACTION_* / keyevent；enter 无焦点返回 NotActionable。
        return UiInjectResult.OutcomeUnknown
    }

    override fun waitFor(request: UiWaitRequest, env: ToolEnvironment): UiWaitResult {
        if (request.durationMs != null) {
            // 只等时长：真实可实现（sleep 到时长或 timeout 的较小者）。
            val waitMs = minOf(request.durationMs, request.timeoutMs).toLong().coerceAtLeast(0)
            val start = System.currentTimeMillis()
            runCatching { Thread.sleep(waitMs) }
            return UiWaitResult.Finished(matched = true, elapsedMs = System.currentTimeMillis() - start, node = null)
        }
        if (!env.accessibilityUsable && !env.rootAvailable) return UiWaitResult.PermissionRequired
        // TODO(ui)：轮询无障碍树等 text 出现/消失或 package 到前台；超时返回 matched=false（不是错误）。
        return UiWaitResult.Finished(matched = false, elapsedMs = request.timeoutMs.toLong(), node = null)
    }
}
