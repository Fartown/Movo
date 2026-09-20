package io.github.mangi.eta.agent.voice.asr

import android.content.Context
import io.github.mangi.eta.data.model.DoubaoSpeechCredentials
import io.github.mangi.eta.data.repository.VoiceSettingsRepository

/**
 * Creates ASR sessions: Doubao streaming first, system SpeechRecognizer as fallback.
 */
internal object EtaAsrSessionFactory {
    fun createPreferred(
        context: Context,
        credentials: DoubaoSpeechCredentials = VoiceSettingsRepository.loadDoubaoCredentials(),
        allowSystemFallback: Boolean = true,
    ): Pair<EtaAsrEngine, AsrBackend> {
        val normalized = credentials.normalized()
        if (normalized.hasUsableAuth()) {
            return DoubaoBidirectionalAsrEngine(context, normalized) to AsrBackend.Doubao
        }
        require(allowSystemFallback) { "豆包语音凭证未配置" }
        return SystemAsrEngine(context) to AsrBackend.System
    }

    fun createSystem(context: Context): EtaAsrEngine = SystemAsrEngine(context)
}

/**
 * Dictation helper that auto-falls back to system ASR once on Doubao failure.
 */
internal class EtaDictationController(
    private val context: Context,
    private val allowSystemFallback: Boolean = true,
) {
    private var engine: EtaAsrEngine? = null
    private var backend: AsrBackend? = null
    private var fellBack = false

    fun isRunning(): Boolean = engine?.isRunning() == true

    fun start(listener: EtaAsrEngine.Listener) {
        stop(submitFinal = false)
        fellBack = false
        val (created, usedBackend) = EtaAsrSessionFactory.createPreferred(
            context = context,
            allowSystemFallback = allowSystemFallback,
        )
        engine = created
        backend = usedBackend
        created.start(wrap(listener))
    }

    fun stop(submitFinal: Boolean = true) {
        engine?.stop(submitFinal)
        engine = null
        backend = null
    }

    fun cancel() {
        engine?.cancel()
        engine = null
        backend = null
    }

    fun currentBackend(): AsrBackend? = backend

    private fun wrap(listener: EtaAsrEngine.Listener): EtaAsrEngine.Listener =
        object : EtaAsrEngine.Listener {
            override fun onPartial(text: String) = listener.onPartial(text)
            override fun onFinal(text: String) = listener.onFinal(text)
            override fun onEnded() = listener.onEnded()
            override fun onError(message: String, canFallback: Boolean) {
                if (canFallback && allowSystemFallback && !fellBack && backend == AsrBackend.Doubao) {
                    fellBack = true
                    engine?.cancel()
                    val system = EtaAsrSessionFactory.createSystem(context)
                    engine = system
                    backend = AsrBackend.System
                    system.start(listener)
                    return
                }
                listener.onError(message, canFallback = false)
            }
        }
}
