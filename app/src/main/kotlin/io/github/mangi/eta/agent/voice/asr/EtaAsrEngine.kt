package io.github.mangi.eta.agent.voice.asr

/**
 * Callback surface shared by Doubao streaming ASR and system SpeechRecognizer fallback.
 */
internal interface EtaAsrEngine {
    fun start(listener: Listener)
    fun stop(submitFinal: Boolean = true)
    fun cancel()
    fun isRunning(): Boolean

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String, canFallback: Boolean = false)
        fun onEnded()
    }
}

internal enum class AsrBackend {
    Doubao,
    System,
}
