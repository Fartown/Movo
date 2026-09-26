package io.github.fartown.movo.agent.voice.asr

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Volcengine Doubao SAUC bidirectional streaming ASR binary frame codec.
 *
 * Spec: https://docs.volcengine.com/docs/DoubaoVoice/bidirectional-streaming-automatic-speech-recognition-websocket
 */
internal object DoubaoSaucProtocol {
    const val DEFAULT_ENDPOINT =
        "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    const val DEFAULT_RESOURCE_ID = "volc.seedasr.sauc.duration"
    const val SAMPLE_RATE = 16_000
    const val BITS = 16
    const val CHANNELS = 1
    /** ~200 ms of 16-bit mono PCM at 16 kHz. */
    const val PACKET_BYTES = SAMPLE_RATE * (BITS / 8) * CHANNELS / 5

    private const val PROTOCOL_VERSION = 0b0001
    private const val HEADER_SIZE_UNITS = 0b0001

    const val MSG_FULL_CLIENT_REQUEST = 0b0001
    const val MSG_AUDIO_ONLY_REQUEST = 0b0010
    const val MSG_FULL_SERVER_RESPONSE = 0b1001
    const val MSG_SERVER_ERROR = 0b1111

    const val FLAG_NO_SEQUENCE = 0b0000
    const val FLAG_POS_SEQUENCE = 0b0001
    const val FLAG_NEG_SEQUENCE = 0b0010
    const val FLAG_NEG_WITH_SEQUENCE = 0b0011

