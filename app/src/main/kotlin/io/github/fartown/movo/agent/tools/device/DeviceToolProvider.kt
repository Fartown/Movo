package io.github.fartown.movo.agent.tools.device

import android.content.Context
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.device.RootAccess
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.ToolProvider

/**
 * 设备与应用领域的工具 Provider。注册 device_read（§A.1，复用其既有合同）与本子任务实现的 7 个工具：
 * device_toggle、setting_read、setting_write、device_diagnostics、app_search、app_open、app_control。
 *
 * 真实后端在此装配：Android 系统服务 + Root 执行器。Root 执行器由调用方（ToolServices）提供，便于统一关闭。
 */
internal class DeviceToolProvider(
    context: Context,
    root: BoundedRootCommandExecutor,
    rootAvailable: () -> Boolean = { RootAccess.isGranted },
) : ToolProvider {

    private val settingBackend = AndroidSettingBackend(context, root, rootAvailable)

    override val tools: List<AgentTool> = listOf(
        ContractTool(DeviceReadTool(AndroidDeviceReadBackend(context, root, rootAvailable))),
        ContractTool(DeviceToggleTool(AndroidDeviceToggleBackend(context, root))),
        ContractTool(SettingReadTool(settingBackend)),
        ContractTool(SettingWriteTool(settingBackend)),
        ContractTool(DeviceDiagnosticsTool(AndroidDeviceDiagnosticsBackend(root))),
        ContractTool(AppSearchTool(AndroidAppSearchBackend(context))),
        ContractTool(AppOpenTool(AndroidAppOpenBackend(context))),
        ContractTool(AppControlTool(AndroidAppControlBackend(context, root))),
    )
}
