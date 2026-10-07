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
        ContractTool(MediaSessionControlTool(services.context, { listOf(TvMediaAccess.component(services.context)) })),
        ContractTool(VolumeSetTool(RealVolumeSetBackend(services.context))),
        ContractTool(TvVideoSearchTool(services.context)),
    )
    override val promptSection = PromptSection("tv_media", ToolDomain.CLOCK_MEDIA,
        "电视播放控制：media_control 的 status 读取当前播放器状态和进度；跳到指定时间用 seek 加 position_s（秒）；动作带实际回读验证。" +
            "用户只要求控制播放或音量时，这一步就是最后一步：在 media_control / volume_set 的 reply 里写好对用户说的那一句，只说这一步做了什么（如「已跳到 5:00」），不加没确认的其他状态；结果没确认或有 warnings 时不会用它，会把结果交给你再说。" +
            "跳转是播放器近似定位，必须按 confirmed_position_ms 和 seek_error_ms 报告落点；播放时 position_ms 是推算值。未知结果先查状态，不能直接重复快进、快退或换集。volume_set 按回读的 actual_percent 确认实际音量，静音设为 0。" +
            "找片优先用 video_search 直达搜索结果页，再用界面工具选片播放；它失败或是其他视频 App 时，用 app_open 打开后在界面里搜索。")
}
