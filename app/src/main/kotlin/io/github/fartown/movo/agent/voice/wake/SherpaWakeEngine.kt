package io.github.fartown.movo.agent.voice.wake

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.core.safeLogType
import io.github.fartown.movo.data.model.WakePhraseRules
import io.github.fartown.movo.data.model.WakeSensitivity
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Bundled local KWS. The worker exclusively owns native recognizers and streams. */
internal class SherpaWakeEngine(private val context: Context) : WakeWordEngine {
    private val main = Handler(Looper.getMainLooper())
    private val control = Object()
    @Volatile private var enabled = false
    @Volatile private var paused = false
    @Volatile private var recording = false
    @Volatile private var version = 0
    @Volatile private var epoch = 0
    @Volatile private var delivery = 0
    @Volatile private var phrase = WakePhraseRules.DEFAULT
    @Volatile private var sensitivity = WakeSensitivity.Medium
    private var listener: WakeWordEngine.Listener? = null
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null
    private var released = CountDownLatch(0)

    override fun engineName() = "sherpa-onnx"
    override fun isRunning() = enabled && recording && !paused

    override fun start(phrase: String, sensitivity: WakeSensitivity, listener: WakeWordEngine.Listener) {
        stop()
        this.listener = listener
        this.phrase = WakePhraseRules.normalizeOrDefault(phrase)
        this.sensitivity = sensitivity
        enabled = true
        paused = false
        val session = epoch
        worker = thread(name = "movo-local-wake", isDaemon = true) { runEngine(session) }
    }

    override fun updateKeywords(phrase: String, sensitivity: WakeSensitivity) {
        val normalized = WakePhraseRules.normalizeOrDefault(phrase)
        if (this.phrase == normalized && this.sensitivity == sensitivity) return
        synchronized(control) {
            this.phrase = normalized
            this.sensitivity = sensitivity
            version++
            delivery++
            runCatching { recorder?.stop() }
            control.notifyAll()
        }
    }

    override fun pause() {
        val signal = synchronized(control) {
            paused = true
            delivery++
            runCatching { recorder?.stop() }
            released
        }
        // Hand over the microphone after its recording session is released.
        signal.await(1, TimeUnit.SECONDS)
    }

    override fun resume() {
        synchronized(control) {
            if (!enabled) return
            paused = false
            control.notifyAll()
        }
    }

    override fun stop() {
        synchronized(control) {
            enabled = false
            epoch++
            delivery++
            runCatching { recorder?.stop() }
            control.notifyAll()
        }
        if (worker !== Thread.currentThread()) worker?.join(1_000)
        worker = null
        listener = null
    }

    private fun notifyState(session: Int) {
        main.post { if (epoch == session) listener?.onListeningChanged(isRunning()) }
    }

