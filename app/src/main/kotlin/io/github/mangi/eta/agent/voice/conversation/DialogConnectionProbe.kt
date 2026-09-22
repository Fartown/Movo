package io.github.mangi.eta.agent.voice.conversation

import android.content.Context
import io.github.mangi.eta.agent.voice.asr.DoubaoConnectionProbe.Result
import io.github.mangi.eta.data.model.DoubaoSpeechCredentials
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicBoolean

/** Exercise the same Dialog authentication and session startup as conversation, without a microphone. */
internal object DialogConnectionProbe {
    suspend fun test(context: Context, credentials: DoubaoSpeechCredentials): Result =
        withTimeoutOrNull(14_000) {
            suspendCancellableCoroutine { continuation ->
                val done = AtomicBoolean(false)
                lateinit var engine: DoubaoDialogEngine
                fun finish(result: Result) {
                    if (!done.compareAndSet(false, true)) return
                    engine.close { if (continuation.isActive) continuation.resume(result) }
                }
                engine = DoubaoDialogEngine(context, object : DoubaoDialogEngine.Listener {
                    override fun onReady() = finish(Result.Success)
                    override fun onError(message: String) = finish(Result.InvalidCredentials(message))
                    override fun onSpeechStarted(turn: Long) = Unit
                    override fun onPartial(turn: Long, text: String) = Unit
                    override fun onSpeechEnded(turn: Long) = Unit
                    override fun onInputActivity(atElapsedMs: Long) = Unit
                    override fun onPlaybackStarted(turn: Long) = Unit
                    override fun onPlaybackFinished(turn: Long) = Unit
                }, sourceFactory = { object : DoubaoDialogEngine.PcmInput {
                    override fun start(feed: (ByteArray) -> Unit) = Unit
                    override fun close() = Unit
                } })
                continuation.invokeOnCancellation { done.set(true); engine.close() }
                engine.start(credentials)
            }
        } ?: Result.Timeout
}
