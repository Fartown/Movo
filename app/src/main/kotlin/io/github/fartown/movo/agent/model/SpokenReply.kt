package io.github.fartown.movo.agent.model

/**
 * 这一轮的最终答复会不会被念出来（语音简短回复方案 §4）。
 * 模型分不出用户是说的还是打的字，由入口决定，再通过系统提示末尾的语音段告诉它。
 */
internal enum class SpokenReply {
    /** 打字或其他不念的入口。 */
    NONE,

    /** Movo 自己的语音会话：答复会被念出来，对话记录里也是这段文字。 */
    MOVO_VOICE,

    /** 超级小爱交给 Movo：小爱念答复，结果显示在小爱卡片上，不进 Movo 对话记录。 */
    XIAOAI;

    val spoken: Boolean get() = this != NONE
}
