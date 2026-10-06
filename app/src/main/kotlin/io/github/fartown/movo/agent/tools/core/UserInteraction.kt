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

/**
 * 一次审批请求（手动审批模式）：[detail] 是要执行的动作和关键参数，[reason] 是为什么问你。
 * [category] 为空表示命中的是「某个应用里先问我」。
 */
internal data class ApprovalRequest(
    val toolName: String,
    val title: String,
    val detail: String,
    val category: ApprovalCategory?,
    val reason: String = reasonSentence(category),
)

internal sealed interface ApprovalDecision {
    data object Approved : ApprovalDecision
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
