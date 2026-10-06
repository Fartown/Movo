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
        ContractTool(TvMediaControlTool(services.context)),
        ContractTool(VolumeSetTool(RealVolumeSetBackend(services.context))),
    )
    override val promptSection = PromptSection("tv_media", ToolDomain.CLOCK_MEDIA,
        "电视播放控制：media_control 的 status 读取当前播放器状态和进度；动作带实际回读验证。" +
            "跳转是播放器近似定位，必须按 confirmed_position_ms 和 seek_error_ms 报告落点；播放时 position_ms 是推算值。未知结果先查状态，不能直接重复快进、快退或换集。volume_set 按回读的 actual_percent 确认实际音量，静音设为 0。")
}
