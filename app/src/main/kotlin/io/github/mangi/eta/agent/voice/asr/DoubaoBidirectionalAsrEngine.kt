package io.github.mangi.eta.agent.voice.asr

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.DoubaoSpeechCredentials
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
) : EtaAsrEngine {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val sequence = AtomicInteger(1)
    private var listener: EtaAsrEngine.Listener? = null
    private var webSocket: WebSocket? = null
    private var pcmCapture: AsrPcmCapture? = null
    private var latestText: String = ""
    private var definiteText: String = ""
    private var startedAudio = false

    override fun isRunning(): Boolean = running.get()

    override fun start(listener: EtaAsrEngine.Listener) {
        if (!running.compareAndSet(false, true)) return
        this.listener = listener
        latestText = ""
        definiteText = ""
        startedAudio = false
        sequence.set(1)
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
                    val frame = DoubaoSaucProtocol.encodeFullClientRequest(
                        DoubaoSaucProtocol.defaultFullClientJson(),
                        sequence = sequence.getAndIncrement(),
                    )
                    webSocket.send(frame.toByteString())
                    startPcm(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleServerBytes(bytes.toByteArray())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    AndroidAgentLogger.warn("Doubao ASR failure: type=${t.safeLogType()}")
                    failAndStop(
                        message = "豆包语音连接失败",
                    )
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    finishSession()
                }
            },
        )
    }

    override fun stop(submitFinal: Boolean) {
        if (!running.get()) return
        val socket = webSocket
        val seq = sequence.getAndIncrement()
        pcmCapture?.stop()
        pcmCapture = null
        if (socket != null && submitFinal) {
            runCatching {
                socket.send(
                    DoubaoSaucProtocol.encodeAudioOnlyRequest(
                        pcm = ByteArray(0),
                        sequence = seq,
                        isLast = true,
                    ).toByteString(),
                )
            }
            mainHandler.postDelayed({
                socket.close(1000, "done")
                finishSession(submitFinal = true)
            }, 600)
        } else {
            socket?.cancel()
            finishSession(submitFinal = submitFinal)
        }
    }

    override fun cancel() {
        pcmCapture?.stop()
        pcmCapture = null
        webSocket?.cancel()
        webSocket = null
        running.set(false)
        listener = null
    }

    private fun startPcm(socket: WebSocket) {
        startedAudio = true
        val capture = AsrPcmCapture(
            context = context,
            onPacket = { packet ->
                if (!running.get()) return@AsrPcmCapture
                val seq = sequence.getAndIncrement()
                val ok = socket.send(
                    DoubaoSaucProtocol.encodeAudioOnlyRequest(
                        pcm = packet,
                        sequence = seq,
                        isLast = false,
                    ).toByteString(),
                )
                if (!ok) {
                    failAndStop("音频发送失败")
                }
            },
            onError = { message -> failAndStop(message) },
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
                if (text.isNotEmpty()) {
                    latestText = text
                    mainHandler.post { listener?.onPartial(text) }
                }
                val definite = frame.result.utterances
                    .filter { it.definite }
                    .joinToString("") { it.text }
                    .trim()
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
        pcmCapture?.stop()
        pcmCapture = null
        webSocket = null
        val current = listener
        listener = null
        val finalText = when {
            definiteText.isNotBlank() -> definiteText
            latestText.isNotBlank() -> latestText
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