    const val SERIAL_NONE = 0b0000
    const val SERIAL_JSON = 0b0001
    const val COMPRESS_NONE = 0b0000
    const val COMPRESS_GZIP = 0b0001

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size.coerceAtLeast(64))
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    fun gunzip(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        return GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }

    fun buildHeader(
        messageType: Int,
        flags: Int,
        serialization: Int,
        compression: Int,
    ): ByteArray =
        byteArrayOf(
            ((PROTOCOL_VERSION shl 4) or HEADER_SIZE_UNITS).toByte(),
            ((messageType shl 4) or (flags and 0x0f)).toByte(),
            ((serialization shl 4) or (compression and 0x0f)).toByte(),
            0,
        )

    fun encodeFullClientRequest(
        jsonPayload: String,
        sequence: Int = 1,
        gzipCompress: Boolean = true,
    ): ByteArray {
        val payload = jsonPayload.toByteArray(Charsets.UTF_8)
        val body = if (gzipCompress) gzip(payload) else payload
        val compression = if (gzipCompress) COMPRESS_GZIP else COMPRESS_NONE
        return encodeFrame(
            messageType = MSG_FULL_CLIENT_REQUEST,
            flags = FLAG_POS_SEQUENCE,
            serialization = SERIAL_JSON,
            compression = compression,
            sequence = sequence,
            payload = body,
        )
    }

    fun encodeAudioOnlyRequest(
        pcm: ByteArray,
        sequence: Int,
        isLast: Boolean,
        gzipCompress: Boolean = true,
    ): ByteArray {
        val flags = if (isLast) FLAG_NEG_WITH_SEQUENCE else FLAG_POS_SEQUENCE
        val seq = if (isLast) -sequence else sequence
        val body = if (gzipCompress) gzip(pcm) else pcm
        val compression = if (gzipCompress) COMPRESS_GZIP else COMPRESS_NONE
        return encodeFrame(
            messageType = MSG_AUDIO_ONLY_REQUEST,
            flags = flags,
            serialization = SERIAL_NONE,
            compression = compression,
            sequence = seq,
            payload = body,
        )
    }

    fun encodeFrame(
        messageType: Int,
        flags: Int,
        serialization: Int,
        compression: Int,
        sequence: Int?,
        payload: ByteArray,
    ): ByteArray {
        val hasSequence = flags == FLAG_POS_SEQUENCE || flags == FLAG_NEG_WITH_SEQUENCE
        val size = 4 + (if (hasSequence) 4 else 0) + 4 + payload.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(buildHeader(messageType, flags, serialization, compression))
        if (hasSequence) {
            requireNotNull(sequence)
            buffer.putInt(sequence)
        }
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    fun defaultFullClientJson(
        uid: String = "movo-android",
        enableNonstream: Boolean = true,
        endWindowSizeMs: Int = 800,
    ): String =
        """
        {
          "user": { "uid": ${jsonString(uid)}, "platform": "Android" },
          "audio": {
            "format": "pcm",
            "codec": "raw",
            "rate": $SAMPLE_RATE,
            "bits": $BITS,
            "channel": $CHANNELS
          },
          "request": {
            "model_name": "bigmodel",
            "enable_itn": true,
            "enable_punc": true,
            "enable_ddc": false,
            "show_utterances": true,
            "enable_nonstream": $enableNonstream,
            "result_type": "full",
            "end_window_size": $endWindowSizeMs
          }
        }
        """.trimIndent()

    fun parseServerFrame(bytes: ByteArray): SaucServerFrame {
        require(bytes.size >= 4) { "frame too short" }
        val version = (bytes[0].toInt() ushr 4) and 0x0f
        val headerSize = (bytes[0].toInt() and 0x0f) * 4
        require(version == PROTOCOL_VERSION) { "unsupported protocol version=$version" }
        require(bytes.size >= headerSize) { "header truncated" }
        val messageType = (bytes[1].toInt() ushr 4) and 0x0f
        val flags = bytes[1].toInt() and 0x0f
        val serialization = (bytes[2].toInt() ushr 4) and 0x0f
        val compression = bytes[2].toInt() and 0x0f
        var offset = headerSize
        var sequence: Int? = null
        if (flags == FLAG_POS_SEQUENCE || flags == FLAG_NEG_WITH_SEQUENCE) {
            require(bytes.size >= offset + 4) { "missing sequence" }
            sequence = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            offset += 4
        }
        val isLast = flags == FLAG_NEG_SEQUENCE || flags == FLAG_NEG_WITH_SEQUENCE ||
            (sequence != null && sequence < 0)

        return when (messageType) {
            MSG_SERVER_ERROR -> {
                require(bytes.size >= offset + 8) { "error frame truncated" }
                val code = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
                offset += 4
                val msgSize = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
                offset += 4
                require(bytes.size >= offset + msgSize) { "error message truncated" }
                val messageBytes = bytes.copyOfRange(offset, offset + msgSize)
                val message = decodePayload(messageBytes, serialization, compression)
                    ?.toString(Charsets.UTF_8)
                    ?: messageBytes.toString(Charsets.UTF_8)
                SaucServerFrame.Error(code = code, message = message, sequence = sequence, isLast = isLast)
            }
            MSG_FULL_SERVER_RESPONSE -> {
                require(bytes.size >= offset + 4) { "response size truncated" }
                val payloadSize = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
                offset += 4
                require(bytes.size >= offset + payloadSize) { "response payload truncated" }
                val raw = bytes.copyOfRange(offset, offset + payloadSize)
                val decoded = decodePayload(raw, serialization, compression) ?: ByteArray(0)
                val text = decoded.toString(Charsets.UTF_8)
                val result = parseRecognitionJson(text)
                SaucServerFrame.Response(
                    sequence = sequence,
                    isLast = isLast,
                    rawJson = text,
                    result = result,
                )
            }
            else -> SaucServerFrame.Unknown(
                messageType = messageType,
                flags = flags,
                sequence = sequence,
                isLast = isLast,
            )
        }
    }

    fun parseRecognitionJson(text: String): SaucRecognitionResult {
        if (text.isBlank()) return SaucRecognitionResult()
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return SaucRecognitionResult()
        val resultElement = root["result"]
        val transcript = when (resultElement) {
            is JsonObject -> resultElement["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            is JsonArray -> resultElement.firstOrNull()
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
            else -> ""
        }
        val utterances = mutableListOf<SaucUtterance>()
        val utteranceSource: JsonArray? = when (resultElement) {
            is JsonObject -> resultElement["utterances"]?.asJsonArrayOrNull()
            is JsonArray -> resultElement.firstOrNull()?.jsonObject?.get("utterances")?.asJsonArrayOrNull()
            else -> null
        }
        utteranceSource?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            utterances += SaucUtterance(
                text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                definite = obj["definite"]?.jsonPrimitive?.booleanOrNull == true,
                startTimeMs = obj["start_time"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                endTimeMs = obj["end_time"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            )
        }
        return SaucRecognitionResult(
            text = transcript,
            utterances = utterances,
            hasDefinite = utterances.any { it.definite },
        )
    }

    private fun decodePayload(
        payload: ByteArray,
        serialization: Int,
        compression: Int,
    ): ByteArray? {
        if (payload.isEmpty()) return payload
        val inflated = when (compression) {
            COMPRESS_GZIP -> runCatching { gunzip(payload) }.getOrElse { payload }
            else -> payload
        }
        return when (serialization) {
            SERIAL_JSON, SERIAL_NONE -> inflated
            else -> inflated
        }
    }

    private fun JsonElement.asJsonArrayOrNull(): JsonArray? = this as? JsonArray

    private fun jsonString(value: String): String =
        JsonPrimitive(value).toString()
}

internal data class SaucRecognitionResult(
    val text: String = "",
    val utterances: List<SaucUtterance> = emptyList(),
    val hasDefinite: Boolean = false,
)

internal data class SaucUtterance(
    val text: String,
    val definite: Boolean,
    val startTimeMs: Int = 0,
    val endTimeMs: Int = 0,
)

internal sealed interface SaucServerFrame {
    val sequence: Int?
    val isLast: Boolean

    data class Response(
        override val sequence: Int?,
        override val isLast: Boolean,
        val rawJson: String,
        val result: SaucRecognitionResult,
    ) : SaucServerFrame

    data class Error(
        val code: Int,
        val message: String,
        override val sequence: Int?,
        override val isLast: Boolean,
    ) : SaucServerFrame

    data class Unknown(
        val messageType: Int,
        val flags: Int,
        override val sequence: Int?,
        override val isLast: Boolean,
    ) : SaucServerFrame
}
