package io.github.mangi.eta.agent.voice.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.mangi.eta.agent.voice.SystemSpeechRecognizer
import java.util.concurrent.atomic.AtomicBoolean

/** System / on-device SpeechRecognizer fallback when Doubao credentials or network fail. */
internal class SystemAsrEngine(
    private val context: Context,
) : EtaAsrEngine {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var recognizer: SpeechRecognizer? = null
    private var listener: EtaAsrEngine.Listener? = null
    private var latestPartial: String = ""

    override fun isRunning(): Boolean = running.get()

    override fun start(listener: EtaAsrEngine.Listener) {
        if (!running.compareAndSet(false, true)) return
        this.listener = listener
        latestPartial = ""
        val created = SystemSpeechRecognizer.create(context)
        if (created == null) {
            running.set(false)
            mainHandler.post {
                listener.onError("系统语音识别不可用", canFallback = false)
                listener.onEnded()
            }
            return
        }
        recognizer = created
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val text = firstResult(partialResults)
                if (text.isNotBlank()) {
                    latestPartial = text
                    listener.onPartial(text)
                }
            }

            override fun onResults(results: Bundle?) {
                val text = firstResult(results).ifBlank { latestPartial }
                running.set(false)
                if (text.isNotBlank()) listener.onFinal(text)
                listener.onEnded()
                releaseRecognizer()
            }

            override fun onError(error: Int) {
                running.set(false)
                listener.onError("系统识别错误 ($error)", canFallback = false)
                listener.onEnded()
                releaseRecognizer()
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        created.startListening(intent)
    }

    override fun stop(submitFinal: Boolean) {
        if (!running.get()) return
        recognizer?.stopListening()
    }

    override fun cancel() {
        running.set(false)
        recognizer?.cancel()
        releaseRecognizer()
        listener = null
    }

    private fun releaseRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun firstResult(bundle: Bundle?): String {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        return list?.firstOrNull().orEmpty().trim()
    }
}
