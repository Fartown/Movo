package io.github.fartown.movo.agent.tool

internal enum class RootRequirement { NONE, PARTIAL, REQUIRED }
internal enum class LsposedRequirement { NONE, OPTIONAL, REQUIRED }

internal enum class ToolSystemAccess { NONE, NOTIFICATIONS, USAGE, LOCATION }

internal data class LocalToolRequirement(
    val rootRequirement: RootRequirement,
    val lsposedRequirement: LsposedRequirement = LsposedRequirement.NONE,
    val accessibility: Boolean = false,
    val systemAccess: ToolSystemAccess = ToolSystemAccess.NONE,
    val colorOs: Boolean = false,
)

/** 展示、模型目录与执行边界共同使用的本地工具能力合同。未登记的工具不能发布。 */
internal object AgentToolRequirements {
    private val definitions = buildMap {
        fun register(root: RootRequirement, vararg names: String) {
            names.forEach { name ->
                check(put(name, LocalToolRequirement(root)) == null) { "Duplicate tool: $name" }
            }
        }
        register(
            RootRequirement.NONE,
            "get_current_context", "search_apps", "launch_app", "open_uri", "browser_use",
            "observe_screen", "tap", "tap_area", "tap_element", "long_press",
            "long_press_element", "swipe", "scroll", "scroll_element", "input_text",
            "replace_text", "clear_text", "set_clipboard", "get_clipboard", "paste_text",
            "wait", "wait_for_text", "wait_for_package", "open_system_panel",
            "set_alarm", "set_timer", "device_status", "media_control", "set_volume",
            "search_notification_history", "recent_app_activity", "app_usage_summary",
            "get_current_location", "get_device_environment", "memory_get", "memory_write",
            "character_memory_get", "character_memory_write",
            "skills_list", "skills_read", "skills_read_resource", "skills_list_curated",
            "skills_inspect_github", "skills_install_from_github",
        )
        register(
            RootRequirement.PARTIAL,
            "press_key", "network_info", "get_setting", "recent_notifications",
            "search_personal_orders", "terminal", "run_command", "read_file",
            "write_file", "list_directory", "read_image",
        )
        register(
            RootRequirement.REQUIRED,
            "top_memory_apps", "top_storage_apps", "wifi_credentials", "read_sms_code",
            "get_logcat", "set_setting", "set_device_state", "app_state_control",
            "list_alarms", "list_active_timers", "get_health_summary", "search_clipboard_history",
            "search_media", "search_audio", "search_recordings", "search_files",
            "search_calendar_events", "search_contacts", "search_call_history", "search_messages",
            "search_downloads", "search_coloros_notes", "search_coloros_recordings",
            "search_recording_summaries", "search_coloros_memories", "search_saved_places",
            "search_qq_chat_images", "search_wechat_chat_images",
        )
        // 类型化工具（重构后运行时只剩这些名字）：供「设置 → 全部工具」判断要不要 Root、权限。
        // media_control、memory_write 与旧名相同，上面已登记。
        register(
            RootRequirement.NONE,
            "ui_observe", "ui_tap", "ui_scroll", "ui_swipe", "ui_input", "ui_key", "ui_wait",
            "clipboard_read", "clipboard_write", "app_search", "app_open", "device_read",
            "clock_create", "volume_set", "usage_read", "browser_open", "browser_read", "browser_act",
            "memory_read", "skill_read", "skill_install", "conversation_read", "ask_user", "tool_search",
            "mcp_find", "mcp_call", "monitor_start", "monitor_stop", "monitor_list", "notify_user",
        )
        // sms_code_read：有 Root 读短信库，没有 Root 时从短信通知里取（要通知使用权）。
        register(
            RootRequirement.PARTIAL,
            "device_toggle", "setting_read", "personal_search", "file_search", "file_read", "file_write",
            "file_list", "terminal_run", "terminal_job", "sms_code_read",
        )
        register(
            RootRequirement.REQUIRED,
            "setting_write", "app_control", "device_diagnostics", "clock_read",
            "health_read", "wifi_password_read",
        )
        listOf("ui_observe", "ui_tap", "ui_scroll", "ui_swipe", "ui_input", "ui_key", "ui_wait")
            .forEach { name -> put(name, getValue(name).copy(accessibility = true)) }
        put("usage_read", getValue("usage_read").copy(systemAccess = ToolSystemAccess.USAGE))
        listOf(
            "observe_screen", "tap", "tap_area", "tap_element", "long_press",
            "long_press_element", "swipe", "scroll", "scroll_element", "input_text",
            "replace_text", "clear_text", "paste_text", "press_key", "open_system_panel",
            "wait_for_text", "wait_for_package",
        ).forEach { name -> put(name, getValue(name).copy(accessibility = true)) }
        mapOf(
            "recent_notifications" to ToolSystemAccess.NOTIFICATIONS,
            "sms_code_read" to ToolSystemAccess.NOTIFICATIONS,
            "search_notification_history" to ToolSystemAccess.NOTIFICATIONS,
            "search_personal_orders" to ToolSystemAccess.NOTIFICATIONS,
            "recent_app_activity" to ToolSystemAccess.USAGE,
            "app_usage_summary" to ToolSystemAccess.USAGE,
            "get_current_location" to ToolSystemAccess.LOCATION,
        ).forEach { (name, access) -> put(name, getValue(name).copy(systemAccess = access)) }
        listOf(
            "search_coloros_notes", "search_coloros_recordings", "search_recording_summaries",
            "search_coloros_memories", "search_saved_places",
        ).forEach { name -> put(name, getValue(name).copy(colorOs = true)) }
        // 系统记忆优先使用 Hook 桥接，框架失联时仍有独立的 Root 快照来源。
        listOf("search_coloros_memories", "search_saved_places", "search_personal_orders").forEach { name ->
            put(name, getValue(name).copy(lsposedRequirement = LsposedRequirement.OPTIONAL))
        }
    }

    val toolNames: Set<String> get() = definitions.keys

    fun find(name: String): LocalToolRequirement? = definitions[name]
}
