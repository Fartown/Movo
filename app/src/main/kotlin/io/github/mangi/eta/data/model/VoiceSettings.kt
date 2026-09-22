package io.github.mangi.eta.data.model

/**
 * Doubao speech BYOK credentials — independent of LLM provider API keys.
 */
internal data class DoubaoSpeechCredentials(
    val appKey: String = "",
    val accessKey: String = "",
    val apiKey: String = "",
    val resourceId: String = DEFAULT_RESOURCE_ID,
    val endpoint: String = DEFAULT_ENDPOINT,
) {
    fun normalized(): DoubaoSpeechCredentials =
        copy(
            appKey = appKey.trim(),
            accessKey = accessKey.trim(),
            apiKey = apiKey.trim(),
            resourceId = resourceId.trim().ifBlank { DEFAULT_RESOURCE_ID },
            endpoint = endpoint.trim().ifBlank { DEFAULT_ENDPOINT },
        )

    fun hasUsableAuth(): Boolean {
        val n = normalized()
        if (n.apiKey.isNotBlank()) return true
        return n.appKey.isNotBlank() && n.accessKey.isNotBlank()
    }

    fun authMode(): AuthMode {
        val n = normalized()
        return when {
            n.apiKey.isNotBlank() -> AuthMode.ApiKey
            n.appKey.isNotBlank() && n.accessKey.isNotBlank() -> AuthMode.AppAccessKey
            else -> AuthMode.Missing
        }
    }

    enum class AuthMode { Missing, ApiKey, AppAccessKey }

    companion object {
        const val DEFAULT_RESOURCE_ID = "volc.seedasr.sauc.duration"
        const val DEFAULT_ENDPOINT =
            "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    }
}

internal data class VoiceWakeSettings(
    val wakeEnabled: Boolean = false,
    val wakePhrase: String = DEFAULT_WAKE_PHRASE,
    val sensitivity: WakeSensitivity = WakeSensitivity.Medium,
) {
    fun effectivePhrase(): String = WakePhraseRules.normalizeOrDefault(wakePhrase)

    companion object {
        const val DEFAULT_WAKE_PHRASE = "小王同学"
    }
}

internal enum class WakeSensitivity {
    Low,
    Medium,
    High,
}

internal object WakePhraseRules {
    const val DEFAULT = VoiceWakeSettings.DEFAULT_WAKE_PHRASE
    const val MIN_CODE_POINTS = 2
    const val SHORT_WARN_CODE_POINTS = 2

    fun normalizeOrDefault(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (!isValid(trimmed)) return DEFAULT
        return trimmed
    }

    fun isValid(phrase: String): Boolean {
        val normalized = phrase.trim()
        if (normalized.isEmpty()) return false
        if (normalized.codePointCount(0, normalized.length) < MIN_CODE_POINTS) return false
        return normalized.any { !it.isWhitespace() }
    }

    fun isShortPhraseWarning(phrase: String): Boolean {
        val normalized = phrase.trim()
        if (!isValid(normalized)) return false
        return normalized.codePointCount(0, normalized.length) <= SHORT_WARN_CODE_POINTS
    }
}

internal object DoubaoCredentialRules {
    fun validate(credentials: DoubaoSpeechCredentials): Validation {
        val n = credentials.normalized()
        if (listOf(n.apiKey, n.appKey, n.accessKey, n.resourceId).any { value ->
                value.any { it.code < 0x20 || it.code > 0x7e }
            }) {
            return Validation.Invalid("凭证和资源 ID 不能包含换行、中文或不可见字符")
        }
        if (!n.hasUsableAuth()) {
            return Validation.Invalid("缺少豆包语音凭证（Api-Key 或 App-Key+Access-Key）")
        }
        if (n.resourceId.isBlank()) {
            return Validation.Invalid("Resource-Id 不能为空")
        }
        if (!n.endpoint.startsWith("wss://") && !n.endpoint.startsWith("ws://")) {
            return Validation.Invalid("端点须为 WebSocket URL")
        }
        return Validation.Ok
    }

    sealed interface Validation {
        data object Ok : Validation
        data class Invalid(val message: String) : Validation
    }
}
