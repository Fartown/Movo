package io.github.fartown.movo.agent.voice.conversation

import com.bytedance.speech.speechengine.net.ws.IWsClient
import com.bytedance.speech.speechengine.net.ws.IWsListener
import com.bytedance.speech.speechengine.net.ws.WsConnectionConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** SDK 0.0.15.0's default transport rereads a nullable socket during send/stop and can crash
 * its native callback thread. A stable socket snapshot and generation-gated callbacks avoid it.
 * This implements the SDK's public transport extension without changing its wire protocol. */
internal class SpeechWebSocketClient(
    private val factory: (Int) -> WebSocket.Factory = { timeout ->
        shared.newBuilder().connectTimeout(timeout.coerceAtLeast(1000).toLong(), TimeUnit.MILLISECONDS).build()
    },
) : IWsClient {
    private val lock = Any()
    @Volatile private var socket: WebSocket? = null
    private var listener: IWsListener? = null
    private var generation = 0L
    override fun init(value: IWsListener): Boolean = synchronized(lock) { listener = value; true }

    override fun startConnection(config: WsConnectionConfig): Boolean = synchronized(lock) {
        stopConnection()
        val current = ++generation
        val request = Request.Builder().url(config.url).apply {
            config.headers?.forEach { (key, value) -> addHeader(key, value) }
        }.build()
        socket = factory(config.connectTimeout).newWebSocket(request, object : WebSocketListener() {
            private fun emit(block: IWsListener.() -> Unit) = synchronized(lock) {
                if (generation == current) listener?.block()
            }
            override fun onOpen(webSocket: WebSocket, response: Response) = emit {
                onConnected(response.message)
                response.header("X-Tt-Logid")?.let(::onLogid)
            }
            override fun onMessage(webSocket: WebSocket, text: String) = emit { onMessage(text.toByteArray(Charsets.UTF_8)) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = emit { onMessage(bytes.toByteArray()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                emit { onDisconnected(reason) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = emit {
                // Do not forward URL, request headers, or exception strings containing credentials.
                onError(response?.code ?: 4003, response?.message ?: "Speech WebSocket failed")
                response?.header("X-Tt-Logid")?.let(::onLogid)
            }
        })
        true
    }

    override fun send(bytes: ByteArray): Boolean {
        val current = socket ?: return false
        val text = bytes.toString(Charsets.UTF_8).trim()
        val json = text.startsWith('{') && text.endsWith('}') && runCatching { JSONObject(text) }.isSuccess
        return if (json) current.send(text) else current.send(bytes.toByteString())
    }

    override fun stopConnection(): Boolean = synchronized(lock) {
        ++generation
        val previous = socket
        socket = null
        previous?.cancel()
        true
    }

    private companion object { val shared = OkHttpClient() }
}
