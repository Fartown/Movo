package io.github.fartown.movo.tv

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import org.json.JSONObject

internal class TvLocalRecordingAcceptance(private val instrumentation: Instrumentation) {
    fun run() = with(instrumentation) {
        var recorder: TvRecordingTest? = null
        var status = ""
        var running = false
        var playable = false
        val directory = File(targetContext.cacheDir, "tv-microphone-test")
        val result = JSONObject()
        try {
            targetContext.startActivity(Intent(targetContext, TvMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync {
                recorder = TvRecordingTest(targetContext) { text, active, saved -> status = text; running = active; playable = saved }
                recorder!!.start()
            }
            check(running) { status }
            sendStatus(1, Bundle().apply { putString("phase", "manual_recording"); putString("evidence", directory.absolutePath) })
            SystemClock.sleep(10000)
            runOnMainSync { recorder!!.stop() }
            check(!running && playable) { status }
            val metrics = JSONObject(File(directory, "latest.json").readText())
            check(metrics.getLong("samples") >= 8 * 16000) { "Insufficient recording: $metrics" }
            check(metrics.getInt("peak") > 0) { "Only zero samples" }
            runOnMainSync { recorder!!.play() }
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (!status.startsWith("回放结束") && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            check(status.startsWith("回放结束")) { status }
            result.put("pass", true).put("capture", metrics).put("playback_completed", true)
        } catch (failure: Throwable) { result.put("pass", false).put("failure", failure.toString()) }
        finally {
            runOnMainSync { recorder?.close() }
            File(directory, "acceptance.json").writeText(result.toString(2))
            finish(if (result.optBoolean("pass")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("result", result.toString()); putString("evidence", directory.absolutePath) })
        }
    }
}