    private fun runEngine(session: Int) {
        try {
            val directory = prepareModels()
            val encoder = WakeKeywordEncoder.load(context)
            while (enabled && epoch == session) {
                val currentVersion = version
                val currentPhrase = phrase
                val currentSensitivity = sensitivity
                val keywords = File(directory, "keywords-$session.txt")
                keywords.writeText(encoder.encode(currentPhrase).joinToString("\n") { "$it @wake" })
                val spotter = KeywordSpotter(config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = File(directory, "encoder.int8.onnx").path,
                            decoder = File(directory, "decoder.onnx").path,
                            joiner = File(directory, "joiner.int8.onnx").path,
                        ),
                        tokens = File(directory, "tokens.txt").path,
                        modelType = "zipformer2", numThreads = 1,
                    ),
                    keywordsFile = keywords.path,
                    keywordsThreshold = when (currentSensitivity) {
                        WakeSensitivity.Low -> 0.35f
                        WakeSensitivity.Medium -> 0.25f
                        WakeSensitivity.High -> 0.15f
                    },
                ))
                try {
                    while (enabled && epoch == session && version == currentVersion) {
                        synchronized(control) {
                            while (enabled && paused && epoch == session && version == currentVersion) control.wait()
                        }
                        if (!enabled || epoch != session || version != currentVersion) break
                        listen(spotter, currentPhrase, currentVersion, session)
                    }
                } finally {
                    spotter.release()
                    keywords.delete()
                }
            }
        } catch (error: Exception) {
            reportError(session, error)
        } catch (error: LinkageError) {
            reportError(session, error)
        } finally {
            if (epoch == session) { recording = false; enabled = false; notifyState(session) }
        }
    }

    private fun listen(spotter: KeywordSpotter, target: String, configVersion: Int, session: Int) {
        // Created only while the energy gate is open; quiet audio never reaches the model.
        var stream: OnlineStream? = null
        var audio: AudioRecord? = null
        var detected = false
        val callbackGeneration = delivery
        val usage = WakeUsage()
        val samples = ShortArray(1_600)
        val gate = WakeEnergyGate(samples.size)
        try {
            synchronized(control) {
                if (!enabled || paused || epoch != session || version != configVersion) return
                val buffer = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(buffer > 0) { "无法初始化唤醒麦克风" }
                audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(buffer, 6_400))
                recorder = audio
                released = CountDownLatch(1)
                check(audio!!.state == AudioRecord.STATE_INITIALIZED) { "唤醒麦克风初始化失败" }
                audio!!.startRecording()
                recording = true
            }
            notifyState(session)
            while (enabled && !paused && epoch == session && version == configVersion) {
                val count = audio!!.read(samples, 0, samples.size)
                if (!enabled || paused || epoch != session || version != configVersion) break
                check(count >= 0) { "唤醒麦克风读取失败 ($count)" }
                if (count == 0) continue
                usage.heard(count)
                val active = when (gate.offer(samples, count)) {
                    WakeEnergyGate.Action.Skip -> null
                    WakeEnergyGate.Action.Closed -> {
                        stream?.release()
                        stream = null
                        null
                    }
                    WakeEnergyGate.Action.Opened -> spotter.createStream().also { opened ->
                        stream = opened
                        usage.opened()
                        gate.drainPreRoll { chunk, length ->
                            opened.acceptWaveform(FloatArray(length) { chunk[it] / 32768.0f }, 16_000)
                            usage.modelled(length)
                        }
                    }
                    WakeEnergyGate.Action.Feed -> stream
                }
                if (active != null) {
                    active.acceptWaveform(FloatArray(count) { samples[it] / 32768.0f }, 16_000)
                    usage.modelled(count)
                    while (spotter.isReady(active)) {
                        spotter.decode(active)
                        if (spotter.getResult(active).keyword.isNotBlank()) {
                            synchronized(control) { paused = true }
                            detected = true
                            break
                        }
                    }
                }
                usage.reportIfDue(gate.noiseFloorDb)
            }
        } finally {
            synchronized(control) {
                runCatching { audio?.stop() }
                audio?.release()
                recorder = null
                recording = false
                released.countDown()
            }
            stream?.release()
            usage.report(gate.noiseFloorDb)
            notifyState(session)
        }
        if (detected) {
            main.post {
                if (enabled && epoch == session && delivery == callbackGeneration && version == configVersion) {
                    listener?.onDetected(target)
                }
            }
        }
    }

    private fun reportError(session: Int, error: Throwable) {
        AndroidAgentLogger.warn("Local wake failed: type=${error.safeLogType()}")
        main.post {
            if (epoch == session) listener?.onError(
                if (error is IllegalArgumentException) error.message.orEmpty()
                else "本地唤醒启动失败，请重新开启并检查麦克风权限",
            )
        }
    }

    private fun prepareModels(): File {
        val directory = File(context.filesDir, "sherpa-kws-zh-en-20251220").apply { mkdirs() }
        for (name in listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")) {
            val file = File(directory, name)
            if (!file.isFile || file.length() == 0L) {
                val temporary = File(directory, "$name.tmp")
                context.assets.open("sherpa-kws/$name").use { input -> temporary.outputStream().use { input.copyTo(it) } }
                check(temporary.renameTo(file)) { "无法准备本地唤醒模型" }
            }
        }
        return directory
    }
}

/**
 * Resource usage of one listening session, measured on the worker thread. Durations and
 * counts only, never audio. cpu_ms covers reading, gating and inference on this thread.
 */
private class WakeUsage {
    private val cpuStartMs = SystemClock.currentThreadTimeMillis()
    private var heardSamples = 0L
    private var modelSamples = 0L
    private var gateOpens = 0
    private var reportedSamples = 0L

    fun heard(count: Int) { heardSamples += count }
    fun modelled(count: Int) { modelSamples += count }
    fun opened() { gateOpens++ }

    fun reportIfDue(floorDb: Double) {
        if (heardSamples - reportedSamples >= REPORT_EVERY_SAMPLES) report(floorDb)
    }

    fun report(floorDb: Double) {
        if (heardSamples == reportedSamples) return
        reportedSamples = heardSamples
        val fields = mapOf(
            "audio_s" to heardSamples / SAMPLE_RATE,
            "model_s" to modelSamples / SAMPLE_RATE,
            "gate_opens" to gateOpens,
            "cpu_ms" to SystemClock.currentThreadTimeMillis() - cpuStartMs,
            "floor_db" to if (floorDb.isNaN()) null else Math.round(floorDb * 10) / 10.0,
        )
        MemoryDiagnostics.record("voice", "wake.usage", fields = fields)
        AndroidAgentLogger.info("Wake usage: $fields")
    }

    private companion object {
        const val SAMPLE_RATE = 16_000L
        const val REPORT_EVERY_SAMPLES = 10 * 60 * SAMPLE_RATE
    }
}

internal object WakeEngineFactory {
    fun create(context: Context): WakeWordEngine = SherpaWakeEngine(context.applicationContext)
}
