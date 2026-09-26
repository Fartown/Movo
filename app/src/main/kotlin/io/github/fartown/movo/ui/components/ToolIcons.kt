package io.github.fartown.movo.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AdsClick
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ContentPasteGo
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardCommandKey
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ScreenshotMonitor
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.WebAsset
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.fartown.movo.ui.theme.MovoIconData
import io.github.fartown.movo.ui.theme.MovoIcons

/**
 * 旧的 Material 实心工具图标，只剩对话执行卡（`ChatMessageItem`）在用；新界面用 [toolIcon]（Lucide 线条，规范第 6 章）。
 */
internal fun iconForTool(toolId: String): ImageVector = when (toolId) {
    "observe", "observe_screen" -> Icons.Rounded.DocumentScanner
    "click", "tap", "tap_element" -> Icons.Rounded.AdsClick
    "tap_area" -> Icons.Rounded.MyLocation
    "long_press", "long_press_element" -> Icons.Rounded.TouchApp
    "swipe" -> Icons.Rounded.OpenWith
    "scroll", "scroll_element" -> Icons.Rounded.SwapVert
    "clipboard", "paste_text" -> Icons.Rounded.ContentPasteGo
    "get_clipboard", "set_clipboard" -> Icons.Rounded.ContentPaste
    "input_text" -> Icons.Rounded.Keyboard
    "replace_text" -> Icons.Rounded.FindReplace
    "clear_text" -> Icons.AutoMirrored.Rounded.Backspace
    "wait", "wait_text", "wait_for_text", "wait_for_package" -> Icons.Rounded.Schedule
    "search_apps" -> Icons.Rounded.Search
    "get_current_context" -> Icons.Rounded.Smartphone
    "open_app", "launch_app" -> Icons.Rounded.Apps
    "open_uri" -> Icons.AutoMirrored.Rounded.OpenInNew
    "browser_use", "网页浏览" -> Icons.Rounded.Language
    "web_search", "web_search_call", "网页搜索" -> Icons.Rounded.TravelExplore
    "browser_read" -> Icons.AutoMirrored.Rounded.MenuBook
    "browser_interact" -> Icons.Rounded.AdsClick
    "browser_screenshot" -> Icons.Rounded.ScreenshotMonitor
    "file_search", "file_search_call", "文件搜索" -> Icons.AutoMirrored.Rounded.ManageSearch
    "code_interpreter", "code_interpreter_call", "代码执行" -> Icons.Rounded.Terminal
    "computer", "computer_call", "计算机操作" -> Icons.Rounded.Computer
    "image_generation", "image_generation_call", "图像生成" -> Icons.Rounded.Image
    "mcp_call", "MCP 工具" -> Icons.Rounded.Extension
    "memory_get", "memory_write", "character_memory_get", "character_memory_write" -> Icons.Rounded.Psychology
    "press_key" -> Icons.Rounded.KeyboardCommandKey
    "open_system_panel" -> Icons.Rounded.WebAsset
    "read_image" -> Icons.Rounded.Image
    "skills_list", "skills_read", "skills_read_resource",
    "skills_list_curated", "skills_inspect_github", "skills_install_from_github",
        -> Icons.Rounded.Extension
    "set_alarm", "set_timer", "list_alarms", "list_active_timers" ->
        Icons.Rounded.Alarm
    "device_status", "set_device_state", "get_device_environment" ->
        Icons.Rounded.Smartphone
    "network_info", "wifi_credentials" -> Icons.Rounded.Wifi
    "media_control" -> Icons.Rounded.PlayArrow
    "set_volume" -> Icons.AutoMirrored.Rounded.VolumeUp
    "top_memory_apps", "top_storage_apps" -> Icons.Rounded.Layers
    "read_sms_code" -> Icons.Rounded.Key
    "recent_notifications", "search_notification_history" -> Icons.Rounded.Notifications
    "get_setting", "set_setting" -> Icons.Rounded.Settings
    "app_state_control" -> Icons.Rounded.AdminPanelSettings
    "get_logcat" -> Icons.Rounded.Description
    "get_current_location", "search_saved_places" -> Icons.Rounded.LocationOn
    "get_health_summary" -> Icons.Rounded.MonitorHeart
    "recent_app_activity", "app_usage_summary" -> Icons.Rounded.Insights
    "search_calendar_events" -> Icons.Rounded.CalendarMonth
    "search_contacts" -> Icons.Rounded.Contacts
    "search_call_history" -> Icons.Rounded.Phone
    "search_messages" -> Icons.Rounded.ChatBubble
    "search_media", "search_qq_chat_images", "search_wechat_chat_images" ->
        Icons.Rounded.Image
    "search_audio" -> Icons.Rounded.MusicNote
    "search_recordings", "search_coloros_recordings", "search_recording_summaries" ->
        Icons.Rounded.Mic
    "search_files" -> Icons.Rounded.FolderOpen
    "search_downloads" -> Icons.Rounded.Download
    "search_clipboard_history" -> Icons.Rounded.ContentPaste
    "search_coloros_notes" -> Icons.AutoMirrored.Rounded.StickyNote2
    "search_coloros_memories" -> Icons.Rounded.Psychology
    "search_personal_orders" -> Icons.Rounded.ShoppingBag
    "terminal", "terminal_job", "run_command" -> Icons.Rounded.Terminal
    "read_file" -> Icons.Rounded.Description
    "write_file" -> Icons.Rounded.EditNote
    "list_directory" -> Icons.Rounded.FolderOpen
    else -> if (toolId.startsWith("mcp_")) Icons.Rounded.Extension else Icons.Rounded.Build
}

/**
 * 工具图标（规范第 6 章：Lucide 线条，24 网格，线宽随尺寸配对）。与 [iconForTool] 一一对应的语义，
 * 同一语义只用一个图标；未知工具用扳手，动态 MCP 工具用插头。
 */
internal fun toolIcon(toolId: String): MovoIconData = when (toolId) {
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
