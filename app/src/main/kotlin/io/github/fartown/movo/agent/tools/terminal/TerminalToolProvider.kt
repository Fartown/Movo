package io.github.fartown.movo.agent.tools.terminal

import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.PromptSection
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.core.AgentLogger

/**
 * 终端领域（domain=TERMINAL）的工具集合：terminal_run、terminal_job。
 * 两个工具共享一个 [TerminalJobRegistry]，运行结束由 [close] 统一回收；keep_alive 的进程不停止，但之后的任务管不到它（见 registry）。
 */
internal class TerminalToolProvider(
    logger: AgentLogger,
) : ToolProvider {

    private val registry = TerminalJobRegistry(logger)

    override val tools: List<AgentTool> = listOf(
        ContractTool(TerminalRunTool(registry)),
        ContractTool(TerminalJobTool(registry)),
    )

    /** 终端与 Linux 环境的用法（重构前写在系统提示里的那段，按有没有 Root 分两种身份说明）。 */
    override fun promptSection(env: ToolEnvironment) = PromptSection(
        id = "terminal",
        domain = ToolDomain.TERMINAL,
        text = """
            ## 终端与 Linux
            - 一次性命令用 terminal_run；长任务设 mode=background 或等超时自动转后台，用 terminal_job 查看，不要 sleep 轮询；交互程序用 tty。
            - Android 应用与当前身份能访问的设备文件用 environment=android；用户选的 Alpine 或 Debian 工具环境统一用 environment=linux，不要自行换发行版。用户没指定环境的命令用 environment=android。
            - ${identityRule(env)}
            - 返回 Linux 环境尚未就绪（linux_not_ready）时，告诉用户先到 设置 → 工具 → Linux 工具环境 安装，不要说成设备不支持。Linux 里缺基础命令时，告诉用户在 Linux 工具环境页「安装基础工具」；Python/uv、Node.js、SSH、APK 分析在同一页按需安装。不要在 Android 环境冒充或自行下载这些工具。
            - Linux 环境默认在 /workspace 工作（映射到宿主工作区，实际路径以终端返回为准）。用户配置的共享文件夹挂在 /workspace/mounts/ 下，每个子目录对应一个 Android 目录：用户提到共享文件、手机目录或要处理设备上的文件时，先 ls /workspace/mounts/ 确认已有共享再读写对应子目录；没共享的目录（包括 /sdcard）不要假定能访问。
            - 分析 APK 优先在 linux 环境用 jadx、apktool、smali、baksmali；命令不存在时告诉用户在 Linux 工具环境页安装「APK 分析」，不要自行下载不受校验的工具。Apktool 只能解码和检查，不能回编译，不要声称已经生成可安装的 APK。
            - 要在任务结束后继续运行的服务（监听端口、Web 面板）用 mode=keep_alive，不要用 nohup 或 & 手工后台化。
            - Movo 自带终端：不要回答「没有终端应用」，不要用 app_search 找「终端」「Termux」，也不要让用户另装终端 App。
        """.trimIndent(),
    )

    private fun identityRule(env: ToolEnvironment): String = if (env.rootAvailable) {
        "需要 Android 特权时设 identity=root；Linux 里的身份由选中的后端决定。"
    } else {
        "这台手机没有 Root，终端只能用 identity=user，以 Movo 的应用身份运行；Linux 里的模拟 root 不给 Android 特权。"
    }

    override fun close() {
        runCatching { registry.close() }
    }
}
