package io.github.fartown.movo.agent.tools.core

import org.json.JSONObject

/**
 * 底层控制器（无障碍、Root Shell、终端、浏览器、数据源）仍返回 `{"ok":…,"code":…,"message":…}` 风格的 JSON。
 * 这里把它们统一映射到 [ToolErrorCode]；原码保留在 detail，方便排查。
 */
internal object LegacyResults {
    private val exact: Map<String, ToolErrorCode> = buildMap {
        fun map(code: ToolErrorCode, vararg legacy: String) = legacy.forEach { put(it, code) }
        map(
            ToolErrorCode.STALE_OBSERVATION,
            "NO_OBSERVATION", "STALE_OBSERVATION", "OBSERVATION_ERROR", "SERVICE_RECONNECTED", "STALE_WINDOW",
            "STALE_CONTENT", "STALE_NODE", "IDENTITY_CHANGED", "STALE_ACTION_TARGET",
        )
        map(
            ToolErrorCode.INVALID_ARGUMENTS,
            "INVALID_ARGUMENT", "INVALID_TOOL_ARGUMENTS", "OBSERVATION_ID_REQUIRED", "INVALID_NODE_INDEX",
            "INVALID_PACKAGE", "INVALID_PATH", "INVALID_ACTION", "MISSING_PARAM", "INVALID_IDENTITY",
            "INVALID_MEMORY_MODE", "MEMORY_RANGE_INVALID", "INVALID_SKILL_SELECTION", "INVALID_RELATIVE_PATH",
            "UNSUPPORTED_TERMINAL_ACTION", "ASYNC_SESSION_UNSUPPORTED", "SKILL_INSPECTION_REQUIRED",
            "IMAGE_PATH_DENIED", "INVALID_GITHUB_SOURCE",
        )
        map(
            ToolErrorCode.NOT_ACTIONABLE,
            "NODE_NOT_ACTIONABLE", "NOT_SCROLLABLE", "NOT_EDITABLE", "NO_FOCUSED_EDITABLE", "AXIS_MISMATCH",
            "DIRECTION_MISMATCH", "INVALID_NODE_BOUNDS", "TEXT_CONTENT_UNAVAILABLE", "TEXT_SELECTION_UNAVAILABLE",
            "NO_ACTIVE_WINDOW",
        )
        map(ToolErrorCode.OUTCOME_UNKNOWN, "ACTION_OUTCOME_UNKNOWN")
        map(
            ToolErrorCode.SYSTEM_REJECTED,
            "GESTURE_NOT_DISPATCHED", "ACTION_FAILED", "COMMAND_FAILED", "CLIPBOARD_WRITE_FAILED",
            "VOLUME_CHANGE_FAILED", "SETTING_ACCESS_DENIED", "READ_FAILED", "WRITE_FAILED", "PROCESS_START_FAILED",
            "SESSION_OPEN_FAILED", "DAEMON_START_FAILED", "DAEMON_STOP_FAILED", "APP_NOT_LAUNCHABLE",
            "FILE_ACCESS_DENIED", "IMAGE_ACCESS_DENIED", "BACKGROUND_START_NOT_ALLOWED", "DIRECT_CLOCK_ACTION_FAILED",
            "MCP_TOOL_ERROR", "SCRIPT_FAILED", "RECORDS_WRITE_FAILED", "MEMORY_WRITE_FAILED",
        )
        map(
            ToolErrorCode.TIMEOUT,
            "SERVICE_TIMEOUT", "TIMEOUT", "ROOT_COMMAND_TIMEOUT", "PERSONAL_DATA_QUERY_TIMEOUT", "SCRIPT_TIMEOUT",
            "MAIN_THREAD_TIMEOUT", "NAVIGATION_TIMEOUT", "ACTION_TIMEOUT", "COLOROS_MEMORY_SNAPSHOT_TIMEOUT",
        )
        map(ToolErrorCode.ROOT_REQUIRED, "ROOT_REQUIRED", "ROOT_UNAVAILABLE", "LINUX_ENVIRONMENT_REQUIRES_ROOT")
        map(
            ToolErrorCode.NOT_FOUND,
            "APP_NOT_FOUND", "NO_ACTIVITY", "NOT_FOUND", "SESSION_NOT_FOUND", "JOB_NOT_FOUND", "TASK_NOT_FOUND",
            "RESOURCE_NOT_FOUND", "ELEMENT_NOT_FOUND", "NO_PAGE", "HISTORY_UNAVAILABLE", "UNKNOWN_MCP_TOOL",
            "GITHUB_NOT_FOUND",
        )
        map(ToolErrorCode.AMBIGUOUS, "AMBIGUOUS_APP")
        map(
            ToolErrorCode.PERMISSION_REQUIRED,
            "ACCESSIBILITY_UNAVAILABLE", "ACCESSIBILITY_PROTECTION_UNAVAILABLE", "ACCESSIBILITY_REPAIR_TIMEOUT",
            "NOTIFICATION_ACCESS_REQUIRED", "NOTIFICATION_HISTORY_ACCESS_REQUIRED", "APP_USAGE_ACCESS_REQUIRED",
            "LOCATION_PERMISSION_REQUIRED",
        )
        map(
            ToolErrorCode.UNSUPPORTED,
            "DEVICE_UNSUPPORTED", "CLOCK_UNAVAILABLE", "INCOMPATIBLE", "BINARY_RESOURCE", "IMAGE_UNSUPPORTED",
            "MCP_INPUT_REQUIRED_UNSUPPORTED", "MCP_RESULT_TYPE_UNSUPPORTED", "LINUX_ENVIRONMENT_NOT_READY",
            "PROOT_UNAVAILABLE", "DAEMON_UNAVAILABLE", "SKILLS_UNAVAILABLE", "SKILL_INSTALLER_UNAVAILABLE",
            "REAL_MEMORY_READ_ONLY",
        )
        map(ToolErrorCode.CONFLICT, "MEMORY_CONFLICT", "SKILL_CONFLICT")
        map(ToolErrorCode.TOO_LARGE, "TEXT_TOO_LONG")
        map(ToolErrorCode.LIMIT_REACHED, "MAX_TASKS_REACHED", "TOO_MANY_SKILL_CANDIDATES")
        map(ToolErrorCode.BUSY, "USER_CONTROL_ACTIVE", "ENTRY_SURFACE_NOT_READY", "NEXT_TURN_REQUIRED")
        map(
            ToolErrorCode.NETWORK_ERROR,
            "NETWORK_ERROR", "GITHUB_RATE_LIMITED", "GITHUB_REQUEST_FAILED", "GITHUB_NETWORK_ERROR",
            "MCP_CALL_FAILED", "RENDERER_GONE",
        )
        map(ToolErrorCode.INTERRUPTED, "TOOL_INTERRUPTED")
        map(ToolErrorCode.DISABLED, "MEMORY_DISABLED")
        map(ToolErrorCode.INTERNAL_ERROR, "TOOL_ERROR", "BROWSER_ERROR", "IO_ERROR", "TERMINAL_CLOSED",
            "MCP_EXECUTOR_CLOSED", "MAIN_THREAD_CALL")
    }

