package io.github.mangi.eta.agent.voice.conversation

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.Keep
import com.bytedance.speech.speechengine.SpeechEngine
import com.bytedance.speech.speechengine.SpeechEngineDefines as D
import com.bytedance.speech.speechengine.SpeechEngineGenerator
import io.github.mangi.eta.data.model.DoubaoSpeechCredentials
import io.github.mangi.eta.diagnostics.MemoryDiagnostics
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Official Dialog transport. SDK owns recording, AEC and playback; Movo owns every answer. */
@Keep
internal class DoubaoDialogEngine(
    private val context: Context,
    private val listener: Listener,
    private val sourceFactory: (() -> PcmInput)? = pcmInputFactory,
) {
    interface Listener {
        fun onReady()
        fun onSpeechStarted(turn: Long)
        fun onPartial(turn: Long, text: String)
        fun onSpeechEnded(turn: Long)
        fun onInputActivity(atElapsedMs: Long)
        fun onPlaybackStarted(turn: Long)
        fun onPlaybackFinished(turn: Long)
        fun onError(message: String)
    }

    /** A same-signature instrumentation test can supply real-time PCM without replacing ASR events. */
    @Keep
    interface PcmInput {
        fun start(feed: (ByteArray) -> Unit)
        fun close()
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "MovoDialog") }
    private val closed = AtomicBoolean(false)
    private val closeCallbacks = mutableListOf<() -> Unit>()
    private var fullyClosed = false
    private var engine: SpeechEngine? = null
    private var focus: AudioFocusRequest? = null
    private var input: PcmInput? = null
    private var ready = false
    private var ownsAudio = false
    private var turn = 0L
    private var questionId = ""
    private var outputTurn: Long? = null
    private var outputReplyId = ""
    private var synthesisEnded = false
    private var outputStarted = false
    private var lastSoundAt = 0L
    private var outputRequestedAt = 0L
    private var expectedDurationMs = 0L
    private var outputStartedAt = 0L
    private var playbackPaused = false
    private var pausedAt = 0L
    private val inputActivity = VoiceInputActivity()

    fun start(credentials: DoubaoSpeechCredentials) = execute {
        check(audioInUse.compareAndSet(false, true)) { "语音设备正在结束上一段连接，请稍后重试" }
        ownsAudio = true
        val normalized = credentials.normalized()
        check(normalized.hasUsableAuth()) { "请先配置豆包语音凭据" }
        val audio = app.getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    fail("语音对话被来电或其他音频中断，请点麦克风继续")
                }
            }, main).build()
        check(audio.requestAudioFocus(focus!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "暂时无法使用声音，请结束其他通话后重试"
        }
        synchronized(environmentLock) {
            if (!environmentPrepared) {
                SpeechEngineGenerator.PrepareEnvironment(app, app as Application)
                environmentPrepared = true
            }
        }
        val sdk = SpeechEngineGenerator.getInstance()
        engine = sdk
        check(sdk.createEngine() != 0L) { "语音引擎创建失败" }
        sdk.setContext(app)
        sdk.setOptionString(D.PARAMS_KEY_ENGINE_NAME_STRING, D.DIALOG_ENGINE)
        sdk.setOptionString(D.PARAMS_KEY_DEBUG_PATH_STRING, "")
        sdk.setOptionString(D.PARAMS_KEY_LOG_LEVEL_STRING, D.LOG_LEVEL_ERROR)
        if (normalized.apiKey.isNotBlank()) {
            sdk.setOptionString(D.PARAMS_KEY_API_KEY_STRING, normalized.apiKey)
            sdk.setOptionString(D.PARAMS_KEY_REQUEST_HEADERS_STRING,
                JSONObject().put("x-api-key", normalized.apiKey).toString())
        } else {
            sdk.setOptionString(D.PARAMS_KEY_APP_ID_STRING, normalized.appKey)
            sdk.setOptionString(D.PARAMS_KEY_APP_TOKEN_STRING, normalized.accessKey)
            sdk.setOptionString(D.PARAMS_KEY_REQUEST_HEADERS_STRING, JSONObject()
                .put("X-Api-App-Key", normalized.appKey).put("X-Api-Access-Key", normalized.accessKey).toString())
        }
        sdk.setOptionString(D.PARAMS_KEY_RESOURCE_ID_STRING, "volc.speech.dialog")
        sdk.setOptionString(D.PARAMS_KEY_UID_STRING, "movo-voice")
        sdk.setOptionString(D.PARAMS_KEY_DIALOG_ADDRESS_STRING, "wss://openspeech.bytedance.com")
        sdk.setOptionString(D.PARAMS_KEY_DIALOG_URI_STRING, "/api/v3/realtime/dialogue")
        sdk.setOptionInt(D.PARAMS_KEY_DIALOG_WORK_MODE_INT, D.DIALOG_WORK_MODE_DELEGATE_CHAT_TTS_TEXT)
        input = sourceFactory?.invoke()
        sdk.setOptionString(D.PARAMS_KEY_RECORDER_TYPE_STRING,
            if (input == null) D.RECORDER_TYPE_RECORDER else D.RECORDER_TYPE_STREAM)
        sdk.setOptionBoolean(D.PARAMS_KEY_DIALOG_ENABLE_PLAYER_BOOL, true)
        sdk.setOptionBoolean(D.PARAMS_KEY_DIALOG_ENABLE_PLAYER_AUDIO_CALLBACK_BOOL, true)
        sdk.setOptionBoolean(D.PARAMS_KEY_DIALOG_ENABLE_RECORDER_AUDIO_CALLBACK_BOOL, true)
        sdk.setOptionBoolean(D.PARAMS_KEY_ENABLE_WS_RECONNECT_BOOL, false)
        sdk.setOptionInt(D.PARAMS_KEY_AUDIO_STREAM_TYPE_INT, AudioManager.STREAM_MUSIC)
        val model = File(app.filesDir, "voice-dialog/aec.model")
        model.parentFile?.mkdirs()
        app.assets.open("voice/aec.model").use { source -> model.outputStream().use(source::copyTo) }
        sdk.setOptionBoolean(D.PARAMS_KEY_ENABLE_AEC_BOOL, input == null)
        sdk.setOptionString(D.PARAMS_KEY_AEC_MODEL_PATH_STRING, model.absolutePath)
        sdk.setListener(object : SpeechEngine.SpeechListener {
            override fun onSpeechMessage(type: Int, data: ByteArray?, len: Int) {
                if (closed.get()) return
                val size = len.coerceIn(0, data?.size ?: 0)
                if (type == D.MESSAGE_TYPE_DIALOG_RECORDER_AUDIO) {
                    if (input == null && data != null) recordActivity(data, size)
                    return
                }
                if (type == D.MESSAGE_TYPE_DIALOG_PLAYER_AUDIO) {
                    // Copy only an amplitude fact out of the native callback, never retain user audio.
                    if (data != null && size > 0) playerObserver?.invoke(data.copyOf(size))
                    var peak = 0
                    if (data != null) for (i in 0 until size - 1 step 2) {
                        val sample = ((data[i].toInt() and 255) or (data[i + 1].toInt() shl 8)).toShort().toInt()
                        peak = maxOf(peak, abs(sample))
                    }
                    val audible = peak > 24
                    execute { playerAudio(audible) }
                    return
                }
                if (type == D.MESSAGE_TYPE_DIALOG_TTS_RESPONSE) return // binary audio, not JSON
                val payload = if (size == 0) "" else String(data!!, 0, size, Charsets.UTF_8)
                execute { event(type, payload) }
            }
        })
        check(sdk.initEngine() == D.ERR_NO_ERROR) { "豆包语音初始化失败" }
        directive(D.DIRECTIVE_SYNC_STOP_ENGINE, "")
        val parameters = JSONObject()
            .put("asr", JSONObject().put("extra", JSONObject()
                .put("enable_custom_vad", true).put("end_smooth_window_ms", 1000)))
            .put("tts", JSONObject().put("speaker", "zh_female_vv_jupiter_bigtts")
                .put("audio_config", JSONObject().put("channel", 1).put("format", "pcm_s16le").put("sample_rate", 24000)))
            .put("dialog", JSONObject().put("bot_name", "Movo")
                .put("extra", JSONObject().put("model", "1.2.1.1").put("input_mode", "keep_alive")))
        directive(D.DIRECTIVE_START_ENGINE, parameters.toString())
        log("session.starting", mapOf("input" to if (input == null) "microphone" else "instrumented_pcm"))
        worker.schedule({ if (!closed.get() && !ready) fail("豆包语音连接超时，请重试") }, 12, TimeUnit.SECONDS)
        worker.scheduleAtFixedRate({ if (!closed.get()) checkPlayback() }, 200, 200, TimeUnit.MILLISECONDS)
    }

    private fun event(type: Int, payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrElse { JSONObject() }
        when (type) {
            D.MESSAGE_TYPE_DIALOG_SESSION_STARTED -> if (!ready) {
                ready = true
                log("session.ready")
                input?.start { bytes -> execute {
                    recordActivity(bytes, bytes.size)
                    check(engine?.feedAudio(bytes, bytes.size) == 0) { "语音输入失败" }
                } }
                emit { onReady() }
            }
            D.MESSAGE_TYPE_DIALOG_ASR_INFO -> {
                val id = json.optString("question_id")
                if (id.isNotBlank() && id == questionId) return
                questionId = id
                turn++
                if (outputTurn != null && !playbackPaused) {
                    directive(D.DIRECTIVE_PAUSE_PLAYER, "")
                    playbackPaused = true
                    pausedAt = SystemClock.elapsedRealtime()
                }
                // Never select server-generated answers. Default cloud output stays behind this gate.
                directive(D.DIRECTIVE_DIALOG_USE_CLIENT_TRIGGER_TTS, "")
                log("input.started", mapOf("turn" to turn))
                val idForCallback = turn
                emit { onSpeechStarted(idForCallback) }
            }
            D.MESSAGE_TYPE_DIALOG_ASR_RESPONSE -> {
                val text = json.optJSONArray("results")?.optJSONObject(0)?.optString("text").orEmpty()
                val id = turn
                if (text.isNotBlank()) emit { onPartial(id, text) }
            }
            D.MESSAGE_TYPE_DIALOG_ASR_ENDED -> {
                log("input.ended", mapOf("turn" to turn, "empty" to json.optBoolean("no_content")))
                val id = turn
                emit { onSpeechEnded(id) }
            }
            D.MESSAGE_TYPE_DIALOG_TTS_SENTENCE_START -> {
                log("output.sentence.started", mapOf("turn" to turn,
                    "client_text" to (json.optString("tts_type") == "chat_tts_text"),
                    "current_question" to (json.optString("question_id") == questionId),
                    "requested" to (outputTurn != null)))
                if (outputTurn != null && json.optString("tts_type") == "chat_tts_text" &&
                    json.optString("question_id") == questionId) {
                    outputReplyId = json.optString("reply_id")
                }
            }
            D.MESSAGE_TYPE_DIALOG_TTS_SENTENCE_END -> {
                if (outputReplyId.isNotBlank() && json.optString("reply_id") == outputReplyId) {
                    expectedDurationMs = maxOf(expectedDurationMs,
                        ((json.optJSONObject("sentence_duration")?.optDouble("sentence_end_time", 0.0) ?: 0.0) * 1000).toLong())
                }
            }
            D.MESSAGE_TYPE_DIALOG_TTS_ENDED -> {
                log("output.synthesis.ended", mapOf("turn" to turn,
                    "owned_reply" to (outputReplyId.isNotBlank() && json.optString("reply_id") == outputReplyId)))
                if (outputReplyId.isNotBlank() && json.optString("reply_id") == outputReplyId) synthesisEnded = true
            }
            D.MESSAGE_TYPE_ENGINE_ERROR, D.MESSAGE_TYPE_DIALOG_SESSION_FAILED -> {
                // SDK payloads can contain headers or transcripts. Log only the event number.
                log("session.error", mapOf("sdk_event" to type))
                fail("豆包语音连接异常，请检查语音服务权限和网络后重试")
            }
            D.MESSAGE_TYPE_ENGINE_STOP -> if (ready && !closed.get()) fail("语音连接已结束，请点麦克风重新连接")
        }
    }

    private fun recordActivity(bytes: ByteArray, size: Int) {
        val now = SystemClock.elapsedRealtime()
        val active = synchronized(inputActivity) { inputActivity.accept(bytes, size, now) }
        if (active) emit { onInputActivity(now) }
    }

    fun speak(id: Long, text: String) = execute {
        if (id != turn || text.isBlank()) return@execute
        outputTurn = id
        outputReplyId = ""
        outputStarted = false
        synthesisEnded = false
        expectedDurationMs = 0
        outputRequestedAt = SystemClock.elapsedRealtime()
        if (playbackPaused) directive(D.DIRECTIVE_RESUME_PLAYER, "")
        playbackPaused = false
        // ASR_INFO already selected client TTS for this vendor round. Initial playback is not paused.
        // Bounded chunks keep a long answer off the JNI argument boundary without truncating it.
        val chunks = text.codePoints().toArray().toList().chunked(400).map { points ->
            String(points.toIntArray(), 0, points.size)
        }
        chunks.forEachIndexed { index, chunk ->
            directive(D.DIRECTIVE_DIALOG_CHAT_TTS_TEXT, JSONObject()
                .put("start", index == 0).put("content", chunk).put("end", false).toString())
        }
        directive(D.DIRECTIVE_DIALOG_CHAT_TTS_TEXT, JSONObject()
            .put("start", false).put("content", "").put("end", true).toString())
        log("output.requested", mapOf("turn" to id, "chars" to text.length))
    }

    fun pause() = execute { if (outputTurn != null && !playbackPaused) {
        playbackPaused = true; pausedAt = SystemClock.elapsedRealtime()
        directive(D.DIRECTIVE_PAUSE_PLAYER, "")
    } }
    fun resume() = execute { if (outputTurn != null && playbackPaused) {
        if (outputStarted) {
            val pausedFor = SystemClock.elapsedRealtime() - pausedAt
            outputStartedAt += pausedFor
            lastSoundAt += pausedFor
        }
        playbackPaused = false; directive(D.DIRECTIVE_RESUME_PLAYER, "")
    } }
    fun discard() = execute {
        if (outputTurn != null) {
            if (!playbackPaused) {
                directive(D.DIRECTIVE_PAUSE_PLAYER, "")
                playbackPaused = true
            }
            // ASR_INFO has already begun the replacement round and the SDK supersedes old audio.
            // ClientInterrupt is the push_to_talk protocol command, not a local queue flush:
            // sending it here can cancel the new round whose answer we still need to play.
            log("output.interrupted", mapOf("turn" to outputTurn))
        }
        outputTurn = null
        outputReplyId = ""
    }

    private fun playerAudio(audible: Boolean) {
        val id = outputTurn ?: return
        if (playbackPaused) return
        if (audible) {
            lastSoundAt = SystemClock.elapsedRealtime()
            if (!outputStarted) {
                outputStarted = true
                outputStartedAt = lastSoundAt
                log("output.audible", mapOf("turn" to id))
                emit { onPlaybackStarted(id) }
            }
        }
    }

    private fun checkPlayback() {
        val id = outputTurn ?: return
        if (playbackPaused) return
        val now = SystemClock.elapsedRealtime()
        // Dialog doesn't reliably emit the standalone-TTS player's finish event. Require the
        // matching reply's synthesis end, its full duration, and a quiet real-time player tail.
        if (outputStarted && synthesisEnded && expectedDurationMs > 0 &&
            now - outputStartedAt >= expectedDurationMs && now - lastSoundAt >= 500) {
            outputTurn = null
            log("output.drained", mapOf("turn" to id))
            emit { onPlaybackFinished(id) }
        } else if (!outputStarted && now - outputRequestedAt > 20_000) {
            outputTurn = null
            fail("没有收到可播放的语音，回答文字已保留，请重试语音连接")
        } else if (outputStarted && now - lastSoundAt > 20_000) {
            // Missing end/duration metadata must not leave the assistant stuck in SPEAKING forever.
            outputTurn = null
            fail("语音播放中断，回答文字已保留，请重新连接")
        }
    }

    fun close(onClosed: () -> Unit = {}) {
        val beginClose = synchronized(closeCallbacks) {
            if (fullyClosed) {
                main.post(onClosed)
                false
            } else {
                closeCallbacks += onClosed
                closed.compareAndSet(false, true)
            }
        }
        if (!beginClose) return
        worker.execute {
            runCatching { input?.close() }
            input = null
            runCatching { engine?.sendDirective(D.DIRECTIVE_SYNC_STOP_ENGINE, "") }
            runCatching { engine?.destroyEngine() }
            engine = null
            if (ownsAudio) { ownsAudio = false; audioInUse.set(false) }
            focus?.let { app.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
            focus = null
            log("session.closed")
            val callbacks = synchronized(closeCallbacks) {
                fullyClosed = true
                closeCallbacks.toList().also { closeCallbacks.clear() }
            }
            main.post { callbacks.forEach { it() } }
            worker.shutdown()
        }
    }

    private fun directive(code: Int, payload: String) {
        val result = engine?.sendDirective(code, payload)
        log("directive.completed", mapOf("directive" to code, "result" to result))
        check(result == D.ERR_NO_ERROR) { "语音指令执行失败，请重新连接" }
    }
    private fun execute(block: () -> Unit) {
        if (closed.get()) return
        runCatching { worker.execute { if (!closed.get()) runCatching(block).onFailure {
            fail(it.message?.takeIf { message -> message.startsWith("语音") || message.startsWith("豆包") || message.startsWith("请先") }
                ?: "语音连接异常，请重新连接")
        } } }
    }
    private fun fail(message: String) { emit { onError(message) } }
    private fun emit(block: Listener.() -> Unit) { main.post { if (!closed.get()) listener.block() } }
    private fun log(event: String, fields: Map<String, Any?> = emptyMap()) =
        MemoryDiagnostics.record("voice", event, fields = fields)

    @Keep
    companion object {
        private val audioInUse = AtomicBoolean(false)
        fun hasOpenAudio(): Boolean = audioInUse.get()
        private val environmentLock = Any()
        private var environmentPrepared = false
        @Keep @JvmStatic var playerObserver: ((ByteArray) -> Unit)? = null
        @Keep @JvmStatic var pcmInputFactory: (() -> PcmInput)? = null
    }
}
