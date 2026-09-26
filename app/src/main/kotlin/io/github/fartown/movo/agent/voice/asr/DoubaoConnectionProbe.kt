package io.github.fartown.movo.agent.voice.asr

import io.github.fartown.movo.agent.model.AgentHttpClient
import io.github.fartown.movo.data.model.DoubaoCredentialRules
import io.github.fartown.movo.data.model.DoubaoSpeechCredentials
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Checks the saved configuration with 200 ms of synthetic silence; never opens the microphone. */
internal object DoubaoConnectionProbe {
    sealed interface Result {
        data object Success : Result
        data object Timeout : Result
        data object NetworkError : Result
        data object InvalidResponse : Result
        data class Rejected(val code: Int) : Result
        data class InvalidCredentials(val message: String) : Result
    }

    fun classify(bytes: ByteArray): Result? = runCatching {
        when (val frame = DoubaoSaucProtocol.parseServerFrame(bytes)) {
            is SaucServerFrame.Error -> Result.Rejected(frame.code)
            is SaucServerFrame.Response -> if (frame.isLast) Result.Success else null
            is SaucServerFrame.Unknown -> Result.InvalidResponse
        }
    }.getOrDefault(Result.InvalidResponse)

    suspend fun test(
        credentials: DoubaoSpeechCredentials,
        client: OkHttpClient = AgentHttpClient.client,
        timeoutMs: Long = 8_000L,
    ): Result {
        val normalized = credentials.normalized()
        when (val validation = DoubaoCredentialRules.validate(normalized)) {
            is DoubaoCredentialRules.Validation.Invalid -> return Result.InvalidCredentials(validation.message)
            DoubaoCredentialRules.Validation.Ok -> Unit
        }
        val request = runCatching {
            val id = UUID.randomUUID().toString()
            Request.Builder().url(normalized.endpoint)
                .header("X-Api-Resource-Id", normalized.resourceId)
                .header("X-Api-Connect-Id", id)
                .header("X-Api-Request-Id", id)
                .header("X-Api-Sequence", "-1")
                .apply {
                    when (normalized.authMode()) {
                        DoubaoSpeechCredentials.AuthMode.ApiKey -> header("X-Api-Key", normalized.apiKey)
                        DoubaoSpeechCredentials.AuthMode.AppAccessKey -> {
                            header("X-Api-App-Key", normalized.appKey)
                            header("X-Api-Access-Key", normalized.accessKey)
                        }
                        DoubaoSpeechCredentials.AuthMode.Missing -> Unit
                    }
                }.build()
        }.getOrElse { return Result.InvalidResponse }
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val completed = AtomicBoolean(false)
                fun finish(socket: WebSocket, result: Result) {
                    if (!completed.compareAndSet(false, true)) return
                    if (continuation.isActive) continuation.resume(result)
                    socket.cancel()
                }
                val socket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (!continuation.isActive) {
                            webSocket.cancel()
                            return
                        }
                        val stream = SaucAudioStream { webSocket.send(it.toByteString()) }
                        if (!stream.start() || !stream.audio(ByteArray(DoubaoSaucProtocol.PACKET_BYTES)) || !stream.finish()) {
                            finish(webSocket, Result.NetworkError)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        classify(bytes.toByteArray())?.let { finish(webSocket, it) }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        finish(webSocket, Result.InvalidResponse)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        finish(webSocket, response?.let { Result.Rejected(it.code) } ?: Result.NetworkError)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        finish(webSocket, Result.InvalidResponse)
                    }
                })
                continuation.invokeOnCancellation { completed.set(true); socket.cancel() }
            }
        } ?: Result.Timeout
    }
}
