package io.github.fartown.movo.tv

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import java.io.File
import java.io.RandomAccessFile
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/** Explicit local recording, bounded to one minute and one private file. No cloud request. */
internal class TvRecordingTest(private val context: Context, private val changed: (String, Boolean, Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val directory = File(context.cacheDir, "tv-microphone-test").apply { mkdirs() }
    private val recording = File(directory, "latest.wav")
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { if (it < 0) stop("音频焦点已被其他应用接管") }.build()
    private var input: TclPcmInput? = null
    private var output: RandomAccessFile? = null
    private var player: MediaPlayer? = null
    private var frames = 0L
    private var squares = 0.0
    private var peak = 0
    private var started = 0L
    private var lastUpdate = 0L
    private val lock = Any()
    private var disposed = false
    private val deadline = Runnable { stop("已到 60 秒，自动保存") }

    fun start() {
        if (input != null || disposed) return
        if (!TclPcmInput.supported(context)) { report("当前录音检测适用于已验证的 TCL 内置麦克风", false); return }
        if (VoiceSessionManager.active) { report("请先结束语音对话，再开始本地录音检测", false); return }
        if (listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            report("请返回设置，先点击“授权录音”", false); return
        }
        stopPlayer()
        try {
            check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "无法取得音频焦点" }
            synchronized(lock) {
                frames = 0; squares = 0.0; peak = 0; lastUpdate = 0
                output = RandomAccessFile(recording, "rw").apply { setLength(0); write(ByteArray(44)) }
            }
            started = SystemClock.elapsedRealtime()
            input = TclPcmInput(context) { message -> main.post { stop(message) } }.also { capture ->
                capture.start feed@ { pcm ->
                    synchronized(lock) {
                        val file = output ?: return@feed
                        file.write(pcm)
                        for (i in pcm.indices step 2) {
                            val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
                            squares += sample.toDouble() * sample; peak = maxOf(peak, abs(sample)); frames++
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUpdate >= 1000) {
                            lastUpdate = now
                            val status = metrics("正在录音")
                            main.post { if (input != null) report(status, true) }
                        }
                    }
                }
            }
            report("正在录音，请直接对电视说话。最长 60 秒。", true)
            main.postDelayed(deadline, 60000)
            MemoryDiagnostics.record("tv.voice", "manual_record.started")
        } catch (failure: Exception) { stop(failure.message ?: "录音启动失败") }
    }

    fun stop(reason: String = "已停止并保存") {
        main.removeCallbacks(deadline)
        val capture = input
        input = null
        capture?.close()
        synchronized(lock) {
            output?.let { file ->
                val bytes = frames * 2
                file.seek(0)
                fun le(value: Int) = file.writeInt(Integer.reverseBytes(value))
                fun short(value: Int) = file.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
                file.writeBytes("RIFF"); le((36 + bytes).toInt()); file.writeBytes("WAVEfmt ")
                le(16); short(1); short(1); le(16000); le(32000); short(2); short(16)
                file.writeBytes("data"); le(bytes.toInt()); file.close()
                output = null
            }
            if (capture != null || started != 0L) {
                val result = JSONObject().put("samples", frames).put("peak", peak)
                    .put("rms", if (frames == 0L) 0 else sqrt(squares / frames).toInt())
                    .put("audio_received", frames > 0).put("nonzero_audio", peak > 0).put("reason", reason)
                File(directory, "latest.json").writeText(result.toString(2))
                MemoryDiagnostics.record("tv.voice", "manual_record.stopped", fields = mapOf("samples" to frames, "peak" to peak))
            }
            report(metrics(if (frames == 0L) "$reason；没有采集到音频" else reason), false)
        }
        audio.abandonAudioFocusRequest(focus)
    }

    fun play() {
        if (input != null || !recording.exists() || disposed) return
        stopPlayer()
        try {
            check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(recording.absolutePath)
                setOnCompletionListener { stopPlayer(); report("回放结束。听到自己的声音才表示实际拾音通过。", false) }
                setOnErrorListener { _, _, _ -> stopPlayer(); report("回放失败，可重新录音再试", false); true }
                prepare(); start()
            }
            report("正在回放本地录音", false)
        } catch (_: Exception) { stopPlayer(); report("录音无法播放，请重新录音", false) }
    }

    private fun stopPlayer() { player?.release(); player = null; audio.abandonAudioFocusRequest(focus) }
    private fun metrics(prefix: String) = "$prefix · ${frames / 16000} 秒 · ${frames * 2} 字节 · 峰值 $peak · RMS ${if (frames == 0L) 0 else sqrt(squares / frames).toInt()}"
    private fun report(message: String, running: Boolean) { if (!disposed) changed(message, running, recording.length() > 44 && !running) }
    fun close() { disposed = true; if (input != null || output != null) stop("离开页面，已停止"); stopPlayer() }
}

@Composable internal fun TvRecordingTestPage(context: Context) {
    var status by remember { mutableStateOf("点击开始录音后直接说话，再停止并回放。采集成功或失败都会留下检测日志。") }
    var recording by remember { mutableStateOf(false) }
    var playable by remember { mutableStateOf(false) }
    val test = remember(context) { TvRecordingTest(context) { text, running, saved -> status = text; recording = running; playable = saved } }
    DisposableEffect(test) { onDispose { test.close() } }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TvBody(status)
        TvButton("开始录音", primary = true, enabled = !recording) { test.start() }
        TvButton("停止并保存", enabled = recording) { test.stop() }
        TvButton("回放录音", enabled = playable) { test.play() }
        TvHint("此检测不上传声音；仅保留最近一次录音与采集日志。音量指标不能代替听音确认。")
    }
}
