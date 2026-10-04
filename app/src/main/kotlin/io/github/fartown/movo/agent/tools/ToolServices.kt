package io.github.fartown.movo.agent.tools

import android.content.Context
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.device.RootAccess
import io.github.fartown.movo.agent.device.RootShellDeviceController
import io.github.fartown.movo.core.AgentLogger

/**
 * 一次运行中多个领域共用的重量级资源。按需创建，运行结束统一关闭。
 * 领域专属的资源（终端、浏览器、技能库等）由各自的 ToolProvider 持有。
 */
internal class ToolServices(
    val context: Context,
    val logger: AgentLogger,
    val runId: String,
    val screenshotExcludedPackages: () -> Set<String> = { emptySet() },
    val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) : AutoCloseable {
    val deviceController: RootShellDeviceController by lazy {
        RootShellDeviceController(logger, screenshotExcludedPackages, rootAvailable)
    }

    val rootCommandExecutor: BoundedRootCommandExecutor by lazy {
        BoundedRootCommandExecutor(logger, rootAvailable = rootAvailable)
    }

    private var rootExecutorCreated = false

    /** 记录创建，便于关闭时只关闭真正用过的资源。 */
    fun root(): BoundedRootCommandExecutor {
        rootExecutorCreated = true
        return rootCommandExecutor
    }

    override fun close() {
        if (rootExecutorCreated) runCatching { rootCommandExecutor.close() }
    }
}
