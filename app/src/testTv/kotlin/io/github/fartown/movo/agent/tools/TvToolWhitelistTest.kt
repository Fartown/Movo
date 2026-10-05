package io.github.fartown.movo.agent.tools

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.tools.core.ApprovalMode
import io.github.fartown.movo.agent.tools.core.MemoryScope
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.flavor.FlavorModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 电视版工具白名单（实施方案 §5.6）与免审（Q11）。 */
@RunWith(RobolectricTestRunner::class)
class TvToolWhitelistTest {

    private val capableEnv = ToolEnvironment(
        rootAvailable = true,
        accessibilityAvailable = true,
        notificationAccess = true,
        usageAccess = true,
        locationAccess = true,
        colorOs = true,
        linuxReady = true,
        conversationBound = true,
        memoryScope = MemoryScope.REAL,
        interactive = FlavorModule.interactionCards,
    )

    private fun subsystem() = AgentToolSubsystem(
        services = ToolServices(
            context = ApplicationProvider.getApplicationContext(),
            logger = AndroidAgentLogger,
            runId = "run-tv-whitelist",
            rootAvailable = { true },
        ),
        environment = { capableEnv },
    )

    @Test
    fun registersOnlyWhitelistedTools() {
        val expected = setOf(
            "device_read", "device_toggle", "setting_read", "setting_write", "device_diagnostics",
            "app_search", "app_open", "app_control",
            "ui_observe", "ui_tap", "ui_scroll", "ui_swipe", "ui_input", "ui_key", "ui_wait",
            "clipboard_read", "clipboard_write",
            "clock_create", "clock_read", "media_control", "volume_set",
            "memory_read", "memory_write", "conversation_read",
            "ask_user", "tool_search",
        )
        subsystem().use { sub ->
            assertEquals(expected, sub.pipeline.registryView.tools.map { it.name }.toSet())
        }
    }

    @Test
    fun noInteractionCards_hidesAskUser_andApprovalsAreSkipped() {
        assertEquals(ApprovalMode.SKIP, FlavorModule.approvalMode)
        assertFalse(FlavorModule.interactionCards)
        subsystem().use { sub ->
            val catalog = sub.pipeline.catalog()
            val names = (0 until catalog.length()).map {
                catalog.getJSONObject(it).getJSONObject("function").getString("name")
            }.toSet()
            assertFalse("电视不弹提问卡，ask_user 不应进目录", "ask_user" in names)
        }
    }
}
