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
        ContractTool(FileSearchTool(RealFileSearchBackend(context, rootAvailable))),
        ContractTool(FileReadTool(RealFileReadBackend(context, rootExecutor, rootAvailable))),
        ContractTool(FileWriteTool(RealFileWriteBackend(context, rootAvailable))),
        ContractTool(FileListTool(RealFileListBackend(context, rootExecutor, rootAvailable))),
    )

    override val promptSection = PromptSection(
        id = "file",
        domain = ToolDomain.FILE,
        text = "找文件用 file_search 拿句柄再交给 file_read，不要猜路径；读文本给 offset/limit 分页；" +
            "写文件用 file_write（默认工作区，写系统位置要确认）；列目录用 file_list。",
    )
}
