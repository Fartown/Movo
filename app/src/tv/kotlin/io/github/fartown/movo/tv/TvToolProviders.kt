package io.github.fartown.movo.tv

import io.github.fartown.movo.agent.tools.ToolServices
import io.github.fartown.movo.agent.tools.core.*
import io.github.fartown.movo.agent.tools.device.*
import io.github.fartown.movo.agent.tools.clockmedia.*

/** TV registers only operations supported by an ordinary app on this device. */
internal class TvDeviceToolProvider(services: ToolServices) : ToolProvider {
    private val context = services.context
    private val root = services.root()
    private val noRoot = { false }
    override val tools: List<AgentTool> = listOf(
        ContractTool(DeviceReadTool(AndroidDeviceReadBackend(context, root, noRoot))),
        ContractTool(SettingReadTool(AndroidSettingBackend(context, root, noRoot))),
        ContractTool(AppSearchTool(AndroidAppSearchBackend(context))),
        ContractTool(AppOpenTool(AndroidAppOpenBackend(context, root, noRoot))),
    )
}

internal class TvMediaToolProvider(services: ToolServices) : ToolProvider {
    override val tools: List<AgentTool> = listOf(
        ContractTool(MediaControlTool(RealMediaControlBackend(services.context, services.root()))),
        ContractTool(VolumeSetTool(RealVolumeSetBackend(services.context))),
    )
    override val promptSection = PromptSection("tv_media", ToolDomain.CLOCK_MEDIA,
        "电视播放控制：media_control 只表示媒体键已派发；暂停、继续、快进、快退、换集后须核实播放器实际变化，" +
            "不能把派发成功说成播放成功。volume_set 按回读的 actual_percent 确认实际音量，静音设为 0。")
}
