package io.github.mangi.eta.agent.voice.session

import androidx.compose.runtime.Immutable

/**
 * 语音通道状态。它与"任务通道"（Agent run）和"呈现面"（App 聊天页 / 浮层 Sheet）互相正交：
 * 结束语音不取消任务，换界面不影响语音，任务在跑也不阻止说话。
 */
internal enum class VoiceChannel {
    /** 语音通道关闭，当前是文字模态。 */
    Off,
    Connecting,
    /** 已就绪，等待用户开口。 */
    Listening,
    /** 正在听用户说话。 */
    Hearing,
    /** 已提交，等待 Agent 回答。 */
    Thinking,
    /** 正在播报，可直接插话。 */
    Speaking,
}

@Immutable
internal data class VoiceSessionUiState(
    val channel: VoiceChannel = VoiceChannel.Off,
    /** 控制器给出的用户可读状态；界面不解析它，只展示。 */
    val statusText: String = "",
    /** 实时字幕。与输入框草稿是两个独立的东西，永远不互相覆盖。 */
    val transcript: String = "",
    val conversationId: String? = null,
    /**
     * 语音结束或没能开始的原因。状态条随语音关闭一起收起，这条提示在输入框上方再停留几秒，
     * 让"网络失败""没听到说话""这句话已存回输入框"之类的结果不至于一闪而过。
     */
    val notice: String? = null,
    /** 本次会话里已作为补充交给当前任务的句数；每多一句，展开卡的「已补充」停留 1.5s（规范 8.1 / 9.6）。 */
    val supplements: Int = 0,
    /** 检测到停顿、正在等待自动发送（主按钮外圈进度环，规范 9.6「停顿后自动发送」）。 */
    val autoSendPending: Boolean = false,
    /** 每次检测到停顿加一：进度环据此从头走。 */
    val autoSendGeneration: Int = 0,
) {
    val active: Boolean get() = channel != VoiceChannel.Off
    val speaking: Boolean get() = channel == VoiceChannel.Speaking

    internal companion object {
        /** 这些事件表示不再等待自动发送：又开口了、已经发出、或语音状态变了。 */
        private val autoSendResetEvents = setOf("hearing", "dispatch", "listening", "speaking", "result", "ended", "cancel.request", "committed")

        fun autoSendPendingAfter(event: String, previous: Boolean): Boolean = when (event) {
            "endpoint" -> true
            in autoSendResetEvents -> false
            else -> previous
        }

        /** 控制器的事件名到通道状态的唯一映射；不在界面里散落 when 分支。 */
        fun channelFor(event: String, active: Boolean, previous: VoiceChannel): VoiceChannel {
            if (!active) return VoiceChannel.Off
            return when (event) {
                "connecting" -> VoiceChannel.Connecting
                "listening", "committed", "result" -> VoiceChannel.Listening
                "hearing", "transcript" -> VoiceChannel.Hearing
                "dispatch", "cancel.request" -> VoiceChannel.Thinking
                "speaking" -> VoiceChannel.Speaking
                "ended" -> VoiceChannel.Off
                // endpoint / input.activity 等不改变通道，只刷新文案与字幕。
                else -> if (previous == VoiceChannel.Off) VoiceChannel.Listening else previous
            }
        }
    }
}
