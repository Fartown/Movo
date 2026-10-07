package io.github.fartown.movo.agent.tools.terminal

import android.content.Context
import io.github.fartown.movo.agent.terminal.DetachedTaskSupervisor
import io.github.fartown.movo.agent.terminal.LinuxEnvironmentPaths
import io.github.fartown.movo.agent.terminal.SharedFolderMounts
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.PromptSection
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.core.AgentLogger

/**
 * 终端领域（domain=TERMINAL）的工具集合：terminal_run、terminal_job。
 * 两个工具共享一个 [TerminalJobRegistry]，运行结束由 [close] 统一回收（本次的后台命令和会话）；
 * keep_alive 常驻任务交给 [DetachedTaskSupervisor]，与终端页共用记录文件，不随本次任务结束（没传 [context] 时 keep_alive 用不了）。
 */
internal class TerminalToolProvider(
    logger: AgentLogger,
    context: Context? = null,
) : ToolProvider {

    private val registry = TerminalJobRegistry(
        logger,
        daemons = context?.let { ctx ->
            DetachedTaskSupervisor(
                logger = logger,
                recordsFile = DetachedTaskSupervisor.defaultRecordsFile(ctx),
                linuxRootfsPathProvider = { environment ->
                    environment.linuxDistribution?.let { LinuxEnvironmentPaths.rootfsDir(ctx, it).absolutePath }
                },
                linuxSharedMountsProvider = { SharedFolderMounts.current() },
            )
        },
    )

    override val tools: List<AgentTool> = listOf(
        ContractTool(TerminalRunTool(registry)),
        ContractTool(TerminalJobTool(registry)),
    )

    override val promptSection = PromptSection(
        id = "terminal",
        domain = ToolDomain.TERMINAL,
        text = "一次性命令用 terminal_run；长任务设 mode=background 或等超时自动转后台，用 terminal_job 查看，不要 sleep 轮询；" +
            "交互程序用 tty；访问 Android 用 environment=android，Linux 工具用 environment=linux。",
    )

    override fun close() {
        runCatching { registry.close() }
    }
}
