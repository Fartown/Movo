package io.github.fartown.movo.ui.model

/**
 * 界面当前待处理的一次同步交互（提问卡 / 确认卡）。由运行时 `AgentEvent.InteractionRequested` 投影而来，
 * 用户作答后经 wire 消息按 [requestId] 回传给正在等待的 [runId]；`InteractionResolved` 或作答后清空。
 */
internal data class AgentInteractionUiState(
    val runId: String,
    val requestId: String,
    /** true 为审批（允许/拒绝 + 可选“一直允许”），false 为提问（选项 + 可选自由文本）。 */
    val isApproval: Boolean,
    val title: String,
    val detail: String,
    val options: List<String> = emptyList(),
    val allowFreeText: Boolean = true,
    /** 非空时审批卡提供“一直允许（此范围）”，文案即范围说明，例如“微信”。 */
    val rememberLabel: String? = null,
    /** 审批原因码（ApprovalReason 名），界面据此区分普通外发 / 受保护应用（支付解锁等）样式。 */
    val reason: String? = null,
)
