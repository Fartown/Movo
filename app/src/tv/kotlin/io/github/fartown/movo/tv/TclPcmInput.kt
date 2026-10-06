package io.github.fartown.movo.tv

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import io.github.fartown.movo.agent.voice.conversation.DoubaoDialogEngine
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Version-gated adapter for the already verified TCL local capture interface. */
internal class TclPcmInput(
    context: Context,
    private val onError: (String) -> Unit,
) : DoubaoDialogEngine.PcmInput {
    private val app = context.applicationContext
    private val running = AtomicBoolean(false)
    private val lock = Any()
    private var worker: Thread? = null
    private val recovery = File(app.filesDir, "tcl-audio-recovery")
    override val acousticEchoCancellation: Boolean = false
    // This source lacks the phone recorder's noise suppression. Physical tests proved that
    // energy-only detection repeatedly vetoed a correctly recognized cloud endpoint.
    override val localActivityDetection: Boolean = false
    override val yieldToMediaPlayback: Boolean = true
    override val captureWhileConnecting: Boolean = true
    @Volatile private var outputSuppressed = false
    override fun setOutputSuppressed(suppressed: Boolean) { outputSuppressed = suppressed }

    override fun start(feed: (ByteArray) -> Unit) = synchronized(lock) {
        check(!running.get()) { "内置麦克风已经开始采音" }
        check(app.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            app.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            "请先授权 TCL 内置麦克风所需的录音文件访问"
        }
        check(owner.compareAndSet(false, true)) { "内置麦克风正在使用，请稍后重试" }
        running.set(true)
        try {
            worker = thread(name = "movo-tcl-pcm", isDaemon = true) {
                try {
                    prepare()
                    while (running.get()) segment(feed)
                } catch (failure: Exception) {
                    if (running.get()) onError(failure.message ?: "电视内置麦克风读取失败")
                } finally {
                    running.set(false)
                    owner.set(false)
                    MemoryDiagnostics.record("tv.voice", "capture.stopped")
                }
            }
        } catch (failure: Exception) {
            running.set(false)
            owner.set(false)
            throw failure
        }
    }

    private fun prepare() {
        // Recover an interrupted session before a new OEM START can clear the shared folder.
        if (File(recovery, "ready").exists()) {
            broadcast(false)
            Thread.sleep(200)
            restore()
        }
        stopContinuous()
        val raw = File(app.getExternalFilesDir(null), "tcl-live.pcm")
        Thread.sleep(200)
        check(!raw.exists() || raw.delete()) { "无法清理上次中断的采音" }
    }

    private fun restore() {
        val saved = File(recovery, WAV_NAME)
        if (saved.exists()) {
            check(source.mkdirs() || source.isDirectory) { "无法还原已有录音" }
            saved.copyTo(File(source, WAV_NAME), overwrite = true)
        } else {
            val wav = File(source, WAV_NAME)
            check(!wav.exists() || wav.delete()) { "无法清理本轮采音" }
        }
        // Keep backup intact on any failure, including process death during copy.
        check(recovery.deleteRecursively()) { "无法清理采音备份" }
    }

    private fun segment(feed: (ByteArray) -> Unit) {
        val raw = File(app.getExternalFilesDir(null), "tcl-live.pcm")
        check(raw.parentFile!!.usableSpace > 160L * 1024 * 1024) { "电视存储空间不足，无法安全采音" }
        check(!raw.exists() || raw.delete()) { "无法准备采音文件" }
        synchronized(lock) {
            if (!running.get()) return
            app.sendBroadcast(Intent("com.tcl.walleve.startlogaudio").setPackage("com.tcl.walleve")
                .putExtra("path", raw.absolutePath))
        }
        val began = SystemClock.elapsedRealtime()
        var total = 0L
        try {
            while (running.get() && !raw.exists() && SystemClock.elapsedRealtime() - began < 4000) Thread.sleep(25)
            if (!running.get()) return
            check(raw.exists()) { "小T未创建采音文件，请确认内置麦克风已启用" }
            var lastData = SystemClock.elapsedRealtime()
            RandomAccessFile(raw, "r").use { input ->
                val reader = TclWavPcm(input, gain = 16, raw = true)
                while (running.get()) {
                    val now = SystemClock.elapsedRealtime()
                    // Recycle only while our own answer has explicitly muted microphone delivery.
                    // Normal speech remains continuous across the OEM EQ interface's old 20 s limit.
                    if (outputSuppressed && now - began > 30000) break
                    check(raw.length() < 128L * 1024 * 1024) { "连续采音达到安全存储上限，请重新开始语音" }
                    val pcm = reader.read()
                    if (pcm == null) {
                        check(now - lastData < 4000) { "内置麦克风没有返回音频" }
                        Thread.sleep(25)
                    } else {
                        feed(pcm); total += pcm.size; lastData = now
                    }
                }
            }
        } finally {
            stopContinuous()
            Thread.sleep(200)
            check(!raw.exists() || raw.delete()) { "无法清理本轮采音文件" }
        }
        MemoryDiagnostics.record("tv.voice", "capture.segment", fields = mapOf("pcm_bytes" to total))
    }

    private fun stopContinuous() = app.sendBroadcast(Intent("com.tcl.walleve.stoplogaudio").setPackage("com.tcl.walleve"))

    private fun broadcast(start: Boolean) = app.sendBroadcast(Intent(
        if (start) "android.intent.action.EQ_RECORD_START" else "android.intent.action.EQ_RECORD_END",
    ).setPackage("com.tcl.walleve"))

    override fun close() {
        synchronized(lock) { running.set(false) }
        if (worker !== Thread.currentThread()) worker?.join(2000)
    }

    companion object {
            private const val WAV_NAME = "original_0.wav"
        private val source = File("/sdcard/walleve/cae_record")
        private val owner = AtomicBoolean(false)

        fun supported(context: Context): Boolean = Build.VERSION.SDK_INT == 28 &&
            runCatching {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo("com.tcl.walleve", 0)
                info.longVersionCode == 623010L
            }.getOrDefault(false)
    }
}
