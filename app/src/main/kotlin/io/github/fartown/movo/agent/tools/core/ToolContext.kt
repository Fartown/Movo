package io.github.fartown.movo.agent.tools.core

import android.content.Context
import io.github.fartown.movo.agent.runtime.AgentRunCancelledException
import io.github.fartown.movo.core.AgentLogger

/** 单次工具调用的上下文。工具实现只能通过它感知运行状态，不持有 Runtime 或界面对象。 */
internal class ToolContext(
    val appContext: Context,
    val logger: AgentLogger,
    val runId: String,
    val toolCallId: String,
    val env: ToolEnvironment,
    val interaction: UserInteraction,
    private val cancelled: () -> Boolean,
) {
    val isCancelled: Boolean get() = cancelled()

    fun checkCancelled() {
        if (cancelled()) throw AgentRunCancelledException()
    }
}
