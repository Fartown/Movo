package io.github.fartown.movo.tv

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/** Real OEM input, no ASR event injection. Audio remains private test evidence on the device. */
class TvAcceptanceInstrumentation : Instrumentation() {
    private var options = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); options = arguments ?: Bundle(); start() }
    override fun onStart() {
        // start() launches this thread before ActivityThread finishes Application.onCreate().
        waitForIdleSync()
        if (options.getString("mode") == "manual") { TvLocalRecordingAcceptance(this).run(); return }
        if (options.getString("mode") == "wake") { TvVoiceConversationAcceptance(this, wake = true).run(); return }
        if (options.getString("mode") == "conversation") { TvVoiceConversationAcceptance(this).run(); return }
        val directory = File(targetContext.filesDir, "tv-acceptance/${System.currentTimeMillis()}").apply { mkdirs() }
        val original = File("/sdcard/walleve/cae_record/original_0.wav")
        val before = hash(original)
        val errors = CopyOnWriteArrayList<String>()
        val packets = CopyOnWriteArrayList<Pair<Long, Int>>()
        val samples = AtomicLong()
        val audio = targetContext.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener {}.build()
        var input: TclPcmInput? = null
        val result = JSONObject()
        try {
            check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio focus denied" }
            check(TclPcmInput.supported(targetContext)) { "Unsupported OEM version" }
            targetContext.startActivity(Intent(targetContext, TvMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(1500)
            val began = SystemClock.elapsedRealtime()
            File(directory, "microphone-16000-mono-s16le.pcm").outputStream().use { out ->
                input = TclPcmInput(targetContext) { errors += it }
                input!!.start { packet ->
                    synchronized(out) { out.write(packet) }
                    packets += (SystemClock.elapsedRealtime() - began) to packet.size
                    samples.addAndGet(packet.size / 2L)
                }
                sendStatus(1, Bundle().apply { putString("phase", "capturing"); putString("evidence", directory.absolutePath) })
                val duration = options.getString("seconds", "46").toLong().coerceIn(3, 60) * 1000
                while (SystemClock.elapsedRealtime() - began < duration && errors.isEmpty()) SystemClock.sleep(100)
                input!!.close()
                val stoppedAt = samples.get()
                SystemClock.sleep(400)
                check(samples.get() == stoppedAt) { "Audio arrived after close" }
                check(errors.isEmpty()) { errors.joinToString() }
                check(samples.get() >= (duration / 1000 - 3) * 16000) { "Too little PCM: ${samples.get()} samples" }
                check(hash(original) == before) { "Pre-existing recording was not restored" }
                input!!.close() // Must not stop a new capture or mutate the restored file.
                check(hash(original) == before) { "Repeated close mutated source" }
            }
            input = TclPcmInput(targetContext) { errors += it }
            val restarted = AtomicLong()
            input!!.start { restarted.addAndGet(it.size.toLong()) }
            SystemClock.sleep(3000)
            input!!.close()
            check(restarted.get() >= 64000) { "Capture did not restart" }
            check(errors.isEmpty()) { errors.joinToString() }
            check(hash(original) == before) { "Restart did not restore source" }
            result.put("pass", true).put("samples", samples.get()).put("restart_bytes", restarted.get())
        } catch (failure: Throwable) {
            result.put("pass", false).put("failure", failure.toString())
        } finally {
            input?.close()
            audio.abandonAudioFocusRequest(focus)
            result.put("packets", JSONArray().apply { packets.forEach { (at, size) -> put(JSONObject().put("at_ms", at).put("bytes", size)) } })
            File(directory, "result.json").writeText(result.toString(2))
            finish(if (result.optBoolean("pass")) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
                putString("result", result.toString()); putString("evidence", directory.absolutePath)
            })
        }
    }
    private fun hash(file: File): String? = if (!file.exists()) null else file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(16384)
        while (true) { val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
}
