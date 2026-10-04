package io.github.fartown.movo.agent.tools

import android.content.Context
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityKeeper
import io.github.fartown.movo.agent.runtime.EntrySurfaceGuard
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolGuard
import io.github.fartown.movo.agent.tools.core.ToolOutcome

/**
 * GUI 就绪守卫（移植旧 `AgentLocalTools.beforeToolExecution` 的职责）。
 *
 * 在任何 UI 领域工具执行前：
 * 1. 关闭 Movo 自己的入口窗口（悬浮结果卡 / 会话面），避免它挡住目标应用或被误操作；关闭未完成则本次不执行。
 * 2. 尽力确保无障碍服务就绪（可触发恢复）；无障碍与 root 都不可用时拒绝——UI 操作无从执行，不冒领 ok。
 *
 * 非 UI 领域工具直接放行。供新类型化子系统的 [ToolPipeline] 作为前置 guard 使用。
 */
internal class GuiReadinessGuard(
    private val appContext: Context,
    private val entrySurfaceGuard: () -> EntrySurfaceGuard?,
) : ToolGuard {
    override fun check(tool: AgentTool, args: ToolArgs, ctx: ToolContext): ToolOutcome? {
        if (tool.domain != ToolDomain.UI) return null

        val guard = entrySurfaceGuard()
        if (guard != null && !guard.dismissOnce()) {
            return ToolOutcome.error(
                ToolErrorCode.BUSY,
                "入口窗口关闭未完成；本次工具未执行，请勿在当前任务中重复调用",
                detail = "entry_surface_not_ready",
            )
        }

        val accessibility = AgentAccessibilityKeeper.ensureEnabledForGuiOperation(appContext)
        if (!accessibility.available && !ctx.env.rootAvailable) {
            return ToolOutcome.error(
                ToolErrorCode.PERMISSION_REQUIRED,
                accessibility.message.ifBlank { "Movo 无障碍服务未开启，本次 GUI 操作未执行" },
                detail = accessibility.code.ifBlank { "ACCESSIBILITY_UNAVAILABLE" },
            )
        }
        return null
    }
}
