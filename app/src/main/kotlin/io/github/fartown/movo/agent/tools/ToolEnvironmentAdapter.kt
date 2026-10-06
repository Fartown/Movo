package io.github.fartown.movo.agent.tools

import io.github.fartown.movo.agent.tool.AgentToolCapabilities
import io.github.fartown.movo.agent.tools.core.ApprovalPolicy
import io.github.fartown.movo.agent.tools.core.ModelInput
import io.github.fartown.movo.agent.tools.core.MemoryScope
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolSwitches

/**
 * S4 接入纯逻辑适配器：把运行时的能力快照 [AgentToolCapabilities]（旧体系的权限/Root/机型探测）
 * 映射成工具子系统每轮冻结的 [ToolEnvironment]。开关、会话类型、模型能力由调用方按配置传入。
 *
 * 这一层不碰 UI、不碰审批通道——接实时 loop 时只需在 complete() 里用它构造 env 提供器，
 * 审批 UI（UserInteraction）由 S5 的确认卡/提问卡接线后注入，属需 Figma 评审与真机验收的范围。
 */
internal fun AgentToolCapabilities.toToolEnvironment(
    switches: ToolSwitches,
    linuxReady: Boolean,
    memoryScope: MemoryScope,
    conversationBound: Boolean,
    interactive: Boolean,
    spokenReply: Boolean = false,
    conversationId: String? = null,
    modelInputs: Set<ModelInput>,
    approvalPolicy: ApprovalPolicy = ApprovalPolicy.YOLO,
): ToolEnvironment = ToolEnvironment(
    rootAvailable = rootAvailable,
    lsposedAvailable = lsposedAvailable,
    accessibilityAvailable = accessibilityAvailable,
    accessibilityRecoverable = accessibilityRecoveryAvailable,
    notificationAccess = notificationsAllowed,
    usageAccess = usageAllowed,
    locationAccess = locationAllowed,
    colorOs = colorOs,
    linuxReady = linuxReady,
    switches = switches,
    memoryScope = memoryScope,
    conversationBound = conversationBound,
    conversationId = conversationId,
    interactive = interactive,
    spokenReply = spokenReply,
    modelInputs = modelInputs,
    approvalPolicy = approvalPolicy,
)
