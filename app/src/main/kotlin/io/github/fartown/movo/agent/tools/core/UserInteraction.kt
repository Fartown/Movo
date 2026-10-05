package io.github.fartown.movo.agent.tools.core

/** 提问的候选项。 */
internal data class QuestionOption(val label: String, val detail: String? = null)

internal data class UserQuestion(
    val question: String,
    val options: List<QuestionOption> = emptyList(),
    val allowFreeText: Boolean = true,
)

internal sealed interface UserAnswer {
    data class Answered(val text: String, val optionIndex: Int?) : UserAnswer
    data object Declined : UserAnswer
    data object TimedOut : UserAnswer
    data object Unavailable : UserAnswer
}

/** 一次审批请求：展示要执行的动作和关键参数。 */
internal data class ApprovalRequest(
    val toolName: String,
    val title: String,
    val detail: String,
    /**
     * 卡片上勾选框的完整文案：「本次任务内，这类操作都允许」或一直允许的说法（如「以后读取短信不再询问」）；
     * 为空时不显示勾选框。
     */
    val rememberScope: String?,
    val reason: ApprovalReason,
)

internal enum class ApprovalReason {
    /** 动作对外或不可逆。 */
    EXTERNAL_EFFECT,
    /** 用户指定的受保护应用（默认没有）里的操作；用户设过受保护应用、却认不出当前是哪个应用时也用它。 */
    PROTECTED_APP,
    /** 本轮既读过不可信内容、又读过个人数据之后，又要执行会把内容发出去的动作。 */
    TAINTED,
    /** 模型声明了动作后果（send / delete / submit）。 */
    DECLARED_EFFECT,
    /** 支付、转账：深色确认，不能勾「本次任务内都允许」（定稿 16-02）。 */
    PAYMENT,
    /** 第一次读取短信、通话记录这类个人数据。 */
    PERSONAL_DATA,
}

internal sealed interface ApprovalDecision {
    data class Approved(val remember: Boolean) : ApprovalDecision
    data object Declined : ApprovalDecision
    data object TimedOut : ApprovalDecision
    data object Unavailable : ApprovalDecision
}

/**
 * 与用户的同步交互：提问与审批共用同一个交互面。实现方在等待期间暂停卡住检测，
 * 取消运行时立即返回 [UserAnswer.Declined] / [ApprovalDecision.Declined]。
 */
internal interface UserInteraction {
    val available: Boolean
    fun ask(question: UserQuestion, timeoutMs: Long): UserAnswer
    fun approve(request: ApprovalRequest, timeoutMs: Long): ApprovalDecision

    companion object {
        val NONE: UserInteraction = object : UserInteraction {
            override val available: Boolean = false
            override fun ask(question: UserQuestion, timeoutMs: Long): UserAnswer = UserAnswer.Unavailable
            override fun approve(request: ApprovalRequest, timeoutMs: Long): ApprovalDecision =
                ApprovalDecision.Unavailable
        }
    }
}
