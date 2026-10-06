package io.github.fartown.movo.tvvoiceprobe

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import com.k2fsa.sherpa.onnx.*
import com.bytedance.speech.speechengine.SpeechEngineGenerator
import com.bytedance.speech.speechengine.SpeechEngineDefines as D
import java.io.File
import org.json.JSONObject

/** No credentials, network session, microphone capture or speech recognition claim. */
class VoiceProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val label = TextView(this).apply { text = "Movo · 32 位语音库自动验证"; textSize = 24f }
        setContentView(label)
        Thread({
            val results = JSONObject()
            results.put("speechengine", runProbe { speechEngine() })
            results.put("sherpa", runProbe { sherpa() })
            File(filesDir, "voice-results.json").writeText(results.toString(2))
            Log.i("MovoTvVoice", "RESULT $results")
            runOnUiThread { label.text = results.toString(2) }
        }, "voice-library-probe").start()
    }
    private fun runProbe(test: () -> JSONObject): JSONObject = try { test() }
        catch (error: Exception) { JSONObject().put("error", error.toString()) }
        catch (error: LinkageError) { JSONObject().put("error", error.toString()) }

    private fun speechEngine(): JSONObject {
        val start = SystemClock.elapsedRealtime()
        SpeechEngineGenerator.PrepareEnvironment(applicationContext, application)
        val sdk = SpeechEngineGenerator.getInstance()
        try {
            val handle = sdk.createEngine()
            check(handle != 0L) { "createEngine returned zero" }
            sdk.setContext(applicationContext)
            sdk.setOptionString(D.PARAMS_KEY_ENGINE_NAME_STRING, D.DIALOG_ENGINE)
            sdk.setOptionString(D.PARAMS_KEY_LOG_LEVEL_STRING, D.LOG_LEVEL_ERROR)
            sdk.setOptionString(D.PARAMS_KEY_RESOURCE_ID_STRING, "volc.speech.dialog")
            sdk.setOptionString(D.PARAMS_KEY_UID_STRING, "movo-tv-probe")
            sdk.setOptionString(D.PARAMS_KEY_DIALOG_ADDRESS_STRING, "wss://openspeech.bytedance.com")
            sdk.setOptionString(D.PARAMS_KEY_DIALOG_URI_STRING, "/api/v3/realtime/dialogue")
            sdk.setOptionString(D.PARAMS_KEY_RECORDER_TYPE_STRING, D.RECORDER_TYPE_STREAM)
            sdk.setOptionInt(D.PARAMS_KEY_DIALOG_WORK_MODE_INT, D.DIALOG_WORK_MODE_DELEGATE_CHAT_TTS_TEXT)
            sdk.setOptionBoolean(D.PARAMS_KEY_DIALOG_ENABLE_PLAYER_BOOL, false)
            sdk.setOptionBoolean(D.PARAMS_KEY_ENABLE_AEC_BOOL, false)
            val initialized = sdk.initEngine()
            return JSONObject().put("native_created", true).put("init_result_without_credentials", initialized)
                .put("elapsed_ms", SystemClock.elapsedRealtime() - start).put("pss_kb", Debug.getPss())
                .put("cloud_session_started", false)
        } finally { sdk.destroyEngine() }
    }
    private fun sherpa(): JSONObject {
        val directory = File(filesDir, "kws").apply { mkdirs() }
        for (name in listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")) {
            assets.open("sherpa-kws/$name").use { input -> File(directory, name).outputStream().use { input.copyTo(it) } }
        }
        val keywords = File(directory, "keywords.txt").apply { writeText("HH AH0 L OW1 W ER1 L D @HELLO_WORLD\n") }
        val start = SystemClock.elapsedRealtime()
        val spotter = KeywordSpotter(config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(transducer = OnlineTransducerModelConfig(
                encoder = File(directory, "encoder.int8.onnx").path,
                decoder = File(directory, "decoder.onnx").path,
                joiner = File(directory, "joiner.int8.onnx").path),
                tokens = File(directory, "tokens.txt").path, modelType = "zipformer2", numThreads = 1),
            keywordsFile = keywords.path))
        val init = SystemClock.elapsedRealtime() - start
        try {
            val stream = spotter.createStream()
            try {
                val infer = SystemClock.elapsedRealtime()
                val cpu = SystemClock.currentThreadTimeMillis()
                var decoded = 0
                repeat(50) {
                    stream.acceptWaveform(FloatArray(1600), 16000)
                    while (spotter.isReady(stream)) { spotter.decode(stream); decoded++ }
                }
                val keyword = spotter.getResult(stream).keyword
                return JSONObject().put("model_initialized", true).put("init_ms", init)
                    .put("test_input", "five_seconds_digital_silence").put("decode_steps", decoded)
                    .put("inference_wall_ms", SystemClock.elapsedRealtime() - infer)
                    .put("inference_thread_cpu_ms", SystemClock.currentThreadTimeMillis() - cpu)
                    .put("empty_result_on_silence", keyword.isEmpty()).put("pss_kb", Debug.getPss())
            } finally { stream.release() }
        } finally { spotter.release() }
    }
}
