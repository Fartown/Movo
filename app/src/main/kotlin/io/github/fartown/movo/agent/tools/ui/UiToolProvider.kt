package io.github.fartown.movo.agent.tools.ui

import android.content.Context
import io.github.fartown.movo.agent.device.RootAccess
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.PromptSection
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.core.AgentLogger

/**
 * 屏幕 UI 领域：ui_observe、ui_tap、ui_scroll、ui_swipe、ui_input、ui_key、ui_wait、
 * clipboard_read、clipboard_write。
 *
 * ui_observe 与所有动作工具共享同一个 [RealUiScreenBackend]（观察登记表 + 注入后端），
 * 保证代际校验与前台包归因一致。剪贴板读写各自真实后端。
 */
internal class UiToolProvider(
    context: Context,
    logger: AgentLogger,
    rootAvailable: () -> Boolean = { RootAccess.isGranted },
    includeTouchscreenTools: Boolean = true,
    /** 截图时要排除的包（正在退场的入口面板），由运行时的 EntrySurfaceGuard 提供。 */
    screenshotExcludedPackages: () -> Set<String> = { emptySet() },
) : ToolProvider {

    private val screen = RealUiScreenBackend(context, logger, rootAvailable, screenshotExcludedPackages)

    override val tools: List<AgentTool> = listOfNotNull(
        ContractTool(UiObserveTool(screen)),
        ContractTool(UiTapTool(screen, screen)),
        ContractTool(UiFocusTool(screen, screen)),
        ContractTool(UiScrollTool(screen, screen)),
        if (includeTouchscreenTools) ContractTool(UiSwipeTool(screen, screen)) else null,
        ContractTool(UiInputTool(screen, screen)),
        ContractTool(UiKeyTool(screen, screen)),
        ContractTool(UiWaitTool(screen)),
        ContractTool(ClipboardReadTool(RealClipboardReadBackend(context))),
        ContractTool(ClipboardWriteTool(RealClipboardWriteBackend(context))),
    )

    /** 领域系统提示（仅无障碍可用时注入，见定义清单 §C 抬头）。 */
    override val promptSection = PromptSection(
        id = "ui_screen",
        domain = ToolDomain.UI,
        text = """
            ## 屏幕
            - 先 ui_observe 再操作；优先用 index（它绑定对应那次观察的代际）。
            - 每次新观察让旧 index 失效；动作结果不返回新的 observation_id。
            - 动作 ok 只代表已送达（effect_verified=false），关键步骤后重新 ui_observe 确认。
            - ui_scroll 能判定移动：moved=true 才算滚到；没动可能到边界。
            - ui_input 文字回读一致才算证实；submit 是独立动作，不代表已发送。append 无法插入时不要改用 replace。
            - 遇 unknown 先观察，不要直接重复同一动作。
            - 无触屏设备只按观察节点操作。ui_focus 可用时用于移动遥控焦点；未提供的截图、滑动和坐标参数不可使用。
        """.trimIndent(),
    )

    companion object {
        val NAMES = setOf(
            "ui_observe", "ui_tap", "ui_scroll", "ui_swipe", "ui_input", "ui_key", "ui_wait",
            "clipboard_read", "clipboard_write", "ui_focus",
        )
    }
}
