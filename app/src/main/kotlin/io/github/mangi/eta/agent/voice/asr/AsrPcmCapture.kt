package io.github.mangi.eta.agent.voice.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Captures 16 kHz mono 16-bit PCM in ~200 ms packets for Doubao ASR. */
internal class AsrPcmCapture(
    private val context: Context,
    private val onPacket: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            running.set(false)
            onError("缺少麦克风权限")
            return
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            DoubaoSaucProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            running.set(false)
            onError("无法初始化麦克风")
            return
        }
        val bufferSize = maxOf(minBuffer, DoubaoSaucProtocol.PACKET_BYTES * 2)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                DoubaoSaucProtocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (error: SecurityException) {
            running.set(false)
            onError("麦克风权限被拒绝")
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            running.set(false)
            onError("麦克风初始化失败")
            return
        }
        audioRecord = recorder
        try {
            recorder.startRecording()
        } catch (error: RuntimeException) {
            audioRecord = null
            recorder.release()
            running.set(false)
            onError("无法启动麦克风")
            return
        }
        recordThread = thread(name = "eta-asr-pcm", isDaemon = true) {
            val packet = ByteArray(DoubaoSaucProtocol.PACKET_BYTES)
            try {
                while (running.get()) {
                    val read = recorder.read(packet, 0, packet.size)
                    if (read > 0) {
                        onPacket(packet.copyOf(read))
                    } else if (read < 0) {
                        if (running.get()) onError("麦克风读取失败 ($read)")
                        break
                    }
                }
            } catch (error: RuntimeException) {
                if (running.get()) onError("麦克风读取失败")
            } finally {
                running.set(false)
                runCatching { recorder.stop() }
                recorder.release()
            }
        }
    }

    fun stop() {
        running.set(false)
        // Unblock a pending read before joining. The recording thread owns release().
        audioRecord?.runCatching { stop() }
        if (recordThread !== Thread.currentThread()) recordThread?.join(1_000)
        recordThread = null
        audioRecord = null
    }
}
