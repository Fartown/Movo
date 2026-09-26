package io.github.fartown.movo.agent.model

/**
 * 失败原因给人看的说法：网络 / TLS 这类传输层错误（真机：`Read error: ssl=0x…: Failure in SSL library`）
 * 不直接展示原始报错，统一成一句可操作的说明；完整原文仍保留在运行日志里（「查看日志」）。
 * 其他错误（模型拒绝、配置缺失等）原样返回。
 */
internal object UserFacingFailure {
    private val transportPatterns = listOf(
        Regex("ssl=0x", RegexOption.IGNORE_CASE),
        Regex("\\bSSL\\b"),
        Regex("Read error", RegexOption.IGNORE_CASE),
        Regex("Write error", RegexOption.IGNORE_CASE),
        Regex("Connection reset", RegexOption.IGNORE_CASE),
        Regex("connection abort", RegexOption.IGNORE_CASE),
        Regex("unexpected end of stream", RegexOption.IGNORE_CASE),
        Regex("stream was reset", RegexOption.IGNORE_CASE),
        Regex("\\bprotocol error\\b", RegexOption.IGNORE_CASE),
        Regex("\\bGOAWAY\\b"),
        Regex("timed? ?out", RegexOption.IGNORE_CASE),
        Regex("Unable to resolve host", RegexOption.IGNORE_CASE),
        Regex("Failed to connect", RegexOption.IGNORE_CASE),
        Regex("\\bEOF\\b"),
    )

    fun isTransportError(raw: String?): Boolean =
        !raw.isNullOrBlank() && transportPatterns.any { it.containsMatchIn(raw) }

    /** [networkMessage] 由调用方按当前语言传入（`R.string.movo_failure_network`）。 */
    fun message(raw: String?, networkMessage: String): String? = when {
        raw.isNullOrBlank() -> raw
        isTransportError(raw) -> networkMessage
        else -> raw
    }
}
