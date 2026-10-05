package io.github.fartown.movo.agent.tools.core

/** 需要用户确认的工具调用怎么处理；由设备装配决定（实施方案 §5.5）。 */
internal enum class ApprovalMode {
    /** 弹审批卡，等用户允许或拒绝（手机）。 */
    ASK,

    /** 免审：直接执行，不弹审批卡（电视默认）。 */
    SKIP,
}
