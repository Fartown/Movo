package io.github.fartown.movo.agent.tools.clockmedia

import android.content.Context
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.device.RootAccess
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.PromptSection
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.core.AgentLogger

/**
 * 时钟与音频领域：clock_create、clock_read、media_control、volume_set。
 * 真实后端由本 Provider 装配；[root] 由运行期共享资源提供（主流程接线）。
 */
internal class ClockMediaToolProvider(
    context: Context,
    logger: AgentLogger,
    root: BoundedRootCommandExecutor,
    rootAvailable: () -> Boolean = { RootAccess.isGranted },
) : ToolProvider {

    override val tools: List<AgentTool> = listOf(
        ContractTool(ClockCreateTool(RealClockCreateBackend(context, logger, root, rootAvailable))),
        ContractTool(ClockReadTool(RealClockReadBackend(context, root))),
        ContractTool(MediaControlTool(RealMediaControlBackend(context, root))),
        ContractTool(VolumeSetTool(RealVolumeSetBackend(context))),
    )

    override val promptSection = PromptSection(
        id = "clock_media",
        domain = ToolDomain.CLOCK_MEDIA,
        text = """
            ## 时钟与音频
            - clock_create 回读核实：只有 status=ok 才代表系统已确认创建；status=unknown 表示已提交但未核实到，
              要用 clock_read 查看或请用户在时钟界面确认，不要直接重复创建。没有取消能力，取消改走时钟界面。
            - clock_create 报设备上没有时钟应用时，如实告诉用户设不了；不要拿倒计时、监听或通知代替闹钟：
              监听有最长时长，到点前就会结束，用户会以为设好了。
            - media_control 是送达型：媒体键已派发不代表播放器已响应（effect_verified=false），需再查播放状态。
            - volume_set 回读确认实际音量；系统钳制或免打扰可能导致实际值与请求不一致，看 warnings 与 actual_percent。
        """.trimIndent(),
    )

    companion object {
        val NAMES = setOf("clock_create", "clock_read", "media_control", "volume_set")
    }
}