    /** 无障碍服务报的「页面已经变了」类代码（窗口、内容、节点身份对不上）。 */
    fun isStale(legacyCode: String): Boolean = exact[legacyCode] == ToolErrorCode.STALE_OBSERVATION

    fun map(legacyCode: String): ToolErrorCode {
        exact[legacyCode]?.let { return it }
        return when {
            legacyCode.endsWith("_DISABLED") -> ToolErrorCode.DISABLED
            legacyCode.endsWith("_ACCESS_REQUIRED") -> ToolErrorCode.PERMISSION_REQUIRED
            legacyCode.endsWith("_TIMEOUT") -> ToolErrorCode.TIMEOUT
            legacyCode.endsWith("_TOO_LARGE") -> ToolErrorCode.TOO_LARGE
            legacyCode.endsWith("_NOT_FOUND") -> ToolErrorCode.NOT_FOUND
            legacyCode.contains("SCHEMA_UNSUPPORTED") || legacyCode.endsWith("_UNSUPPORTED") ->
                ToolErrorCode.UNSUPPORTED
            legacyCode.endsWith("_UNAVAILABLE") || legacyCode.endsWith("_MISSING") ||
                legacyCode.endsWith("_SYMLINK") || legacyCode.endsWith("_INVALID") -> ToolErrorCode.SOURCE_UNAVAILABLE
            legacyCode.startsWith("HTTP_") || legacyCode.startsWith("GITHUB_") -> ToolErrorCode.NETWORK_ERROR
            legacyCode.endsWith("_CANCELLED") -> ToolErrorCode.INTERRUPTED
            legacyCode.endsWith("_FAILED") -> ToolErrorCode.SYSTEM_REJECTED
            else -> ToolErrorCode.INTERNAL_ERROR
        }
    }

