package io.github.fartown.movo.agent.tools.clockmedia

import android.content.Context
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.device.RootAccess
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.PromptSection
import io.github.fartown.movo.agent.tools.core.ToolAvailability
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
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
            - clock_create 回读核实：effect_verified=true 才代表系统已确认创建；false 表示已交给时钟应用但没核实到，
              如实告诉用户「已提交给时钟」并请他在时钟里看一眼，不要说已设好，也不要直接重复创建。没有取消能力，取消改走时钟界面。
            - 要核实闹钟、计时器有没有设上时用 clock_read 查看。
            - clock_create 报设备上没有时钟应用时，如实告诉用户设不了；报已打开时钟页时，请用户在页里自己设。
              不要拿倒计时、监听或通知代替闹钟：监听有最长时长，到点前就会结束，用户会以为设好了。
            - media_control 派发后回读播放状态：effect_verified=true 才是确认生效，false 只是媒体键已送达、没读到状态；
              报「现在没有正在播放的内容」时如实告诉用户，不要说已停住或已切换。
            - volume_set 回读确认实际音量；系统钳制或免打扰可能导致实际值与请求不一致，看 warnings 与 actual_percent。
        """.trimIndent(),
    )

    /** clock_read 要 Root：用不了时去掉提到它的句子，免得模型去调不存在的工具。 */
    override fun promptSection(env: ToolEnvironment): PromptSection {
        val clockReadAvailable = tools.first { it.name == "clock_read" }.availability(env) is ToolAvailability.Available
        if (clockReadAvailable) return promptSection
        val text = promptSection.text.lineSequence().filter { "clock_read" !in it }.joinToString("\n")
        return promptSection.copy(text = text)
    }

    companion object {
        val NAMES = setOf("clock_create", "clock_read", "media_control", "volume_set")
    }
}
