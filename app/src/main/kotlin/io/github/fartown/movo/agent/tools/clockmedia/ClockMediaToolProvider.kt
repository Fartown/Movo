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
        ContractTool(
            MediaSessionControlTool(
                context,
                {
                    listOf(
                        io.github.fartown.movo.agent.device.MediaAccessService.component(context),
                        android.content.ComponentName(context, io.github.fartown.movo.agent.device.AgentNotificationHistoryService::class.java),
                    )
                },
                keyFallback = MediaControlTool(RealMediaControlBackend(context, root)),
            ),
        ),
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
            - media_control 通过系统媒体会话控制正在播放的 App，动作后回读状态确认：status 读状态和进度，跳到指定时间用 seek 加 position_s（秒），
              快进快退用 seconds。effect_verified=true 才是确认生效；status=unknown 或 effect_verified=false 时先查状态再说，不要直接重复。
              报「现在没有正在播放的内容」时如实告诉用户，不要说已停住或已切换。没授权「播放控制」时只有 play、pause、stop、toggle
              会派发媒体键（并回读），读状态、上下集和跳转要改用界面操作。
            - 用户只要求控制播放或音量（暂停、继续、上下集、快进快退、跳到某处、调音量）时，这一步就是最后一步：
              在 media_control / volume_set 的 reply 里写好对用户说的那一句，只说这一步做了什么（如「已跳到 5:00」），不加没确认的其他状态；
              结果没确认或有 warnings 时不会用它，会把结果交给你再说。
            - volume_set 回读确认实际音量；系统钳制或免打扰可能导致实际值与请求不一致，看 warnings 与 actual_percent。
        """.trimIndent(),
    )

    companion object {
        val NAMES = setOf("clock_create", "clock_read", "media_control", "volume_set")
    }
}
