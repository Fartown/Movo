package io.github.fartown.movo.agent.runtime

import io.github.fartown.movo.flavor.FlavorModule
import io.github.fartown.movo.BuildConfig
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityService
import io.github.fartown.movo.agent.accessibility.PackageWindowVisibility
import io.github.fartown.movo.agent.voice.session.VoiceSurfaceTracker
import io.github.fartown.movo.core.AgentLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 把外部入口从前台工具的真实操作对象中隔离开。
 *
 * 关闭动作、窗口稳定确认和首张截图排除共享同一份入口描述，避免各层分别猜测入口状态。
 */
internal class EntrySurfaceGuard private constructor(
    internal val targetPackageName: String?,
    private val logger: AgentLogger,
    private val ownedSurfaceDismissal: (() -> Boolean)?,
    private val surfaces: EntrySurfaceActions,
) {
    /** 串行化关闭尝试：正在关时另一个调用等它的结果，不会因为「正在关」被当成失败（工具因此被拒）。 */
    private val attemptLock = Any()
    private val dismissalCompleted = AtomicBoolean(false)
    // 无障碍窗口可能早于退场 Surface 消失；必须由关闭后的首张截图消费，不能在关闭确认时清除。
    private val screenshotExclusionPending = AtomicBoolean(targetPackageName != null)

    /** 入口窗口确实为前台操作关过（或确认已不在）。 */
    val wasTriggered: Boolean
        get() = dismissalCompleted.get()

    /**
     * 关闭入口窗口；关成（或确认已不在）后恒为 true。没关成时不留下「已触发」标记：下一个前台事件或工具调用会重新判断，
     * 不会整轮卡在「未就绪」（悬浮球、光晕都不出，UI 工具全被拒）。
     */
    fun dismissOnce(): Boolean {
        if (dismissalCompleted.get()) return true
        synchronized(attemptLock) {
            if (dismissalCompleted.get()) return true
            val completed = attemptDismissal()
            if (completed) dismissalCompleted.set(true)
            return completed
        }
    }

    private fun attemptDismissal(): Boolean {
        ownedSurfaceDismissal?.let { dismiss ->
            val startedAt = System.nanoTime()
            val completed = runCatching(dismiss).getOrDefault(false)
            val waitedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
            if (completed) {
                logger.debug {
                    "Agent runtime owned entry surface dismissed before foreground operation " +
                        "waitedMs=$waitedMillis"
                }
            } else {
                // 自有入口关闭是幂等定向操作，失败后允许再次确认，不会误退底层 App。
                logger.warn(
                    "Agent runtime owned entry surface dismiss incomplete before foreground " +
                        "operation: waitedMs=$waitedMillis"
                )
            }
            return completed
        }

        if (!surfaces.available) {
            logger.warn("Agent runtime entry surface dismiss skipped: accessibility service unavailable")
            return false
        }

        val packageName = targetPackageName
        val visibility = packageName?.let(surfaces::visibility)
        when (EntrySurfaceDismissPolicy.decide(packageName, visibility)) {
            EntrySurfaceDismissPolicy.Decision.ALREADY_GONE -> {
                if (!surfaces.awaitGone(packageName!!)) {
                    logger.warn(
                        "Agent runtime entry surface absence was not stable; keep screenshot " +
                            "exclusion and retry later: package=$packageName",
                    )
                    return false
                }
                logger.debug {
                    "Agent runtime entry surface already gone before foreground operation " +
                        "package=$packageName"
                }
                return true
            }
            EntrySurfaceDismissPolicy.Decision.DEFER -> {
                logger.warn(
                    "Agent runtime entry surface visibility unknown; keep screenshot exclusion " +
                        "and retry later: package=$packageName",
                )
                return false
            }
            EntrySurfaceDismissPolicy.Decision.SEND_BACK -> Unit
        }
        val startedAt = System.nanoTime()
        val actionResult = surfaces.back()
        val windowGone = packageName?.let(surfaces::awaitGone) ?: actionResult.ok
        val waitedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
        val completed = if (packageName == null) actionResult.ok else windowGone

        if (completed) {
            logger.debug {
                "Agent runtime entry surface dismissed before foreground operation " +
                    "package=$packageName visibilityBefore=$visibility waitedMs=$waitedMillis"
            }
        } else {
            // 不留「已触发」：下次先重新看入口窗口还在不在（已消失就不再返回），未知入口只在返回动作本身没执行时才会再发。
            logger.warn(
                "Agent runtime entry surface dismiss incomplete before foreground operation: " +
                    "actionCode=${actionResult.code} windowGone=$windowGone package=$packageName " +
                    "visibilityBefore=$visibility waitedMs=$waitedMillis"
            )
        }
        return completed
    }

    fun consumeScreenshotExcludedPackages(): Set<String> {
        val packageName = targetPackageName ?: return emptySet()
        return if (screenshotExclusionPending.compareAndSet(true, false)) {
            setOf(packageName)
        } else {
            emptySet()
        }
    }

    companion object {
        fun from(
            handoff: AgentRuntimeWire.EntryHandoff?,
            logger: AgentLogger,
            movoVoiceSurfaceDismissal: (() -> Boolean)? = null,
            movoPagesDismissal: () -> Boolean = { MovoOwnedPages.dismissForForegroundOperation() },
            surfaces: EntrySurfaceActions = AccessibilityEntrySurfaces,
        ): EntrySurfaceGuard? {
            if (handoff?.dismissEntrySurfaceOnForegroundOperation != true) return null
            val packageName = when (handoff.source) {
                BREENO_HANDOFF_SOURCE -> BREENO_PACKAGE_NAME
                XIAOAI_HANDOFF_SOURCE -> XIAOAI_PACKAGE_NAME
                AgentRuntimeWire.MOVO_VOICE_HANDOFF_SOURCE,
                AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE -> MOVO_PACKAGE_NAME
                else -> null
            }
            val ownedSurfaceDismissal: (() -> Boolean)? = when (handoff.source) {
                AgentRuntimeWire.MOVO_VOICE_HANDOFF_SOURCE -> movoVoiceSurfaceDismissal
                // App 内发起的语音轮次：入口是 Movo 自己的页面（主界面、对话浮层、语音面板）。只用 Movo 自己的方式收起，
                // 从不发全局返回——返回会落在被操作的 App 上；Movo 的页面已不在前台时视为入口已关。
                AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE -> {
                    {
                        runCatching { movoVoiceSurfaceDismissal?.invoke() }
                        movoPagesDismissal()
                    }
                }
                else -> null
            }
            return EntrySurfaceGuard(packageName, logger, ownedSurfaceDismissal, surfaces)
        }

        private const val BREENO_HANDOFF_SOURCE = "breeno"
        private const val BREENO_PACKAGE_NAME = "com.heytap.speechassist"
        private const val XIAOAI_HANDOFF_SOURCE = "xiaoai"
        private const val XIAOAI_PACKAGE_NAME = "com.miui.voiceassist"
        private const val MOVO_PACKAGE_NAME = BuildConfig.APPLICATION_ID
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

internal object EntrySurfaceDismissPolicy {
    enum class Decision {
        ALREADY_GONE,
        SEND_BACK,
        DEFER,
    }

    fun decide(
        targetPackageName: String?,
        visibility: PackageWindowVisibility?,
    ): Decision = when {
        targetPackageName == null -> Decision.SEND_BACK
        visibility == PackageWindowVisibility.GONE -> Decision.ALREADY_GONE
        visibility == PackageWindowVisibility.VISIBLE -> Decision.SEND_BACK
        else -> Decision.DEFER
    }
}

/** 关外部入口要用到的窗口能力（真实实现走无障碍；测试换假实现）。 */
internal interface EntrySurfaceActions {
    val available: Boolean
    fun visibility(packageName: String): PackageWindowVisibility
    fun awaitGone(packageName: String): Boolean
    fun back(): BackResult

    data class BackResult(val ok: Boolean, val code: String = "")
}

internal object AccessibilityEntrySurfaces : EntrySurfaceActions {
    override val available: Boolean get() = AgentAccessibilityService.current() != null

    override fun visibility(packageName: String): PackageWindowVisibility =
        AgentAccessibilityService.current()?.packageWindowVisibility(packageName) ?: PackageWindowVisibility.UNKNOWN

    override fun awaitGone(packageName: String): Boolean =
        AgentAccessibilityService.current()?.awaitPackageWindowGone(packageName) == true

    override fun back(): EntrySurfaceActions.BackResult {
        val result = AgentAccessibilityService.current()?.globalActionResult("BACK")
            ?: return EntrySurfaceActions.BackResult(ok = false, code = "ACCESSIBILITY_UNAVAILABLE")
        return EntrySurfaceActions.BackResult(result.ok, result.code)
    }
}

/**
 * 收起 Movo 自己在前台的页面（主界面、对话浮层等），给前台操作让出屏幕。
 *
 * 只用 Movo 自己的方式：对话浮层先按 Q4 收成悬浮球再退到后台，其余页面 moveTaskToBack；等到它们都不可见（onStop）为止。
 * 没有 Movo 页面可见时直接视为已关。超时返回 false，由调用方下次重试（不会误退底层 App）。
 */
internal class MovoPageDismisser(
    /** 当前可见（started 未 stopped）的 Movo 页面数。 */
    private val visiblePageCount: () -> Int,
    /** 对话浮层收成悬浮球并退到后台（没有浮层时直接返回）。 */
    private val hideConversationSheet: () -> Boolean,
    /** 把仍可见的 Movo 页面所在任务移到后台；返回是否都已发起。 */
    private val moveVisiblePagesToBack: () -> Boolean,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
) {
    fun dismiss(): Boolean {
        if (visiblePageCount() == 0) return true
        runCatching(hideConversationSheet)
        if (visiblePageCount() == 0) return true
        if (!runCatching(moveVisiblePagesToBack).getOrDefault(false)) return visiblePageCount() == 0
        val deadline = clock() + timeoutMillis
        while (clock() < deadline) {
            if (visiblePageCount() == 0) return true
            sleep(POLL_MS)
        }
        return visiblePageCount() == 0
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L
        const val POLL_MS = 40L
    }
}

internal object MovoOwnedPages {
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun dismissForForegroundOperation(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 主线程不能阻塞等页面退场：只发起，交给下一次调用确认。
            if (VoiceSurfaceTracker.visibleActivities().isEmpty()) return true
            moveVisiblePagesToBack()
            return false
        }
        return MovoPageDismisser(
            visiblePageCount = { onMain { VoiceSurfaceTracker.visibleActivities().size } ?: Int.MAX_VALUE },
            hideConversationSheet = { FlavorModule.surfaces.hideConversationForDeviceOperation() },
            moveVisiblePagesToBack = { onMain(::moveVisiblePagesToBack) ?: false },
        ).dismiss()
    }

    private fun moveVisiblePagesToBack(): Boolean =
        VoiceSurfaceTracker.visibleActivities().fold(true) { all, activity ->
            runCatching { activity.moveTaskToBack(true) }.getOrDefault(false) && all
        }

    private fun <T> onMain(block: () -> T): T? {
        val result = AtomicReference<T?>()
        val done = CountDownLatch(1)
        mainHandler.post {
            try {
                result.set(block())
            } finally {
                done.countDown()
            }
        }
        return try {
            if (done.await(MAIN_WAIT_MS, TimeUnit.MILLISECONDS)) result.get() else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private const val MAIN_WAIT_MS = 1_000L
}
