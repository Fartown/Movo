package io.github.fartown.movo.agent.tools

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.accessibility.AccessibilityEnableResult
import io.github.fartown.movo.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.fartown.movo.agent.runtime.AgentRuntimeWire
import io.github.fartown.movo.agent.runtime.EntrySurfaceGuard
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolOutcome
import io.github.fartown.movo.agent.tools.core.UserInteraction
import io.github.fartown.movo.core.AgentLogger
import io.github.fartown.movo.core.AndroidAgentLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * GUI 就绪守卫与悬浮层揭开名单一致（2026-10-05 审查）：会把别的界面拉到前台的工具（app_open、clock_create）先关入口；
 * 不揭开悬浮球的工具（clipboard_*）不关小布 / 小爱面板；UI 领域工具先确认无障碍再关入口。
 */
@RunWith(RobolectricTestRunner::class)
class GuiReadinessGuardTest {
    private var accessibilityChecks = 0
    private var accessibilityAvailable = true
    private var dismissals = 0
    private var dismissalSucceeds = true

    private val entryGuard: EntrySurfaceGuard = EntrySurfaceGuard.from(
        handoff = AgentRuntimeWire.EntryHandoff(
            id = "run-1",
            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            payload = "{}",
            dismissEntrySurfaceOnForegroundOperation = true,
        ),
        logger = NoOpLogger,
        movoPagesDismissal = {
            dismissals++
            dismissalSucceeds
        },
    )!!

    private val guard = GuiReadinessGuard(
        appContext = ApplicationProvider.getApplicationContext(),
        ensureAccessibility = {
            accessibilityChecks++
            if (accessibilityAvailable) {
                AccessibilityEnableResult.available(recoveryRequested = false)
            } else {
                AccessibilityEnableResult(available = false, code = "ACCESSIBILITY_DISABLED", recoveryRequested = false)
            }
        },
    ) { entryGuard }

    @Test
    fun appOpenAndClockCreateCloseTheEntrySurfaceBeforeRunning() {
        assertNull(check(tool("app_open", ToolDomain.APP)))
        assertEquals(1, dismissals)
        assertEquals("non-UI tools never need accessibility", 0, accessibilityChecks)

        val clock = EntrySurfaceGuard.from(
            handoff = AgentRuntimeWire.EntryHandoff("run-2", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "{}", true),
            logger = NoOpLogger,
            movoPagesDismissal = { dismissals++; false },
        )!!
        val clockGuard = GuiReadinessGuard(ApplicationProvider.getApplicationContext(), { error("unused") }) { clock }
        val outcome = clockGuard.check(tool("clock_create", ToolDomain.CLOCK_MEDIA), ToolArgs.parse("{}"), context())
        assertEquals(ToolErrorCode.BUSY, outcome?.error?.code)
        assertEquals("entry_surface_not_ready", outcome?.error?.detail)
    }

    @Test
    fun clipboardToolsDoNotCloseTheVoicePanelBecauseTheyNeverRevealTheOrb() {
        assertNull(check(tool("clipboard_read", ToolDomain.UI)))
        assertNull(check(tool("clipboard_write", ToolDomain.UI)))
        assertEquals(0, dismissals)
    }

    @Test
    fun clipboardToolsNeedNeitherAccessibilityNorRoot() {
        // #15：剪贴板走 ClipboardManager，与 AgentToolRequirements 的登记一致，不要求无障碍或 Root。
        accessibilityAvailable = false
        assertNull(check(tool("clipboard_read", ToolDomain.UI)))
        assertNull(check(tool("clipboard_write", ToolDomain.UI)))
        assertEquals(0, accessibilityChecks)
        // 屏幕工具照旧要求；没在 AgentToolRequirements 登记的 UI 工具（ui_focus）也照旧要求。
        assertEquals(ToolErrorCode.PERMISSION_REQUIRED, check(tool("ui_tap", ToolDomain.UI))?.error?.code)
        assertEquals(ToolErrorCode.PERMISSION_REQUIRED, check(tool("ui_focus", ToolDomain.UI))?.error?.code)
    }

    @Test
    fun uiToolsCheckAccessibilityBeforeClosingTheEntry() {
        accessibilityAvailable = false
        val outcome = check(tool("ui_tap", ToolDomain.UI))
        assertEquals(ToolErrorCode.PERMISSION_REQUIRED, outcome?.error?.code)
        assertEquals("entry stays open when the operation cannot run", 0, dismissals)

        accessibilityAvailable = true
        assertNull(check(tool("ui_tap", ToolDomain.UI)))
        assertEquals(1, dismissals)
        // 已关过：后续 UI 工具不再重复关闭。
        assertNull(check(tool("ui_swipe", ToolDomain.UI)))
        assertEquals(1, dismissals)
    }

    @Test
    fun rootStandsInForMissingAccessibility() {
        accessibilityAvailable = false
        assertNull(check(tool("ui_observe", ToolDomain.UI), ToolEnvironment(rootAvailable = true)))
        assertEquals(1, dismissals)
    }

    @Test
    fun unrelatedToolsPassUntouched() {
        assertNull(check(tool("file_read", ToolDomain.FILE)))
        assertNull(check(tool("monitor_start", ToolDomain.TERMINAL)))
        assertEquals(0, dismissals)
        assertEquals(0, accessibilityChecks)
    }

    @Test
    fun dismissalFollowsTheSameListAsTheOverlayReveal() {
        listOf("app_open", "ui_tap", "ui_swipe", "ui_scroll", "ui_input", "ui_key", "ui_observe", "ui_wait", "clock_create")
            .forEach { name -> assertTrue(name, AgentOverlayVisibilityPolicy.requiresEntrySurfaceDismissal(name)) }
        listOf("clipboard_read", "clipboard_write", "file_read", "terminal_run")
            .forEach { name -> assertTrue(name, !AgentOverlayVisibilityPolicy.requiresEntrySurfaceDismissal(name)) }
    }

    private fun check(tool: AgentTool, env: ToolEnvironment = ToolEnvironment()): ToolOutcome? =
        guard.check(tool, ToolArgs.parse("{}"), context(env))

    private fun context(env: ToolEnvironment = ToolEnvironment()) = ToolContext(
        appContext = ApplicationProvider.getApplicationContext(),
        logger = AndroidAgentLogger,
        runId = "run-1",
        toolCallId = "call-1",
        env = env,
        interaction = UserInteraction.NONE,
        cancelled = { false },
    )

    private fun tool(name: String, domain: ToolDomain): AgentTool = object : AgentTool {
        override val name = name
        override val domain = domain
        override val description = "test"
        override fun parameters(env: ToolEnvironment): JSONObject = JSONObject()
        override fun risk(args: ToolArgs, env: ToolEnvironment): Risk = Risk.READ
        override fun execute(args: ToolArgs, ctx: ToolContext): ToolOutcome = ToolOutcome.ok()
    }

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
