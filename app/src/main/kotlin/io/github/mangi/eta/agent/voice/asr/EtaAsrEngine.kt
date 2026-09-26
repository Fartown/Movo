package io.github.mangi.eta.agent.voice.asr

/**
 * Callback surface for dictation sessions.
 */
internal interface EtaAsrEngine {
    fun start(listener: Listener)
    fun stop(submitFinal: Boolean = true)
    fun cancel()
    fun isRunning(): Boolean

    interface Listener {
        fun onPartial(text: String)

        /**
         * 带确认进度的中间结果：[text] 是本次会话到目前为止的全文，前 [confirmedLength] 个字符已被引擎确认，
         * 其余是还可能改写的部分（规范 8.2 `Composer/Dictation`：未确认部分三级色）。默认退回 [onPartial]。
         */
        fun onPartial(text: String, confirmedLength: Int) = onPartial(text)
        fun onFinal(text: String)
        fun onError(message: String)
        fun onEnded()

        /** 麦克风电平 0–1（−50 dB → 0、−10 dB → 1，规范 9.6「音量信号」），约每 50ms 一次，主线程回调。 */
        fun onLevel(level: Float) = Unit
    }
}
