package io.github.fartown.movo.tv

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import io.github.fartown.movo.agent.voice.conversation.VoiceConversationController
import io.github.fartown.movo.agent.voice.conversation.DoubaoDialogEngine
import io.github.fartown.movo.agent.voice.conversation.VoiceInstrumentationAccess
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.data.repository.RuntimeConfigRepository
import io.github.fartown.movo.data.repository.ProviderRepository
import io.github.fartown.movo.data.provider.PackagedModelDefaults
import io.github.fartown.movo.MovoApp
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject

/** External loudspeaker -> built-in mic -> real ASR -> real model/tools -> SDK playback. */
internal class TvVoiceConversationAcceptance(private val instrumentation: Instrumentation, private val wake: Boolean = false) {
    private data class Event(val at: Long, val name: String, val turn: Long, val text: String, val status: String)
    private val events = CopyOnWriteArrayList<Event>()
    fun run() = with(instrumentation) {
        val directory = File(targetContext.filesDir, "tv-conversation/${System.currentTimeMillis()}").apply { mkdirs() }
        val audio = targetContext.getSystemService(AudioManager::class.java)
        val oldVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val result = JSONObject()
        var oldConversation: String? = null
        val oldWakeEnabled = TclWakeService.enabled(targetContext)
        val rounds = if (wake) 1 else 3
        val began = SystemClock.elapsedRealtime()
        val output = File(directory, "player-24000-mono-s16le.pcm").outputStream()
        val microphone = File(directory, "input-16000-mono-s16le.pcm").outputStream()
        val inputPackets = JSONArray()
        var inputOffset = 0L
        fun save() {
            File(directory, "events.json").writeText(JSONArray().apply { events.forEach {
                put(JSONObject().put("at_ms", it.at).put("name", it.name).put("turn", it.turn).put("text", it.text).put("status", it.status))
            } }.toString(2))
        }
        fun await(description: String, timeout: Long = 60000, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (!condition()) {
                check(SystemClock.elapsedRealtime() < deadline) { "Timed out: $description" }
                val ended = events.lastOrNull { it.name == "ended" }
                check(ended == null) { "Voice ended: ${ended?.status}" }
                SystemClock.sleep(100)
            }
        }
        fun phase(name: String) = sendStatus(1, Bundle().apply { putString("phase", name); putString("evidence", directory.absolutePath) })
        try {
            runBlocking {
                RuntimeConfigRepository.ensureDefaults(MovoApp.serviceInstance)
                // The first development install had no .env. Provision explicitly for this
                // acceptance run; app upgrades must never overwrite an existing user choice.
                if (RuntimeConfigRepository.currentRuntimeConfig() == null) {
                    val supplied = checkNotNull(PackagedModelDefaults.provider()) { "No test model supplied" }
                    check(ProviderRepository.providerById(supplied.id) == null) { "Existing default requires manual configuration" }
                    ProviderRepository.addProvider(supplied)
                    RuntimeConfigRepository.setSelectedProviderId(supplied.id)
                    RuntimeConfigRepository.setSelectedModelId(supplied.models.first().id)
                }
                checkNotNull(RuntimeConfigRepository.currentRuntimeConfig()) { "Model configuration unavailable" }
                // TV runtime reads the local repository; Xposed remote preferences are phone-only.
            }
            targetContext.startActivity(Intent(targetContext, TvMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            SystemClock.sleep(2000)
            // am instrument force-stops the app, which leaves TCL's accessibility binding stale.
            // The host restores this already-authorized service before testing real UI tools.
            phase("prepare_accessibility")
            await("accessibility rebound", 30000) { io.github.fartown.movo.agent.accessibility.AgentAccessibilityService.isAvailable() }
            VoiceConversationController.observer = { name, turn, text, status ->
                events += Event(SystemClock.elapsedRealtime() - began, name, turn, text, status); save()
            }
            DoubaoDialogEngine.playerObserver = { bytes -> synchronized(output) { output.write(bytes) } }
            DoubaoDialogEngine.inputObserver = { bytes, muted -> synchronized(microphone) {
                microphone.write(bytes)
                inputPackets.put(JSONObject().put("at_ms", SystemClock.elapsedRealtime() - began).put("offset", inputOffset).put("bytes", bytes.size).put("muted", muted))
                File(directory, "input-packets.ndjson").appendText(inputPackets.getJSONObject(inputPackets.length() - 1).toString() + "\n")
                inputOffset += bytes.size
            } }
            if (wake) {
                runOnMainSync {
                    val session = io.github.fartown.movo.ui.app.AgentAppSession.get(targetContext)
                    oldConversation = session.conversationPaneState.selectedConversationId
                    session.createConversation()
                    if (!oldWakeEnabled) TclWakeService.toggle(targetContext)
                }
                await("wake service connection", 15000) { TclWakeService.status(targetContext) == "唤醒接管已连接" }
                phase("ready_wake")
            } else runOnMainSync { oldConversation = VoiceInstrumentationAccess.beginInPlace(targetContext) }
            await("voice ready", 30000) { events.any { it.name == "listening" } }
            for (round in 1..rounds) {
                val from = events.size
                phase("ready_round_$round")
                await("round $round dispatch") { events.drop(from).any { it.name == "dispatch" } }
                val turn = events.drop(from).first { it.name == "dispatch" }.turn
                await("round $round answer") { events.drop(from).any { it.name == "answer" && it.turn == turn } }
                val answer = events.drop(from).first { it.name == "answer" && it.turn == turn }
                check(answer.text.isNotBlank()) { "Empty model answer" }
                check(events.drop(from).any { it.name == "runtime.terminal" && it.turn == turn && it.status == "ok" }) { "Runtime failed in round $round" }
                if (round == 3) {
                    // Video playback owns audio focus after successful launch. The accepted task
                    // must complete, and speech should yield with its final answer preserved.
                    val deadline = SystemClock.elapsedRealtime() + 20000
                    while (events.none { it.name == "ended" || (it.name == "listening" && it.turn == turn && it.at > answer.at) }) {
                        check(SystemClock.elapsedRealtime() < deadline) { "Launch did not settle" }; SystemClock.sleep(100)
                    }
                    val ended = events.lastOrNull { it.name == "ended" }
                    if (ended != null) check(ended.status == DoubaoDialogEngine.MEDIA_HANDOFF_NOTICE) { "Unexpected end: ${ended.status}" }
                    val foreground = io.github.fartown.movo.agent.accessibility.AgentAccessibilityService.current()
                        ?.rootInActiveWindow?.packageName?.toString()
                    check(foreground == "com.tcl.qiyiguo") { "爱奇艺并未实际进入前台: $foreground" }
                    result.put("launched_package", foreground)
                    result.put("launch_audio_handoff", ended != null)
                    phase("complete_round_$round")
                    continue
                }
                await("round $round playback") { events.drop(from).any { it.name == "speaking" && it.turn == turn } }
                await("round $round playback drained") { events.drop(from).any { it.name == "listening" && it.turn == turn && it.at > answer.at } }
                check(events.drop(from).any { it.name == "runtime.terminal" && it.turn == turn && it.status == "ok" }) { "Runtime failed in round $round" }
                if (round == 2) check(audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 30) { "Volume tool did not set 30" }
                phase("complete_round_$round")
                SystemClock.sleep(1000)
            }
            check(events.count { it.name == "dispatch" } == rounds) { "Unexpected extra turn (possible self-trigger)" }
            result.put("pass", true)
        } catch (failure: Throwable) {
            result.put("pass", false).put("failure", failure.toString())
        } finally {
            runOnMainSync { VoiceSessionManager.cancelTask(); VoiceInstrumentationAccess.end(targetContext) }
            if (wake && !oldWakeEnabled) runOnMainSync { if (TclWakeService.enabled(targetContext)) TclWakeService.toggle(targetContext) }
            SystemClock.sleep(1500)
            DoubaoDialogEngine.playerObserver = null
            DoubaoDialogEngine.inputObserver = null
            VoiceConversationController.observer = null
            synchronized(output) { output.close() }
            synchronized(microphone) { microphone.close(); File(directory, "input-packets.json").writeText(inputPackets.toString()) }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, oldVolume, 0)
            runOnMainSync { VoiceInstrumentationAccess.restore(targetContext, oldConversation) }
            save()
            File(directory, "diagnostics.json").writeText(VoiceInstrumentationAccess.diagnostics())
            File(directory, "result.json").writeText(result.toString(2))
            phase("release_accessibility")
            val releaseDeadline = SystemClock.elapsedRealtime() + 5000
            while (io.github.fartown.movo.agent.accessibility.AgentAccessibilityService.isAvailable() && SystemClock.elapsedRealtime() < releaseDeadline) SystemClock.sleep(100)
            finish(if (result.optBoolean("pass")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("result", result.toString()); putString("evidence", directory.absolutePath) })
        }
    }
}
