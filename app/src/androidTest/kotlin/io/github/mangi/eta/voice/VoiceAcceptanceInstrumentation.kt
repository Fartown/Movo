package io.github.mangi.eta.voice

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import io.github.mangi.eta.agent.voice.conversation.VoiceInstrumentationAccess
import io.github.mangi.eta.agent.voice.conversation.DoubaoDialogEngine
import io.github.mangi.eta.agent.voice.conversation.VoiceConversationController
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Real SDK -> product controller -> Runtime -> selected model -> real SDK player.
 * Only the microphone source is replaced with paced PCM; end-of-file is continued silence.
 */
class VoiceAcceptanceInstrumentation : Instrumentation() {
    private data class Event(val at: Long, val name: String, val turn: Long, val text: String, val status: String)
    private val events = CopyOnWriteArrayList<Event>()
    private val source = PacedPcm()
    private lateinit var directory: File
    private var options = Bundle()
    private var startAt = 0L
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); options = arguments ?: Bundle(); start() }
    override fun onStart() {
        directory = File(targetContext.getExternalFilesDir(null), "voice-acceptance/${System.currentTimeMillis()}").apply { mkdirs() }
        startAt = SystemClock.elapsedRealtime()
        var oldConversation: String? = null
        var result = "FAIL"
        var failure = ""
        val player = File(directory, "player-24000-mono-s16le.pcm").outputStream()
        val playerChunks = JSONArray()
        var playerBytes = 0L
        val decoder = File(directory, "decoder-24000-mono-s16le.pcm").outputStream()
        val decoderChunks = JSONArray()
        var decoderBytes = 0L
        try {
            VoiceConversationController.observer = { name, turn, text, status ->
                events += Event(SystemClock.elapsedRealtime() - startAt, name, turn, text, status)
                save()
            }
            DoubaoDialogEngine.playerObserver = { bytes -> synchronized(player) {
                playerChunks.put(JSONObject().put("elapsed_ms", SystemClock.elapsedRealtime() - startAt)
                    .put("offset_bytes", playerBytes).put("byte_count", bytes.size))
                player.write(bytes)
                playerBytes += bytes.size
            } }
            DoubaoDialogEngine.decoderObserver = { turn, owned, bytes -> synchronized(decoder) {
                decoderChunks.put(JSONObject().put("elapsed_ms", SystemClock.elapsedRealtime() - startAt)
                    .put("turn", turn).put("owned", owned).put("offset_bytes", decoderBytes)
                    .put("byte_count", bytes.size))
                decoder.write(bytes)
                decoderBytes += bytes.size
            } }
            if (options.getString("mode") != "physical") DoubaoDialogEngine.pcmInputFactory = { source }
            startActivitySync(Intent(targetContext, io.github.mangi.eta.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(2000)
            runOnMainSync {
                oldConversation = VoiceInstrumentationAccess.begin(targetContext)
            }
            waitFor("ready", 25_000) { events.any { it.name == "listening" } }
            if (options.getString("mode") == "physical") {
                SystemClock.sleep(7000)
                check(events.none { it.name == "dispatch" }) { "Unexpected task during microphone silence" }
            } else if (options.getString("mode") == "cancel") {
                check(VoiceInstrumentationAccess.terminalToolsEnabled()) { "Terminal tools are disabled; test cannot execute the requested delay" }
                val from = events.size
                feed("cancel_long_task")
                val task = awaitEvent("dispatch", from)
                awaitEvent("tool.started", from, task.turn)
                val cancelFrom = events.size
                feed("cancel_task")
                awaitEvent("cancel.request", cancelFrom)
                val terminal = awaitEvent("runtime.terminal", cancelFrom, task.turn)
                check(terminal.text.contains("停止") || terminal.text.contains("stopped")) {
                    "Cancellation did not return a stopped terminal result: ${terminal.text}"
                }
                check(events.count { it.name == "dispatch" } == 1) { "Cancel command was incorrectly submitted as another task" }
                check(events.drop(cancelFrom).none { it.name == "speech.request" }) { "Runtime cancellation status must not be spoken" }
                val endFrom = events.size
                feed("end_session")
                awaitEvent("ended", endFrom)
            } else {
                val first = round("r1_remember")
                // Memory is proven by the subsequent recall, not one particular acknowledgement.
                // "我记着了" is valid speech and must not prevent the actual context test from running.
                check(first.text.isNotBlank()) { "First model answer is empty" }
                val second = round("r2_pause_correction")
                check(second.text.contains("蓝鲸") && (second.text.contains("47") || second.text.contains("四十七"))) {
                    "Conversation context lost: ${second.text}"
                }
                val begin = events.size
                feed("r3_long_reply")
                val third = awaitEvent("dispatch", begin)
                awaitEvent("speaking", begin, third.turn)
                SystemClock.sleep(550)
                val interruptAt = events.size
                feed("barge_in_replace")
                val replacement = awaitEvent("dispatch", interruptAt)
                check(replacement.turn > third.turn)
                val replacementAnswer = awaitEvent("answer", interruptAt, replacement.turn)
                check(replacementAnswer.text.contains("蓝鲸") &&
                    (replacementAnswer.text.contains("47") || replacementAnswer.text.contains("四十七"))) {
                    "Barge-in lost the complete codeword: ${replacementAnswer.text}"
                }
                awaitEvent("speaking", interruptAt, replacement.turn)
                awaitEvent("listening", interruptAt, replacement.turn)
                check(events.drop(interruptAt).none { it.name == "speaking" && it.turn == third.turn }) {
                    "Stale answer resumed after confirmed barge-in"
                }
                val beforeEnd = events.count { it.name == "dispatch" }
                val endIndex = events.size
                feed("end_session")
                awaitEvent("ended", endIndex)
                // Even an external input producer racing with close cannot submit a new task.
                SystemClock.sleep(2000)
                source.feedAfterClose(context.assets.open("voice/after_end_negative.pcm").use { it.readBytes() })
                SystemClock.sleep(5000)
                check(events.count { it.name == "dispatch" } == beforeEnd) { "Task dispatched after voice end" }
                check(beforeEnd == 4) { "Expected four tasks, received $beforeEnd" }
            }
            events.filter { it.name == "speech.request" }.forEach { spoken ->
                check(events.any { it.name == "answer" && it.turn == spoken.turn && it.text == spoken.text }) {
                    "Speech differs from the final LLM body for turn ${spoken.turn}"
                }
            }
            result = "PASS"
        } catch (t: Throwable) {
            failure = "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runOnMainSync { VoiceInstrumentationAccess.end(targetContext) }
            SystemClock.sleep(1000)
            source.close()
            DoubaoDialogEngine.pcmInputFactory = null
            DoubaoDialogEngine.playerObserver = null
            DoubaoDialogEngine.decoderObserver = null
            VoiceConversationController.observer = null
            synchronized(player) {
                player.close()
                File(directory, "player-chunks.json").writeText(playerChunks.toString(2))
            }
            synchronized(decoder) {
                decoder.close()
                File(directory, "decoder-chunks.json").writeText(decoderChunks.toString(2))
            }
            runOnMainSync {
                VoiceInstrumentationAccess.restore(targetContext, oldConversation)
            }
            if (oldConversation != null) {
                val persistDeadline = SystemClock.elapsedRealtime() + 5000
                while (SystemClock.elapsedRealtime() < persistDeadline &&
                    !VoiceInstrumentationAccess.selectionSaved(targetContext, oldConversation!!)) {
                    SystemClock.sleep(100)
                }
            }
            save()
            File(directory, "voice-diagnostics.json").writeText(VoiceInstrumentationAccess.diagnostics())
            File(directory, "result.json").writeText(JSONObject()
                .put("result", result).put("failure", failure)
                .put("input", if (options.getString("mode") == "physical") "SDK physical microphone" else "Paced synthetic PCM; no end directive")
                .put("physical_aec_double_talk", "UNVERIFIED")
                .put("elapsed_ms", SystemClock.elapsedRealtime() - startAt)
                .put("dispatches", events.count { it.name == "dispatch" }).toString(2))
            finish(if (result == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("result", result); putString("failure", failure); putString("evidence", directory.absolutePath) })
        }
    }
    private fun round(clip: String): Event {
        val from = events.size
        feed(clip)
        val dispatched = awaitEvent("dispatch", from)
        val answer = awaitEvent("answer", from, dispatched.turn)
        awaitEvent("speaking", from, dispatched.turn)
        awaitEvent("listening", from, dispatched.turn)
        check(events.drop(from).count { it.name == "dispatch" } == 1) { "Pause split $clip into multiple submissions" }
        return answer
    }
    private fun feed(name: String) {
        events += Event(SystemClock.elapsedRealtime() - startAt, "fixture.$name", 0, "", "")
        source.enqueue(context.assets.open("voice/$name.pcm").use { it.readBytes() })
    }
    private fun awaitEvent(name: String, from: Int, turn: Long? = null): Event {
        waitFor(name, 150_000) { events.drop(from).any { it.name == name && (turn == null || it.turn == turn) } }
        return events.drop(from).first { it.name == name && (turn == null || it.turn == turn) }
    }
    private fun waitFor(label: String, timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timeout waiting for $label; last=${events.lastOrNull()}" }
            val last = events.lastOrNull()
            check(last?.name != "ended") { "Session ended while waiting for $label: ${last?.status}" }
            SystemClock.sleep(100)
        }
    }
    @Synchronized private fun save() {
        val array = JSONArray()
        events.forEach { e -> array.put(JSONObject().put("ms", e.at).put("event", e.name)
            .put("turn", e.turn).put("text", e.text).put("status", e.status)) }
        File(directory, "events.json").writeText(array.toString(2))
    }
    private class PacedPcm : DoubaoDialogEngine.PcmInput {
        private val running = AtomicBoolean(false)
        private val queue = LinkedBlockingQueue<ByteArray>()
        private var feed: ((ByteArray) -> Unit)? = null
        private var worker: Thread? = null
        override fun start(feed: (ByteArray) -> Unit) {
            this.feed = feed; running.set(true)
            worker = Thread({
                var next = SystemClock.elapsedRealtime()
                while (running.get()) {
                    feed(queue.poll() ?: ByteArray(640))
                    next += 20
                    SystemClock.sleep((next - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                }
            }, "VoiceAcceptancePcm").also { it.start() }
        }
        fun enqueue(bytes: ByteArray) { bytes.asList().chunked(640).forEach { queue.put(it.toByteArray()) } }
        fun feedAfterClose(bytes: ByteArray) {
            bytes.asList().chunked(640).forEach { feed?.invoke(it.toByteArray()); SystemClock.sleep(20) }
        }
        override fun close() { running.set(false); worker?.join(1000) }
    }
}
