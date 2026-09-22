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
        fun onFinal(text: String)
        fun onError(message: String)
        fun onEnded()
    }
}
