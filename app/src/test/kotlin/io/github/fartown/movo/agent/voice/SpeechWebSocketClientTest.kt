package io.github.fartown.movo.agent.voice

import com.bytedance.speech.speechengine.net.ws.IWsListener
import com.bytedance.speech.speechengine.net.ws.WsConnectionConfig
import io.github.fartown.movo.agent.voice.conversation.SpeechWebSocketClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SpeechWebSocketClientTest {
    private class Socket : WebSocket {
        var cancelled = false
        var binary: ByteString? = null
        var text: String? = null
        var beforeSend: () -> Unit = {}
        override fun request() = Request.Builder().url("https://example.invalid").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { beforeSend(); this.text = text; return !cancelled }
        override fun send(bytes: ByteString): Boolean { beforeSend(); binary = bytes; return !cancelled }
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }
    private fun config() = WsConnectionConfig().apply { url = "wss://example.invalid"; headers = emptyMap(); connectTimeout = 1000 }

    @Test fun stopWhileNativeThreadSendsDoesNotDereferenceClearedSocket() {
        val socket = Socket()
        val client = SpeechWebSocketClient { WebSocket.Factory { _, _ -> socket } }
        client.startConnection(config())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        socket.beforeSend = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
        val failure = AtomicReference<Throwable>()
        val sending = thread { try { client.send(byteArrayOf(0x11, 0x22)) } catch (t: Throwable) { failure.set(t) } }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        client.stopConnection(); release.countDown(); sending.join(2000)
        assertFalse(sending.isAlive)
        assertNull(failure.get())
        assertFalse(client.send(byteArrayOf(1)))
        assertTrue(socket.cancelled)
    }

    @Test fun cancelledConnectionCallbacksCannotReachNewSession() {
        val callbacks = mutableListOf<WebSocketListener>()
        val sockets = mutableListOf<Socket>()
        val client = SpeechWebSocketClient { WebSocket.Factory { _, listener ->
            callbacks += listener; Socket().also { sockets += it }
        } }
        var messages = 0; var failures = 0
        client.init(object : IWsListener {
            override fun onConnected(message: String) = Unit
            override fun onDisconnected(message: String) = Unit
            override fun onMessage(bytes: ByteArray) { messages++ }
            override fun onError(code: Int, message: String) { failures++ }
        })
        client.startConnection(config()); client.stopConnection(); client.startConnection(config())
        callbacks[0].onMessage(sockets[0], "stale")
        callbacks[0].onFailure(sockets[0], Exception("closed"), null)
        callbacks[1].onMessage(sockets[1], "current")
        assertEquals(1, messages); assertEquals(0, failures)
        client.stopConnection()
    }
}
