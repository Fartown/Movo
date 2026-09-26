package io.github.mangi.eta.agent.voice.asr

import android.content.Context
import io.github.mangi.eta.data.model.DoubaoSpeechCredentials
import io.github.mangi.eta.data.repository.VoiceSettingsRepository

/**
 * Creates Doubao-only dictation sessions. Missing credentials are reported by the engine.
 */
internal object EtaAsrSessionFactory {
    fun create(
        context: Context,
        credentials: DoubaoSpeechCredentials = VoiceSettingsRepository.loadDoubaoCredentials(),
    ): EtaAsrEngine = DoubaoBidirectionalAsrEngine(context, credentials.normalized())
}

/**
 * Owns one dictation session; failures are surfaced without switching recognizers.
 */
internal class EtaDictationController(
    private val createEngine: () -> EtaAsrEngine,
) {
    constructor(context: Context) : this({ EtaAsrSessionFactory.create(context) })

    private var engine: EtaAsrEngine? = null

    fun isRunning(): Boolean = engine?.isRunning() == true

    fun start(listener: EtaAsrEngine.Listener) {
        cancel()
        val created = createEngine()
        engine = created
        created.start(wrap(created, listener))
    }

    fun stop(submitFinal: Boolean = true) {
        if (submitFinal) {
            engine?.stop(submitFinal = true)
        } else {
            cancel()
        }
    }

    fun cancel() {
        val current = engine
        engine = null
        current?.cancel()
    }

    private fun wrap(current: EtaAsrEngine, listener: EtaAsrEngine.Listener): EtaAsrEngine.Listener =
        object : EtaAsrEngine.Listener {
            override fun onPartial(text: String) {
                if (engine === current) listener.onPartial(text)
            }

            override fun onPartial(text: String, confirmedLength: Int) {
                if (engine === current) listener.onPartial(text, confirmedLength)
            }

            override fun onLevel(level: Float) {
                if (engine === current) listener.onLevel(level)
            }

            override fun onFinal(text: String) {
                if (engine === current) listener.onFinal(text)
            }

            override fun onError(message: String) {
                if (engine === current) listener.onError(message)
            }

            override fun onEnded() {
                if (engine === current) {
                    engine = null
                    listener.onEnded()
                }
            }
        }
}
