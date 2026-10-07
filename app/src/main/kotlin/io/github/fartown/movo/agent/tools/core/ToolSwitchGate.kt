package io.github.fartown.movo.agent.tools.core

/**
 * 「设置 → 工具」里五个开关管哪些工具（沿用工具重构前的分组）。
 * 开关关着的工具不进目录，执行时复核也拒绝。各工具不用各自判断开关；个别按参数细分的（file_read 按来源）仍在工具内。
 */
internal object ToolSwitchGate {
    private val BROWSER = setOf("browser_open", "browser_read", "browser_act")

    /** 设备直达：闹钟、计时器、音量和媒体播放。 */
    private val DEVICE_DIRECT = setOf("clock_create", "media_control", "volume_set")

    /** 终端与文件：命令、任意路径的文件读写与列目录（file_read 按来源在工具内判断）。 */
    private val TERMINAL = setOf("terminal_run", "terminal_job", "file_write", "file_list")

    /** 读取敏感信息：通知、位置、验证码、通讯录等个人数据，设置值、闹钟列表、应用用量、健康、日志。 */
    private val SENSITIVE_READ = setOf(
        "personal_search", "sms_code_read", "usage_read", "health_read", "wifi_password_read",
        "file_search", "setting_read", "clock_read", "device_diagnostics",
    )

    /** 敏感设备操作：修改系统设置，打开或关闭网络与蓝牙，冻结或停止应用。 */
    private val SENSITIVE_ACTION = setOf("setting_write", "device_toggle", "app_control")

    fun check(toolName: String, switches: ToolSwitches): ToolAvailability.Unavailable? {
        val (enabled, label) = when (toolName) {
            in BROWSER -> switches.browser to "网页浏览"
            in DEVICE_DIRECT -> switches.deviceDirect to "设备直达"
            in TERMINAL -> switches.terminal to "终端与文件"
            in SENSITIVE_READ -> switches.sensitiveRead to "读取敏感信息"
            in SENSITIVE_ACTION -> switches.sensitiveAction to "敏感设备操作"
            else -> return null
        }
        return if (enabled) null else ToolAvailability.Unavailable(ToolErrorCode.DISABLED, "需要在 设置 → 工具 里开启「$label」")
    }
}