    /**
     * 把旧 JSON 结果转成 [ToolOutcome]。成功时去掉 ok / tool / code / message 外壳，其余字段作为 data。
     * [hints] 可以为特定旧码补充修复指引；[remap] 可以覆盖默认映射。
     */
    fun toOutcome(
        json: JSONObject,
        hints: Map<String, String> = emptyMap(),
        remap: Map<String, ToolErrorCode> = emptyMap(),
        dropKeys: Set<String> = emptySet(),
    ): ToolOutcome {
        val ok = json.optBoolean("ok", true)
        val legacyCode = json.optString("code").takeIf { it.isNotBlank() }
        val data = JSONObject()
        json.keys().forEach { key ->
            if (key !in ENVELOPE_KEYS && key !in dropKeys) data.put(key, json.get(key))
        }
        if (ok && legacyCode == null) return ToolOutcome.ok(data)
        val code = legacyCode ?: "FAILED"
        val mapped = remap[code] ?: map(code)
        val message = json.optString("message").ifBlank { defaultMessage(mapped) }
        return ToolOutcome.error(
            code = mapped,
            message = message,
            hint = hints[code] ?: defaultHint(mapped),
            detail = code,
            data = data.takeIf { it.length() > 0 },
        )
    }

    fun toOutcome(raw: String, hints: Map<String, String> = emptyMap()): ToolOutcome =
        runCatching { JSONObject(raw) }
            .map { toOutcome(it, hints) }
            .getOrElse { ToolOutcome.ok(JSONObject().put("text", raw)) }

    fun defaultHint(code: ToolErrorCode): String? = when (code) {
        ToolErrorCode.STALE_OBSERVATION -> "先调用 ui_observe，再用新的 observation_id"
        ToolErrorCode.OUTCOME_UNKNOWN -> "动作可能已经生效；先观察确认，不要直接重复"
        ToolErrorCode.PERMISSION_REQUIRED -> "需要用户授予权限后才能继续，告诉用户需要哪项权限"
        ToolErrorCode.ROOT_REQUIRED -> "此能力需要 Root，本次未执行；不要换方式绕过"
        ToolErrorCode.DISABLED -> "该能力已在设置中关闭；告诉用户到「设置 · 工具」开启，不要用其他工具绕过"
        ToolErrorCode.UNSUPPORTED -> "当前设备或环境不支持；换其他方式或告诉用户"
        ToolErrorCode.BUSY -> "资源暂时被占用；不要在本次任务中反复调用"
        ToolErrorCode.NOT_ACTIONABLE -> "目标当前不可操作；重新观察后换一个目标"
        else -> null
    }

    private fun defaultMessage(code: ToolErrorCode): String = when (code) {
        ToolErrorCode.OUTCOME_UNKNOWN -> "动作结果无法确认"
        ToolErrorCode.TIMEOUT -> "操作超时"
        else -> "操作失败"
    }

    private val ENVELOPE_KEYS = setOf("ok", "tool", "code", "message")
}
