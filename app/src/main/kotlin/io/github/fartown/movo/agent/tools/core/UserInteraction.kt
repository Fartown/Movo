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
    /** 可以“一直允许”的范围说明，例如“微信”“adb shell 命令 pm”；为空时不提供长期允许。 */
    val rememberScope: String?,
    val reason: ApprovalReason,
)

internal enum class ApprovalReason {
    /** 动作对外或不可逆。 */
    EXTERNAL_EFFECT,
    /** 目标在受保护应用中（支付、银行、系统设置、通讯发送界面）。 */
    PROTECTED_APP,
    /** 本轮读过不可信内容或个人数据后，又要执行可能外发的动作。 */
    TAINTED,
    /** 模型声明了动作后果（send / pay / delete / submit）。 */
    DECLARED_EFFECT,
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
