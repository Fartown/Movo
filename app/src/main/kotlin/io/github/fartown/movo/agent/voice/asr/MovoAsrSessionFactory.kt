package io.github.fartown.movo.agent.voice.asr

import android.content.Context
import io.github.fartown.movo.data.model.DoubaoSpeechCredentials
import io.github.fartown.movo.data.repository.VoiceSettingsRepository

/**
 * Creates Doubao-only dictation sessions. Missing credentials are reported by the engine.
 */
internal object MovoAsrSessionFactory {
    fun create(
        context: Context,
        credentials: DoubaoSpeechCredentials = VoiceSettingsRepository.loadDoubaoCredentials(),
    ): MovoAsrEngine = DoubaoBidirectionalAsrEngine(context, credentials.normalized())
}

/**
 * Owns one dictation session; failures are surfaced without switching recognizers.
 */
internal class MovoDictationController(
    private val createEngine: () -> MovoAsrEngine,
) {
    constructor(context: Context) : this({ MovoAsrSessionFactory.create(context) })

    private var engine: MovoAsrEngine? = null

    fun isRunning(): Boolean = engine?.isRunning() == true

    fun start(listener: MovoAsrEngine.Listener) {
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

    private fun wrap(current: MovoAsrEngine, listener: MovoAsrEngine.Listener): MovoAsrEngine.Listener =
        object : MovoAsrEngine.Listener {
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
