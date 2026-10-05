package io.github.fartown.movo.agent.model

/**
 * 标记原始参数或结果不得进入持久会话的工具。真正执行的调用按工具自己声明的敏感度脱敏（ToolPipeline）；
 * 这份名单兜住没执行完的调用（循环拒绝、审批等待时停止、进程被杀后恢复），所以要覆盖会读写个人数据的类型化工具名。
 */
internal object AgentSensitiveToolPolicy {
    fun isSensitive(toolName: String): Boolean =
        toolName.startsWith("mcp_") || toolName in sensitiveTools

    private val sensitiveTools = setOf(
        // 类型化工具（重构后运行时只出现这些名字），与下面旧名一一对应：个人数据、设置、记忆、文件、日志、位置。
        "personal_search", "sms_code_read", "wifi_password_read", "health_read", "usage_read",
        "clipboard_read", "setting_read", "setting_write", "clock_read",
        "memory_read", "memory_write", "file_search", "file_read",
        "device_read", "device_diagnostics",
        // 旧工具名：只为读旧会话、旧检查点保留。
        "get_setting",
        "wifi_credentials",
        "recent_notifications",
        "search_notification_history",
        "recent_app_activity",
        "app_usage_summary",
        "get_current_location",
        "get_device_environment",
        "list_alarms",
        "list_active_timers",
        "search_clipboard_history",
        "get_health_summary",
        "read_sms_code",
        "get_logcat",
        "search_media",
        "search_audio",
        "search_recordings",
        "search_files",
        "search_calendar_events",
        "search_contacts",
        "search_call_history",
        "search_messages",
        "search_downloads",
        "search_coloros_notes",
        "search_coloros_recordings",
        "search_recording_summaries",
        "search_coloros_memories",
        "search_saved_places",
        "search_personal_orders",
        "search_qq_chat_images",
        "search_wechat_chat_images",
        "read_image",
        "set_setting",
        "memory_get",
        "memory_write",
        "character_memory_get",
        "character_memory_write",
    )
}
