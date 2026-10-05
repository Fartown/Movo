package io.github.fartown.movo.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Wifi
import io.github.fartown.movo.ui.theme.MovoIconData
import io.github.fartown.movo.ui.theme.MovoIcons


/**
 * 工具图标（规范第 6 章：Lucide 线条，24 网格，线宽随尺寸配对）。与原来的 Material 图标保持同一语义，
 * 同一语义只用一个图标；未知工具用扳手，动态 MCP 工具用插头。
 */
internal fun toolIcon(toolId: String): MovoIconData = when (toolId) {
    // 类型化工具（重构后运行时只出现这些名字），与下面旧名同一语义同一图标。
    "ui_observe" -> MovoIcons.ScanEye
    "ui_tap" -> MovoIcons.MousePointerClick
    "ui_swipe" -> MovoIcons.Move
    "ui_scroll" -> MovoIcons.ArrowDownUp
    "ui_input" -> MovoIcons.Keyboard
    "ui_key" -> MovoIcons.Command
    "ui_wait" -> MovoIcons.Clock
    "clipboard_read", "clipboard_write" -> MovoIcons.Clipboard
    "app_search" -> MovoIcons.Search
    "app_open" -> MovoIcons.LayoutGrid
    "app_control" -> MovoIcons.AppWindow
    "device_read", "device_toggle" -> MovoIcons.Smartphone
    "device_diagnostics" -> MovoIcons.FileText
    "setting_read", "setting_write" -> MovoIcons.Settings
    "clock_create", "clock_read" -> MovoIcons.AlarmClock
    "volume_set" -> MovoIcons.Volume2
    "personal_search" -> MovoIcons.Contact
    "sms_code_read" -> MovoIcons.KeyRound
    "usage_read" -> MovoIcons.Activity
    "health_read" -> MovoIcons.HeartPulse
    "wifi_password_read" -> MovoIcons.Wifi
    "file_read" -> MovoIcons.FileText
    "file_write" -> MovoIcons.FilePenLine
    "file_list" -> MovoIcons.FolderOpen
    "terminal_run" -> MovoIcons.Terminal
    "browser_open" -> MovoIcons.Globe
    "browser_act" -> MovoIcons.MousePointerClick
    "memory_read" -> MovoIcons.Brain
    "skill_read", "skill_install" -> MovoIcons.Puzzle
    "conversation_read" -> MovoIcons.History
    "ask_user" -> MovoIcons.MessageSquare
    "tool_search" -> MovoIcons.Search
    "mcp_find" -> MovoIcons.Plug
    "monitor_start", "monitor_stop", "monitor_list" -> MovoIcons.Clock
    "notify_user" -> MovoIcons.Bell
    "observe", "observe_screen" -> MovoIcons.ScanEye
    "click", "tap", "tap_element" -> MovoIcons.MousePointerClick
    "tap_area" -> MovoIcons.Crosshair
    "long_press", "long_press_element" -> MovoIcons.Pointer
    "swipe" -> MovoIcons.Move
    "scroll", "scroll_element" -> MovoIcons.ArrowDownUp
    "clipboard", "paste_text" -> MovoIcons.ClipboardPaste
    "get_clipboard", "set_clipboard" -> MovoIcons.Clipboard
    "input_text" -> MovoIcons.Keyboard
    "replace_text" -> MovoIcons.Replace
    "clear_text" -> MovoIcons.Delete
    "wait", "wait_text", "wait_for_text", "wait_for_package" -> MovoIcons.Clock
    "search_apps" -> MovoIcons.Search
    "get_current_context" -> MovoIcons.Smartphone
    "open_app", "launch_app" -> MovoIcons.LayoutGrid
    "open_uri" -> MovoIcons.ExternalLink
    "browser_use", "网页浏览" -> MovoIcons.Globe
    "web_search", "web_search_call", "网页搜索" -> MovoIcons.Telescope
    "browser_read" -> MovoIcons.BookOpen
    "browser_interact" -> MovoIcons.MousePointerClick
    "browser_screenshot" -> MovoIcons.Camera
    "file_search", "file_search_call", "文件搜索" -> MovoIcons.FileSearch
    "code_interpreter", "code_interpreter_call", "代码执行" -> MovoIcons.CodeXml
    "computer", "computer_call", "计算机操作" -> MovoIcons.Monitor
    "image_generation", "image_generation_call", "图像生成" -> MovoIcons.ImagePlus
    "mcp_call", "MCP 工具" -> MovoIcons.Plug
    "memory_get", "memory_write", "character_memory_get", "character_memory_write" -> MovoIcons.Brain
    "press_key" -> MovoIcons.Command
    "open_system_panel" -> MovoIcons.PanelTop
    "read_image" -> MovoIcons.Image
    "skills_list", "skills_read", "skills_read_resource",
    "skills_list_curated", "skills_inspect_github", "skills_install_from_github",
        -> MovoIcons.Puzzle
    "set_alarm", "set_timer", "list_alarms", "list_active_timers" -> MovoIcons.AlarmClock
    "device_status", "set_device_state", "get_device_environment" -> MovoIcons.Smartphone
    "network_info", "wifi_credentials" -> MovoIcons.Wifi
    "media_control" -> MovoIcons.Play
    "set_volume" -> MovoIcons.Volume2
    "top_memory_apps", "top_storage_apps" -> MovoIcons.Layers
    "read_sms_code" -> MovoIcons.KeyRound
    "recent_notifications", "search_notification_history" -> MovoIcons.Bell
    "get_setting", "set_setting" -> MovoIcons.Settings
    "app_state_control" -> MovoIcons.AppWindow
    "get_logcat" -> MovoIcons.FileText
    "get_current_location", "search_saved_places" -> MovoIcons.MapPin
    "get_health_summary" -> MovoIcons.HeartPulse
    "recent_app_activity", "app_usage_summary" -> MovoIcons.Activity
    "search_calendar_events" -> MovoIcons.Calendar
    "search_contacts" -> MovoIcons.Contact
    "search_call_history" -> MovoIcons.Phone
    "search_messages" -> MovoIcons.MessageSquare
    "search_media", "search_qq_chat_images", "search_wechat_chat_images" -> MovoIcons.Image
    "search_audio" -> MovoIcons.Music
    "search_recordings", "search_coloros_recordings", "search_recording_summaries" -> MovoIcons.Mic
    "search_files" -> MovoIcons.FolderOpen
    "search_downloads" -> MovoIcons.Download
    "search_clipboard_history" -> MovoIcons.Clipboard
    "search_coloros_notes" -> MovoIcons.StickyNote
    "search_coloros_memories" -> MovoIcons.Brain
    "search_personal_orders" -> MovoIcons.ShoppingBag
    "terminal", "terminal_job", "run_command" -> MovoIcons.Terminal
    "read_file" -> MovoIcons.FileText
    "write_file" -> MovoIcons.FilePenLine
    "list_directory" -> MovoIcons.FolderOpen
    else -> if (toolId.startsWith("mcp_")) MovoIcons.Plug else MovoIcons.Wrench
}
