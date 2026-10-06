package io.github.fartown.movo.ui.model

/**
 * 界面当前待处理的一次同步交互（提问卡 / 确认卡）。由运行时 `AgentEvent.InteractionRequested` 投影而来，
 * 用户作答后经 wire 消息按 [requestId] 回传给正在等待的 [runId]；`InteractionResolved` 或作答后清空。
 */
internal data class AgentInteractionUiState(
    val runId: String,
    val requestId: String,
    /** true 为审批（允许 / 拒绝），false 为提问（选项 + 可选自由文本）。 */
    val isApproval: Boolean,
    val title: String,
    val detail: String,
    val options: List<String> = emptyList(),
    val allowFreeText: Boolean = true,
    /** 审批卡灰底块下面一行：为什么问你，例如「手动审批时，付款、转账都会先问你。」。 */
    val note: String? = null,
    /** 审批原因码：动作类别名（PAYMENT、SEND……）或 APP_RULE（在你选的应用里），界面据此选卡头样式。 */
    val reason: String? = null,
)
