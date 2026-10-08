package io.github.fartown.movo.agent.tools.file

import android.content.Context
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.device.RootAccess
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.PromptSection
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolProvider

/**
 * 文件领域（domain=FILE）的工具集合：file_search、file_read、file_write、file_list。
 * Root 文件操作复用 [BoundedRootCommandExecutor]；由调用方传入共享实例，关闭由其负责。
 */
internal class FileToolProvider(
    context: Context,
    rootExecutor: BoundedRootCommandExecutor,
    rootAvailable: () -> Boolean = { RootAccess.isGranted },
) : ToolProvider {

    override val tools: List<AgentTool> = listOf(
        ContractTool(FileSearchTool(RealFileSearchBackend(context, rootExecutor, rootAvailable))),
        ContractTool(FileReadTool(RealFileReadBackend(context, rootExecutor, rootAvailable))),
        ContractTool(FileWriteTool(RealFileWriteBackend(context, rootAvailable))),
        ContractTool(FileListTool(RealFileListBackend(context, rootExecutor, rootAvailable))),
    )

    override val promptSection = PromptSection(
        id = "file",
        domain = ToolDomain.FILE,
        text = "用户说的是 Movo 工作区里的文件（例如刚写的、只给了文件名）时，直接用 file_read 读相对路径（相对路径就在工作区），" +
            "或先 file_list 看工作区；在手机共享存储里找文件才用 file_search 拿句柄再交给 file_read，不要猜路径。" +
            "读文本给 offset/limit 分页；写文件用 file_write（默认工作区，写系统位置要确认）；列目录用 file_list。" +
            "读图片内容也用 file_read（图片会直接附给你）：同一轮回复最多读一张，要看多张就等这张返回、看过内容后，" +
            "下一轮再读下一张，不要在同一轮并行或批量读多张。",
    )
}
