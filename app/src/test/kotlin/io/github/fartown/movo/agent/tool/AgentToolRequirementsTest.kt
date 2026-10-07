package io.github.fartown.movo.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolRequirementsTest {
    @Test
    fun everyTypedToolHasARequirement() {
        // 类型化工具名都登记了（「设置 → 全部工具」用）。
        assertTrue(AgentToolRequirements.toolNames.containsAll(TYPED_TOOL_NAMES))
    }

    @Test
    fun ordinaryAuthorizationIsIndependentFromRootAndForegroundIntentsDoNotNeedAccessibility() {
        val restricted = AgentToolCapabilities(
            rootAvailable = false, accessibilityAvailable = false,
            notificationsAllowed = false, usageAllowed = false, locationAllowed = false, colorOs = false,
        )
        assertTrue(setOf("launch_app", "open_uri", "terminal", "browser_use").all { restricted.unavailableCode(it) == null })
        assertTrue(setOf("observe_screen", "wait_for_text", "wait_for_package", "recent_notifications", "app_usage_summary", "get_current_location").none { restricted.unavailableCode(it) == null })
        assertEquals("ROOT_REQUIRED", restricted.unavailableCode("search_coloros_notes"))
        assertEquals("DEVICE_UNSUPPORTED", restricted.copy(rootAvailable = true).unavailableCode("search_coloros_notes"))
        assertEquals(null, restricted.copy(notificationsAllowed = true).unavailableCode("recent_notifications"))
        assertEquals("NOTIFICATION_ACCESS_REQUIRED", restricted.copy(rootAvailable = true).unavailableCode("search_personal_orders"))
        assertEquals(null, restricted.copy(rootAvailable = true, colorOs = true).unavailableCode("search_personal_orders"))
        assertEquals(null, restricted.copy(accessibilityAvailable = true).unavailableCode("observe_screen"))
        assertEquals(null, restricted.copy(accessibilityRecoveryAvailable = true).unavailableCode("observe_screen"))
    }

    @Test
    fun frameworkConnectionDoesNotGrantRootAndRootSnapshotDoesNotRequireFramework() {
        assertEquals(LsposedRequirement.OPTIONAL, AgentToolRequirements.find("search_coloros_memories")?.lsposedRequirement)
        assertEquals("ROOT_REQUIRED", AgentToolCapabilities(rootAvailable = false, lsposedAvailable = true)
            .unavailableCode("search_coloros_memories"))
        assertEquals(null, AgentToolCapabilities(rootAvailable = true, lsposedAvailable = false)
            .unavailableCode("search_coloros_memories"))
    }
}

private val TYPED_TOOL_NAMES = setOf(
    "ui_observe", "ui_tap", "ui_scroll", "ui_swipe", "ui_input", "ui_key", "ui_wait", "clipboard_read",
    "clipboard_write", "app_search", "app_open", "app_control", "device_read", "device_toggle", "setting_read",
    "setting_write", "device_diagnostics", "clock_create", "clock_read", "media_control", "volume_set",
    "personal_search", "sms_code_read", "usage_read", "health_read", "wifi_password_read", "file_search",
    "file_read", "file_write", "file_list", "terminal_run", "terminal_job", "browser_open", "browser_read",
    "browser_act", "memory_read", "memory_write", "skill_read", "skill_install", "conversation_read",
    "ask_user", "tool_search", "mcp_find", "mcp_call", "monitor_start", "monitor_stop", "monitor_list", "notify_user",
)
