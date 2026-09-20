package io.github.mangi.eta.agent.voice.wake

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.WakePhraseRules
import io.github.mangi.eta.data.model.WakeSensitivity
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sherpa-ONNX KWS adapter.
 *
 * Loads `com.k2fsa.sherpa.onnx.KeywordSpotter` via reflection when the AAR and
 * model files under `filesDir/sherpa-kws/` are present. Otherwise [isAvailable]
 * is false and [WakeEngineFactory] falls back to on-device recognition.
 *
 * Keyword format example: `x iǎo w áng t óng x ué @小王同学`
 */
internal class SherpaWakeEngine(
    private val context: Context,
) : WakeWordEngine {
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var phrase: String = WakePhraseRules.DEFAULT
    private var sensitivity: WakeSensitivity = WakeSensitivity.Medium
    private var listener: WakeWordEngine.Listener? = null

    override fun engineName(): String = "sherpa-onnx"

    override fun isRunning(): Boolean = running.get() && !paused.get()

    override fun start(
        phrase: String,
        sensitivity: WakeSensitivity,
        listener: WakeWordEngine.Listener,
    ) {
        this.phrase = WakePhraseRules.normalizeOrDefault(phrase)
        this.sensitivity = sensitivity
        this.listener = listener
        if (!isAvailable(context)) {
            listener.onError("Sherpa 模型未就绪")
            return
        }
        // Full AudioRecord + KeywordSpotter loop requires native AAR on classpath.
        // Keep a clear hook so integrating the AAR later is a drop-in.
        AndroidAgentLogger.info("Sherpa wake requested; native loop not linked in this build")
        listener.onError("Sherpa native 未链接，请使用本地识别回退")
    }

    override fun updateKeywords(phrase: String, sensitivity: WakeSensitivity) {
        this.phrase = WakePhraseRules.normalizeOrDefault(phrase)
        this.sensitivity = sensitivity
    }

    override fun pause() {
        paused.set(true)
    }

    override fun resume() {
        paused.set(false)
    }

    override fun stop() {
        running.set(false)
        paused.set(false)
        listener = null
    }

    companion object {
        fun isAvailable(context: Context): Boolean {
            val modelDir = File(context.filesDir, "sherpa-kws")
            val hasModel = modelDir.isDirectory && modelDir.list()?.isNotEmpty() == true
            if (!hasModel) return false
            return runCatching {
                Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotter")
                true
            }.getOrDefault(false)
        }

        /** Converts Chinese phrase to a Sherpa-style keyword line (pinyin left for user/model). */
        fun keywordLine(phrase: String): String {
            val normalized = WakePhraseRules.normalizeOrDefault(phrase)
            return "@$normalized"
        }
    }
}

internal object WakeEngineFactory {
    fun create(context: Context): WakeWordEngine {
        if (SherpaWakeEngine.isAvailable(context)) {
            return SherpaWakeEngine(context)
        }
        return OnDeviceRecognitionWakeEngine(context)
    }
}
