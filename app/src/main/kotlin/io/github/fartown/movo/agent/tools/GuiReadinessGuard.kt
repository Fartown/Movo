package io.github.fartown.movo.agent.tools

import android.content.Context
import io.github.fartown.movo.agent.accessibility.AccessibilityEnableResult
import io.github.fartown.movo.agent.accessibility.AgentAccessibilityKeeper
import io.github.fartown.movo.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.fartown.movo.agent.runtime.EntrySurfaceGuard
import io.github.fartown.movo.agent.tool.AgentToolRequirements
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
 * 1. UI 领域里观察 / 注入屏幕的工具尽力确保无障碍服务就绪（可触发恢复）；无障碍与 root 都不可用时拒绝——
 *    UI 操作无从执行，不冒领 ok。先查无障碍再关入口：操作不了时不白白关掉用户的语音面板。
 *    读写剪贴板走 ClipboardManager，不需要无障碍（与重构前和 [AgentToolRequirements] 的登记一致），不查也不拦；
 *    只看 [AgentToolRequirements] 明确登记为「不需要无障碍」的工具，没登记的 UI 工具（如 ui_focus）照旧要求。
 * 2. 要先关掉入口窗口（小布 / 小爱面板、Movo 自己的语音页）的工具，按 [AgentOverlayVisibilityPolicy] 的同一份名单判断：
 *    与悬浮层「操作其他 App 时揭开悬浮球」一致——会关入口的工具一定会揭开悬浮球，用户看得到反馈；
 *    app_open、clock_create 这类不属于 UI 领域、但会把别的界面拉到前台的工具也要先关入口；关闭未完成则本次不执行。
 *
 * 其余工具直接放行。供新类型化子系统的 [ToolPipeline] 作为前置 guard 使用。
 */
internal class GuiReadinessGuard(
    private val appContext: Context,
    private val ensureAccessibility: (Context) -> AccessibilityEnableResult =
        AgentAccessibilityKeeper::ensureEnabledForGuiOperation,
    private val entrySurfaceGuard: () -> EntrySurfaceGuard?,
) : ToolGuard {
    override fun check(tool: AgentTool, args: ToolArgs, ctx: ToolContext): ToolOutcome? {
        val needsAccessibility = tool.domain == ToolDomain.UI &&
            AgentToolRequirements.find(tool.name)?.accessibility != false
        val dismissesEntrySurface = AgentOverlayVisibilityPolicy.requiresEntrySurfaceDismissal(tool.name)
        if (!needsAccessibility && !dismissesEntrySurface) return null

        if (needsAccessibility) {
            val accessibility = ensureAccessibility(appContext)
            if (!accessibility.available && !ctx.env.rootAvailable) {
                return ToolOutcome.error(
                    ToolErrorCode.PERMISSION_REQUIRED,
                    accessibility.message.ifBlank { "Movo 无障碍服务未开启，本次 GUI 操作未执行" },
                    detail = accessibility.code.ifBlank { "ACCESSIBILITY_UNAVAILABLE" },
                )
            }
        }

        if (dismissesEntrySurface) {
            val guard = entrySurfaceGuard()
            if (guard != null && !guard.dismissOnce()) {
                return ToolOutcome.error(
                    ToolErrorCode.BUSY,
                    "入口窗口关闭未完成；本次工具未执行，请勿在当前任务中重复调用",
                    detail = "entry_surface_not_ready",
                )
            }
        }
        return null
    }
}
