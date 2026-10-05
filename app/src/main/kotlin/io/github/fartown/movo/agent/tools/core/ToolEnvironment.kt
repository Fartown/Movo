package io.github.fartown.movo.agent.tools.core

/** 用户在「设置 · 工具」里的开关；存储 key 沿用 Prefs.Keys.AGENT_*。 */
internal data class ToolSwitches(
    /** 网页：browser_*。 */
    val browser: Boolean = true,
    /** 系统直达：clock_*、media_control、device_toggle、device_info。 */
    val deviceDirect: Boolean = true,
    /** 文件与终端：file_*、terminal_*、media_read 读本机路径。 */
    val terminal: Boolean = true,
    /** 读取个人数据：personal_search、sms_code_get、file_search、app_usage、health_summary、wifi_password_get。 */
    val sensitiveRead: Boolean = true,
    /** 修改系统：system_setting 写入、app_control、device_diagnostics。 */
    val sensitiveAction: Boolean = true,
)

internal enum class MemoryScope {
    DISABLED,
    /** 普通会话：真实长期记忆，可读写。 */
    REAL,
    /** 角色会话：读写绑定到角色记忆，真实记忆只读。 */
    CHARACTER,
}

internal enum class ModelInput { TEXT, IMAGE, VIDEO, PDF, AUDIO }

/**
 * 每轮冻结的工具运行环境：设备条件、用户开关、会话类型与模型能力。
 * 目录投影、可用性判断和执行时的复核都只看这一份快照。
 */
internal data class ToolEnvironment(
    val rootAvailable: Boolean = false,
    val lsposedAvailable: Boolean = false,
    val accessibilityAvailable: Boolean = false,
    /** 无障碍未连接，但可以由保护后端自动恢复。 */
    val accessibilityRecoverable: Boolean = false,
    val notificationAccess: Boolean = false,
    val usageAccess: Boolean = false,
    val locationAccess: Boolean = false,
    val colorOs: Boolean = false,
    val linuxReady: Boolean = false,
    val switches: ToolSwitches = ToolSwitches(),
    val memoryScope: MemoryScope = MemoryScope.DISABLED,
    /** 当前运行绑定了一个持久会话，可读取其历史。 */
    val conversationBound: Boolean = false,
    /** 绑定会话的 id；后台监听等需要把结果送回这个会话。 */
    val conversationId: String? = null,
    /** 当前入口可以向用户提问与请求确认（后台定时任务等不行）。 */
    val interactive: Boolean = true,
    val modelInputs: Set<ModelInput> = setOf(ModelInput.TEXT, ModelInput.IMAGE),
) {
    val accessibilityUsable: Boolean get() = accessibilityAvailable || accessibilityRecoverable
}
