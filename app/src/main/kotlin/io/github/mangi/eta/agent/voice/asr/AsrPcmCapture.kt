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

/**
 * Captures 16 kHz mono 16-bit PCM in ~200 ms packets for Doubao ASR.
 * [onLevel] receives a 0–1 microphone level for every ~50 ms read (spec 9.6), on the recording thread.
 */
internal class AsrPcmCapture(
    private val context: Context,
    private val onPacket: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    private val onLevel: (Float) -> Unit = {},
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
            // Read in ~50 ms slices so the level meter follows speech; packets sent to the server stay ~200 ms.
            val slice = (packet.size / LEVEL_SLICES).coerceAtLeast(2) and 1.inv()
            var filled = 0
            try {
                while (running.get()) {
                    val read = recorder.read(packet, filled, minOf(slice, packet.size - filled))
                    if (read > 0) {
                        onLevel(pcmLevel(packet, filled, read))
                        filled += read
                        if (filled >= packet.size) {
                            onPacket(packet.copyOf(filled))
                            filled = 0
                        }
                    } else if (read < 0) {
                        if (running.get()) onError("麦克风读取失败 ($read)")
                        break
                    }
                }
                // Flush the tail so a stop never drops the last partial packet of speech.
                if (filled > 0) onPacket(packet.copyOf(filled))
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

    internal companion object {
        private const val LEVEL_SLICES = 4

        /** RMS of 16-bit little-endian PCM mapped linearly from −50 dBFS → 0 to −10 dBFS → 1. */
        fun pcmLevel(buffer: ByteArray, offset: Int, length: Int): Float {
            val samples = length / 2
            if (samples == 0) return 0f
            var sum = 0.0
            var i = offset
            repeat(samples) {
                val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
                sum += sample.toDouble() * sample
                i += 2
            }
            val rms = kotlin.math.sqrt(sum / samples) / Short.MAX_VALUE
            if (rms <= 0.0) return 0f
            val db = 20 * kotlin.math.log10(rms)
            return ((db + 50) / 40).toFloat().coerceIn(0f, 1f)
        }
    }
}
