package io.github.fartown.movo.agent.voice.asr

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.model.AgentHttpClient
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.core.safeLogType
import io.github.fartown.movo.data.model.DoubaoSpeechCredentials
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Doubao bidirectional streaming ASR over OkHttp WebSocket (SAUC binary protocol).
 * Default endpoint: [DoubaoSaucProtocol.DEFAULT_ENDPOINT] (`bigmodel_async`).
 */
internal class DoubaoBidirectionalAsrEngine(
    private val context: Context,
    private val credentials: DoubaoSpeechCredentials,
    private val httpClient: okhttp3.OkHttpClient = AgentHttpClient.client.newBuilder()
        .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
        .build(),
) : MovoAsrEngine {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var audioStream: SaucAudioStream? = null
    private var stopping = false
    private var listener: MovoAsrEngine.Listener? = null
    private var webSocket: WebSocket? = null
    private var pcmCapture: AsrPcmCapture? = null
    private var latestText: String = ""
    private var definiteText: String = ""
    private val finalTimeout = Runnable { failAndStop("等待豆包语音结果超时，请重试") }

    override fun isRunning(): Boolean = running.get()

    override fun start(listener: MovoAsrEngine.Listener) {
        if (!running.compareAndSet(false, true)) return
        this.listener = listener
        latestText = ""
        definiteText = ""
        stopping = false
        val normalized = credentials.normalized()
        if (!normalized.hasUsableAuth()) {
            failAndStop(context.getString(R.string.voice_doubao_credentials_required))
            return
        }
        val connectId = UUID.randomUUID().toString()
        val requestBuilder = Request.Builder()
            .url(normalized.endpoint)
            .header("X-Api-Resource-Id", normalized.resourceId)
            .header("X-Api-Connect-Id", connectId)
            .header("X-Api-Request-Id", connectId)
            .header("X-Api-Sequence", "-1")
        when (normalized.authMode()) {
            DoubaoSpeechCredentials.AuthMode.ApiKey -> {
                requestBuilder.header("X-Api-Key", normalized.apiKey)
            }
            DoubaoSpeechCredentials.AuthMode.AppAccessKey -> {
                requestBuilder.header("X-Api-App-Key", normalized.appKey)
                requestBuilder.header("X-Api-Access-Key", normalized.accessKey)
            }
            DoubaoSpeechCredentials.AuthMode.Missing -> {
                failAndStop(context.getString(R.string.voice_doubao_credentials_required))
                return
            }
        }
        // Never log credential values.
        AndroidAgentLogger.info(
            "Doubao ASR connecting: auth=${normalized.authMode().name} resource=${normalized.resourceId}",
        )
        webSocket = httpClient.newWebSocket(
            requestBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val logId = response.header("X-Tt-Logid")
                    if (!logId.isNullOrBlank()) {
                        AndroidAgentLogger.info("Doubao ASR connected logid=${logId.take(24)}")
                    }
                    mainHandler.post {
                        if (!running.get() || stopping) {
                            webSocket.cancel()
                            return@post
                        }
                        val stream = SaucAudioStream { webSocket.send(it.toByteString()) }
                        audioStream = stream
                        if (stream.start()) startPcm(stream) else failAndStop("音频发送失败")
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    mainHandler.post {
                        if (running.get()) handleServerBytes(bytes.toByteArray())
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    AndroidAgentLogger.warn("Doubao ASR failure: type=${t.safeLogType()}")
                    mainHandler.post { failAndStop("豆包语音连接失败") }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    mainHandler.post {
                        if (running.get()) failAndStop("豆包语音连接已关闭，请重试")
                    }
                }
            },
        )
    }

    override fun stop(submitFinal: Boolean) {
        if (!running.get() || stopping) return
        stopping = true
        pcmCapture?.stop()
        pcmCapture = null
        if (submitFinal && audioStream != null) {
            if (audioStream?.finish() == true) {
                // Wait for the server's final result instead of cutting it off after 600 ms.
                mainHandler.postDelayed(finalTimeout, 5_000L)
            } else {
                failAndStop("音频发送失败")
            }
        } else {
            finishSession(submitFinal = false)
        }
    }

    override fun cancel() {
        running.set(false)
        stopping = true
        mainHandler.removeCallbacksAndMessages(null)
        audioStream?.cancel()
        audioStream = null
        pcmCapture?.stop()
        pcmCapture = null
        webSocket?.cancel()
        webSocket = null
        listener = null
    }

    private fun startPcm(stream: SaucAudioStream) {
        val capture = AsrPcmCapture(
            context = context,
            onPacket = { packet ->
                if (!running.get()) return@AsrPcmCapture
                val ok = stream.audio(packet)
                if (!ok) {
                    mainHandler.post { failAndStop("音频发送失败") }
                }
            },
            onError = { message -> mainHandler.post { failAndStop(message) } },
            onLevel = { level ->
                mainHandler.post { if (running.get() && !stopping) listener?.onLevel(level) }
            },
        )
        pcmCapture = capture
        capture.start()
    }

    private fun handleServerBytes(bytes: ByteArray) {
        val frame = runCatching { DoubaoSaucProtocol.parseServerFrame(bytes) }
            .getOrElse { error ->
                AndroidAgentLogger.warn("Doubao ASR frame parse failed: type=${error.safeLogType()}")
                return
            }
        when (frame) {
            is SaucServerFrame.Error -> {
                failAndStop("豆包语音错误 ${frame.code}")
            }
            is SaucServerFrame.Response -> {
                val text = frame.result.text.trim()
                val definite = frame.result.utterances
                    .filter { it.definite }
                    .joinToString("") { it.text }
                    .trim()
                if (text.isNotEmpty()) {
                    latestText = text
                    listener?.onPartial(text, text.commonPrefixWith(definite).length)
                }
                if (definite.isNotEmpty()) {
                    definiteText = definite
                }
                if (frame.isLast) {
                    finishSession(submitFinal = true)
                }
            }
            is SaucServerFrame.Unknown -> Unit
        }
    }

    private fun failAndStop(message: String) {
        if (!running.getAndSet(false)) return
        mainHandler.removeCallbacksAndMessages(null)
        audioStream?.cancel()
        audioStream = null
        pcmCapture?.stop()
        pcmCapture = null
        webSocket?.cancel()
        webSocket = null
        val current = listener
        listener = null
        mainHandler.post {
            current?.onError(message)
            current?.onEnded()
        }
    }

    private fun finishSession(submitFinal: Boolean = true) {
        if (!running.getAndSet(false) && listener == null) return
        mainHandler.removeCallbacksAndMessages(null)
        audioStream?.cancel()
        audioStream = null
        pcmCapture?.stop()
        pcmCapture = null
        webSocket?.close(1000, "done")
        webSocket = null
        val current = listener
        listener = null
        val finalText = when {
            latestText.isNotBlank() -> latestText
            definiteText.isNotBlank() -> definiteText
            else -> ""
        }
        mainHandler.post {
            if (submitFinal && finalText.isNotBlank()) {
                current?.onFinal(finalText)
            }
            current?.onEnded()
        }
    }
}
