package io.github.fartown.movo.agent.tools

/**
 * 工具重构的切换开关。默认全部关闭——关时运行时完全走旧工具体系，新类型化子系统只被单测使用。
 *
 * [useTypedSubsystem] 打开后，运行时改用 [AgentToolSubsystem] 的目录与执行器（S4 非 UI 接线）。
 * 注意：此路径的审批 UI 仍是临时占位（UserInteraction 不可用 → 外发工具安全拒绝、不误报成功），
 * 真正可用需先完成 S5 的确认卡/提问卡（须先出 Figma 稿、经用户 review）并通过小米真机验收。
 * 旧代码在切换稳定前不删除。改此值需走真机验证，不要在未验证时默认开启。
 */
internal object AgentToolFeatureFlags {
    const val useTypedSubsystem: Boolean = true
}
