package io.github.mangi.eta.agent.voice.wake

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.mangi.eta.agent.voice.SystemSpeechRecognizer
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.WakePhraseRules
import io.github.mangi.eta.data.model.WakeSensitivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Best-effort local wake detection when Sherpa-ONNX models/native are unavailable.
 *
 * Uses on-device SpeechRecognizer bursts only (EXTRA_PREFER_OFFLINE). Ambient audio is
 * not continuously uploaded to cloud ASR. If on-device recognition is unavailable,
 * the engine reports an error so the FGS notification can surface the limitation.
 */
internal class OnDeviceRecognitionWakeEngine(
    private val context: Context,
) : WakeWordEngine {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var phrase: String = WakePhraseRules.DEFAULT
    private var sensitivity: WakeSensitivity = WakeSensitivity.Medium
    private var listener: WakeWordEngine.Listener? = null
    private var recognizer: SpeechRecognizer? = null
    private var cooldownUntilMs: Long = 0L

    override fun engineName(): String = "on-device-recognition"

    override fun isRunning(): Boolean = running.get() && !paused.get()

    override fun start(
        phrase: String,
        sensitivity: WakeSensitivity,
        listener: WakeWordEngine.Listener,
    ) {
        stop()
        this.phrase = WakePhraseRules.normalizeOrDefault(phrase)
        this.sensitivity = sensitivity
        this.listener = listener
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context) &&
            SystemSpeechRecognizer.create(context) == null
        ) {
            listener.onError("设备不支持本地唤醒识别，请稍后集成 Sherpa 模型")
            return
        }
        running.set(true)
        paused.set(false)
        scheduleListen(delayMs = 0L)
    }

    override fun updateKeywords(phrase: String, sensitivity: WakeSensitivity) {
        this.phrase = WakePhraseRules.normalizeOrDefault(phrase)
        this.sensitivity = sensitivity
    }

    override fun pause() {
        paused.set(true)
        recognizer?.cancel()
    }

    override fun resume() {
        if (!running.get()) return
        paused.set(false)
        scheduleListen(delayMs = 250L)
    }

    override fun stop() {
        running.set(false)
        paused.set(false)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        listener = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun scheduleListen(delayMs: Long) {
        mainHandler.postDelayed({
            if (!running.get() || paused.get()) return@postDelayed
            startListeningBurst()
        }, delayMs)
    }

    private fun startListeningBurst() {
        if (!running.get() || paused.get()) return
        if (System.currentTimeMillis() < cooldownUntilMs) {
            scheduleListen(cooldownUntilMs - System.currentTimeMillis())
            return
        }
        val created = when {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ->
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else -> SystemSpeechRecognizer.create(context)
        } ?: run {
            listener?.onError("无法创建本地识别器")
            return
        }
        recognizer?.destroy()
        recognizer = created
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                maybeDetect(firstResult(partialResults))
            }

            override fun onResults(results: Bundle?) {
                maybeDetect(firstResult(results))
                scheduleListen(restartDelayMs())
            }

            override fun onError(error: Int) {
                // Common no-speech / client errors — keep listening quietly.
                scheduleListen(restartDelayMs())
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs())
        }
        runCatching { created.startListening(intent) }
            .onFailure {
                AndroidAgentLogger.warn("Wake listen burst failed")
                scheduleListen(restartDelayMs())
            }
    }

    private fun maybeDetect(text: String) {
        if (!running.get() || paused.get() || text.isBlank()) return
        if (!WakePhraseMatcher.matches(text, phrase)) return
        cooldownUntilMs = System.currentTimeMillis() + cooldownMs()
        paused.set(true)
        recognizer?.cancel()
        listener?.onDetected(phrase)
    }

    private fun firstResult(bundle: Bundle?): String {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        return list?.firstOrNull().orEmpty().trim()
    }

    private fun silenceMs(): Long = when (sensitivity) {
        WakeSensitivity.Low -> 1200L
        WakeSensitivity.Medium -> 900L
        WakeSensitivity.High -> 650L
    }

    private fun restartDelayMs(): Long = when (sensitivity) {
        WakeSensitivity.Low -> 700L
        WakeSensitivity.Medium -> 450L
        WakeSensitivity.High -> 280L
    }

    private fun cooldownMs(): Long = when (sensitivity) {
        WakeSensitivity.Low -> 4_000L
        WakeSensitivity.Medium -> 2_500L
        WakeSensitivity.High -> 1_500L
    }
}
