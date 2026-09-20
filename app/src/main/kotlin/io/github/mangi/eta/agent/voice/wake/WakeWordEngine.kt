package io.github.mangi.eta.agent.voice.wake

import io.github.mangi.eta.data.model.WakePhraseRules
import io.github.mangi.eta.data.model.WakeSensitivity

/** Local wake-word detector. Audio must stay on-device. */
internal interface WakeWordEngine {
    fun start(phrase: String, sensitivity: WakeSensitivity, listener: Listener)
    fun updateKeywords(phrase: String, sensitivity: WakeSensitivity)
    fun pause()
    fun resume()
    fun stop()
    fun isRunning(): Boolean
    fun engineName(): String

    interface Listener {
        fun onDetected(phrase: String)
        fun onError(message: String)
    }
}

internal object WakePhraseMatcher {
    fun matches(transcript: String, phrase: String): Boolean {
        val hay = normalize(transcript)
        val needle = normalize(phrase.ifBlank { WakePhraseRules.DEFAULT })
        if (needle.isEmpty() || hay.isEmpty()) return false
        return hay.contains(needle)
    }

    fun normalize(raw: String): String =
        raw.trim()
            .lowercase()
            .replace(Regex("[\\s\\p{Punct}]"), "")
            .replace("，", "")
            .replace("。", "")
            .replace("！", "")
            .replace("？", "")
            .replace("、", "")
            .replace("；", "")
            .replace("：", "")
            .replace("“", "")
            .replace("”", "")
            .replace("‘", "")
            .replace("’", "")
            .replace("（", "")
            .replace("）", "")
            .replace("【", "")
            .replace("】", "")
            .replace("《", "")
            .replace("》", "")
            .replace("·", "")
            .replace("…", "")
}
