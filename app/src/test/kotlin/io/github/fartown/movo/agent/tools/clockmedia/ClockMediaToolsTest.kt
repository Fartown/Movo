package io.github.fartown.movo.agent.tools.clockmedia

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolPipeline
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.agent.tools.core.UserInteraction
import io.github.fartown.movo.core.AndroidAgentLogger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 时钟与音频领域四工具：用假后端经 ToolPipeline 跑通各 Verdict 分支。 */
@RunWith(RobolectricTestRunner::class)
class ClockMediaToolsTest {

    private fun pipeline(provider: ToolProvider, env: ToolEnvironment) = ToolPipeline(
        registry = ToolRegistry(listOf(provider)),
        environment = { env },
        appContext = ApplicationProvider.getApplicationContext(),
        logger = AndroidAgentLogger,
        runId = "run1",
        cancelled = { false },
        interaction = UserInteraction.NONE,
    ).also { it.catalog() }

    private fun call(name: String, args: String) = AgentModelClient.ToolCall("c1", name, args)

    private fun provider(vararg contracts: io.github.fartown.movo.agent.tools.core.AgentTool) =
        object : ToolProvider {
            override val tools = contracts.toList()
        }

    // ---- clock_create ----

    @Test
    fun clockCreate_attributed_returnsVerifiedDone() {
        val backend = object : ClockCreateBackend {
            override fun createAndVerify(input: ClockCreateInput, env: ToolEnvironment) =
                ClockCreateResult.Attributed(1_700_000_000_000L, "07:30")
        }
        val p = pipeline(provider(ContractTool(ClockCreateTool(backend))), ToolEnvironment())
        val json = JSONObject(p.execute(call("clock_create", """{"type":"alarm","hour":7,"minute":30}""")).content)
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        assertTrue(json.getJSONObject("data").has("matched_trigger_at"))
    }

    /**
     * 已有更早的闹钟时新闹钟成不了「下一个」，无 Root 核实不到：按已交给时钟应用报（effect_verified=false），
     * 说明里写清只是已提交；没有 clock_read 时不叫模型去调它。
     */
    @Test
    fun clockCreate_notAttributed_isDispatchedAndSaysSoHonestly() {
        val backend = object : ClockCreateBackend {
            override fun createAndVerify(input: ClockCreateInput, env: ToolEnvironment) =
                ClockCreateResult.NotAttributed
        }
        val noRoot = pipeline(provider(ContractTool(ClockCreateTool(backend))), ToolEnvironment())
        val result = noRoot.execute(call("clock_create", """{"type":"alarm","hour":7,"minute":30}"""))
        val json = JSONObject(result.content)
        assertEquals("ok", json.getString("status"))
        assertFalse(json.getBoolean("effect_verified"))
        val note = json.getJSONObject("data").getString("note")
        assertTrue(note, note.contains("没核实到") && note.contains("不要重复创建") && note.contains("在时钟里看一眼"))
        assertFalse(note, note.contains("clock_read"))
        assertEquals("已交给时钟，没能核实", result.outcome!!.view!!.summary)

        val root = pipeline(provider(ContractTool(ClockCreateTool(backend))), ToolEnvironment(rootAvailable = true))
        val rootNote = JSONObject(root.execute(call("clock_create", """{"type":"timer","duration_seconds":300}""")).content)
            .getJSONObject("data").getString("note")
        assertTrue(rootNote, rootNote.contains("clock_read"))
    }

    @Test
    fun clockCreate_launchFailed_isNotReportedAsNoClockApp() {
        fun run(pageOpened: Boolean): JSONObject {
            val backend = object : ClockCreateBackend {
                override fun createAndVerify(input: ClockCreateInput, env: ToolEnvironment) =
                    ClockCreateResult.LaunchFailed(clockPageOpened = pageOpened)
            }
            val p = pipeline(provider(ContractTool(ClockCreateTool(backend))), ToolEnvironment())
            return JSONObject(p.execute(call("clock_create", """{"type":"alarm","hour":7,"minute":30}""")).content)
        }
        val opened = run(pageOpened = true)
        assertEquals("SYSTEM_REJECTED", opened.getString("code"))
        assertEquals("user", opened.getString("retry"))
        assertTrue(opened.getString("message").contains("已打开时钟的闹钟页"))
        assertTrue(opened.getString("hint"), opened.getString("hint").contains("07:30 的闹钟"))
        val blocked = run(pageOpened = false)
        assertEquals("SYSTEM_REJECTED", blocked.getString("code"))
        assertTrue(blocked.getString("message").contains("时钟应用没能启动"))
        assertFalse(blocked.getString("message").contains("没有可处理"))
        assertTrue(blocked.getString("hint").contains("后台弹出界面"))
    }

    @Test
    fun clockPrompt_mentionsClockReadOnlyWhenItCanBeUsed() {
        val provider = ClockMediaToolProvider(
            ApplicationProvider.getApplicationContext(), AndroidAgentLogger,
            io.github.fartown.movo.agent.device.BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { false }),
            rootAvailable = { false },
        )
        val noRoot = provider.promptSection(ToolEnvironment())!!.text
        assertFalse(noRoot, noRoot.contains("clock_read"))
        assertTrue(noRoot.contains("effect_verified=true"))
        assertTrue(provider.promptSection(ToolEnvironment(rootAvailable = true))!!.text.contains("clock_read"))
        // 有 Root 但关了「读取敏感信息」：clock_read 不在目录里，提示也不提它。
        val switchedOff = ToolEnvironment(
            rootAvailable = true,
            switches = io.github.fartown.movo.agent.tools.core.ToolSwitches(sensitiveRead = false),
        )
        assertFalse(provider.promptSection(switchedOff)!!.text.contains("clock_read"))
        assertFalse(clockReadUsable(switchedOff))
        assertTrue(clockReadUsable(ToolEnvironment(rootAvailable = true)))
    }

    @Test
    fun clockCreate_noClockApp_returnsUnsupported() {
        val backend = object : ClockCreateBackend {
            override fun createAndVerify(input: ClockCreateInput, env: ToolEnvironment) =
                ClockCreateResult.NoClockApp
        }
        val p = pipeline(provider(ContractTool(ClockCreateTool(backend))), ToolEnvironment())
        val json = JSONObject(p.execute(call("clock_create", """{"type":"alarm","hour":8,"minute":0}""")).content)
        assertEquals("error", json.getString("status"))
        assertEquals("UNSUPPORTED", json.getString("code"))
    }

    @Test
    fun clockCreate_alarmMissingMinute_invalidArgs() {
        // parse 失败走 fail-closed（经管线会被当成需确认而非 INVALID_ARGUMENTS），所以直接验 parse。
        val backend = object : ClockCreateBackend {
            override fun createAndVerify(input: ClockCreateInput, env: ToolEnvironment) = ClockCreateResult.NotAttributed
        }
        val tool = ClockCreateTool(backend)
        val failure = runCatching {
            tool.parse(io.github.fartown.movo.agent.tools.core.ToolArgs.parse("""{"type":"alarm","hour":7}"""), ToolEnvironment())
        }.exceptionOrNull()
        assertTrue(failure is io.github.fartown.movo.agent.tools.core.ToolFailure)
        assertEquals(
            io.github.fartown.movo.agent.tools.core.ToolErrorCode.INVALID_ARGUMENTS,
            (failure as io.github.fartown.movo.agent.tools.core.ToolFailure).code,
        )
    }

    // ---- clock_read ----

    @Test
    fun clockRead_withoutRoot_returnsRootRequired() {
        val backend = object : ClockReadBackend {
            override fun read(type: ClockType?, enabledOnly: Boolean, limit: Int, env: ToolEnvironment) =
                ClockReadResult.Items(JSONArray())
        }
        val p = pipeline(provider(ContractTool(ClockReadTool(backend))), ToolEnvironment(rootAvailable = false))
        val json = JSONObject(p.execute(call("clock_read", "{}")).content)
        assertEquals("error", json.getString("status"))
        assertEquals("ROOT_REQUIRED", json.getString("code"))
    }

    @Test
    fun clockRead_withRoot_returnsReadAndSensitive() {
        val backend = object : ClockReadBackend {
            override fun read(type: ClockType?, enabledOnly: Boolean, limit: Int, env: ToolEnvironment) =
                ClockReadResult.Items(JSONArray().put(JSONObject().put("hour", 7).put("kind", "alarm")))
        }
        val p = pipeline(provider(ContractTool(ClockReadTool(backend))), ToolEnvironment(rootAvailable = true))
        val result = p.execute(call("clock_read", """{"type":"alarm"}"""))
        val json = JSONObject(result.content)
        assertEquals("ok", json.getString("status"))
        assertFalse(json.has("effect_verified")) // 只读不带 effect_verified
        assertTrue(result.sensitive) // PRIVATE
        assertEquals(1, json.getJSONObject("data").getJSONArray("items").length())
    }

    // ---- media_control ----

    /**
     * 假后端：[states] 依次作为每次观察的会话状态（派发前一次，之后每次回读一次，用完停在最后一个），
     * [audio] 同理给媒体声音。
     */
    private class FakeMedia(
        private val states: List<MediaSessionState>,
        private val audio: List<Boolean?> = listOf(null),
    ) : MediaControlBackend {
        val dispatched = mutableListOf<MediaAction>()
        private var sessionReads = 0
        private var audioReads = 0
        override fun sessionState(env: ToolEnvironment) = states[minOf(sessionReads++, states.lastIndex)]
        override fun musicActive() = audio[minOf(audioReads++, audio.lastIndex)]
        override fun dispatch(action: MediaAction) { dispatched += action }
    }

    private fun media(backend: MediaControlBackend, env: ToolEnvironment = ToolEnvironment()) =
        pipeline(provider(ContractTool(MediaControlTool(backend, readBackTimeoutMs = 50, pollIntervalMs = 1))), env)

    private fun runMedia(backend: MediaControlBackend, action: String): JSONObject =
        JSONObject(media(backend).execute(call("media_control", """{"action":"$action"}""")).content)

    @Test
    fun mediaControl_pausePlayingSession_readBackConfirms() {
        val backend = FakeMedia(
            listOf(
                MediaSessionState.Active("com.example.player", playing = true),
                MediaSessionState.Active("com.example.player", playing = false),
            ),
        )
        val json = runMedia(backend, "pause")
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        val data = json.getJSONObject("data")
        assertEquals("com.example.player", data.getString("session"))
        assertFalse(data.getBoolean("playing"))
        assertEquals("media_session", data.getString("confirmed_by"))
        assertEquals(listOf(MediaAction.PAUSE), backend.dispatched)
    }

    @Test
    fun mediaControl_pauseButStillPlaying_isUnknown() {
        val backend = FakeMedia(listOf(MediaSessionState.Active("com.example.player", playing = true)))
        val json = runMedia(backend, "pause")
        assertEquals("unknown", json.getString("status"))
        assertEquals("OUTCOME_UNKNOWN", json.getString("code"))
        assertTrue(json.getString("message").contains("回读仍在播放"))
    }

    @Test
    fun mediaControl_noSessionPause_returnsNotFoundWithoutDispatch() {
        val backend = FakeMedia(listOf(MediaSessionState.None), audio = listOf(false))
        val json = runMedia(backend, "pause")
        assertEquals("error", json.getString("status"))
        assertEquals("NOT_FOUND", json.getString("code"))
        assertEquals("never", json.getString("retry"))
        assertTrue(json.getString("message").contains("现在没有正在播放的内容"))
        assertTrue(backend.dispatched.isEmpty())
    }

    /** 真机复现：没通知使用权、没 Root 看不到会话，也没有声音在响——pause 以前回 ok/dispatched，模型说「已停住」。 */
    @Test
    fun mediaControl_sessionsHiddenAndSilent_pauseFailsClearly() {
        for (action in listOf("pause", "stop", "next", "previous", "fast_forward", "rewind")) {
            val backend = FakeMedia(listOf(MediaSessionState.Unknown), audio = listOf(false))
            val json = runMedia(backend, action)
            assertEquals(action, "error", json.getString("status"))
            assertEquals(action, "NOT_FOUND", json.getString("code"))
            assertEquals(action, "no_music_active", json.getString("detail"))
            assertTrue(action, backend.dispatched.isEmpty())
        }
    }

    @Test
    fun mediaControl_sessionsHiddenButAudioPlaying_pauseVerifiedByAudio() {
        val backend = FakeMedia(listOf(MediaSessionState.Unknown), audio = listOf(true, false))
        val json = runMedia(backend, "pause")
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        assertEquals("audio", json.getJSONObject("data").getString("confirmed_by"))
    }

    /** 什么都读不到：照常派发，但只算送达（effect_verified=false），不冒领。 */
    @Test
    fun mediaControl_nothingReadable_isDispatchedUnverified() {
        val backend = FakeMedia(listOf(MediaSessionState.Unknown), audio = listOf(null))
        val json = runMedia(backend, "pause")
        assertEquals("ok", json.getString("status"))
        assertFalse(json.getBoolean("effect_verified"))
        assertTrue(json.getJSONObject("data").getString("note").contains("没确认"))
        assertEquals(listOf(MediaAction.PAUSE), backend.dispatched)
    }

    @Test
    fun mediaControl_audioWithoutSession_isNotActionable() {
        val backend = FakeMedia(listOf(MediaSessionState.None), audio = listOf(true))
        val json = runMedia(backend, "pause")
        assertEquals("NOT_ACTIONABLE", json.getString("code"))
        assertTrue(backend.dispatched.isEmpty())
    }

    @Test
    fun mediaControl_pauseAlreadyPaused_returnsNothingPlaying() {
        val backend = FakeMedia(listOf(MediaSessionState.Active("com.example.player", playing = false)), audio = listOf(false))
        val json = runMedia(backend, "pause")
        assertEquals("NOT_FOUND", json.getString("code"))
        assertEquals("already_paused", json.getString("detail"))
        assertTrue(backend.dispatched.isEmpty())
    }

    @Test
    fun mediaControl_noSessionPlay_dispatchesAndConfirmsWhenPlaybackStarts() {
        val backend = FakeMedia(
            listOf(MediaSessionState.None, MediaSessionState.Active("com.example.player", playing = true)),
        )
        val json = runMedia(backend, "play")
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        assertEquals(listOf(MediaAction.PLAY), backend.dispatched)
    }

    @Test
    fun mediaControl_playNeverStarts_isUnknown() {
        val backend = FakeMedia(listOf(MediaSessionState.None), audio = listOf(false))
        val json = runMedia(backend, "play")
        assertEquals("unknown", json.getString("status"))
        assertTrue(json.getString("message").contains("没检测到开始播放"))
        assertEquals(listOf(MediaAction.PLAY), backend.dispatched)
    }

    /** 审计 C6：补上 stop。 */
    @Test
    fun mediaControl_stop_inSchemaAndVerifiedWhenSessionGone() {
        val tool = MediaControlTool(FakeMedia(listOf(MediaSessionState.None)))
        val actions = tool.schema(ToolEnvironment()).getJSONObject("properties").getJSONObject("action").getJSONArray("enum")
        assertTrue((0 until actions.length()).any { actions.getString(it) == "stop" })
        val backend = FakeMedia(listOf(MediaSessionState.Active("com.example.player", playing = true), MediaSessionState.None))
        val json = runMedia(backend, "stop")
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        assertEquals(listOf(MediaAction.STOP), backend.dispatched)
    }

    @Test
    fun mediaControl_next_trackChangeConfirms_otherwiseOnlyDispatched() {
        val changed = FakeMedia(
            listOf(
                MediaSessionState.Active("com.example.player", playing = true, track = "歌 A"),
                MediaSessionState.Active("com.example.player", playing = true, track = "歌 B"),
            ),
        )
        val json = runMedia(changed, "next")
        assertTrue(json.getBoolean("effect_verified"))
        assertEquals("track", json.getJSONObject("data").getString("confirmed_by"))
        // 没有曲目信息（不少视频应用不报）：派发了，但只算送达。
        val noTrack = FakeMedia(listOf(MediaSessionState.Active("com.example.video", playing = true)))
        val unverified = runMedia(noTrack, "next")
        assertEquals("ok", unverified.getString("status"))
        assertFalse(unverified.getBoolean("effect_verified"))
        // 暂停中的播放器也能切歌：不拦。
        val paused = FakeMedia(listOf(MediaSessionState.Active("com.example.player", playing = false)), audio = listOf(false))
        runMedia(paused, "next")
        assertEquals(listOf(MediaAction.NEXT), paused.dispatched)
    }

    @Test
    fun mediaControl_toggle_expectsOppositeOfBefore() {
        val backend = FakeMedia(
            listOf(
                MediaSessionState.Active("com.example.player", playing = false),
                MediaSessionState.Active("com.example.player", playing = true),
            ),
        )
        val json = runMedia(backend, "toggle")
        assertTrue(json.getBoolean("effect_verified"))
        assertTrue(json.getJSONObject("data").getBoolean("playing"))
    }

    // ---- media_control 真实后端（Robolectric 系统服务） ----

    @Test
    fun realMediaBackend_noNotificationAccess_fallsBackToAudioSignal() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val audio = org.robolectric.Shadows.shadowOf(context.getSystemService(android.media.AudioManager::class.java))
        val backend = RealMediaControlBackend(context, noRootExecutor())
        assertEquals(MediaSessionState.Unknown, backend.sessionState(ToolEnvironment(notificationAccess = false)))
        audio.setIsMusicActive(false)
        assertEquals(false, backend.musicActive())
        // 经工具：看不到会话、没声音 → 清楚报错，不派发媒体键。
        val json = JSONObject(
            media(backend, ToolEnvironment(notificationAccess = false))
                .execute(call("media_control", """{"action":"pause"}""")).content,
        )
        assertEquals("NOT_FOUND", json.getString("code"))
        assertTrue(audio.dispatchedMediaKeyEvents.isEmpty())
        // stop 派发 KEYCODE_MEDIA_STOP。
        backend.dispatch(MediaAction.STOP)
        assertEquals(android.view.KeyEvent.KEYCODE_MEDIA_STOP, audio.dispatchedMediaKeyEvents.first().keyCode)
    }

    @Test
    fun realMediaBackend_readsSessionStateAndTrack() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val session = android.media.session.MediaSession(context, "test")
        val controller = android.media.session.MediaController(context, session.sessionToken)
        org.robolectric.Shadows.shadowOf(controller).apply {
            setPackageName("com.example.player")
            setPlaybackState(
                android.media.session.PlaybackState.Builder()
                    .setState(android.media.session.PlaybackState.STATE_PAUSED, 0L, 1f)
                    .build(),
            )
            setMetadata(
                android.media.MediaMetadata.Builder()
                    .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "晴天")
                    .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "周杰伦")
                    .build(),
            )
        }
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.media.session.MediaSessionManager::class.java))
            .addController(controller)
        val state = RealMediaControlBackend(context, noRootExecutor()).sessionState(ToolEnvironment(notificationAccess = true))
        assertEquals(MediaSessionState.Active("com.example.player", playing = false, track = "晴天 · 周杰伦"), state)
        session.release()
    }

    @Test
    fun mediaPlayback_stateMapping() {
        assertEquals(true, MediaPlayback.isPlaying(android.media.session.PlaybackState.STATE_PLAYING))
        assertEquals(true, MediaPlayback.isPlaying(android.media.session.PlaybackState.STATE_BUFFERING))
        assertEquals(false, MediaPlayback.isPlaying(android.media.session.PlaybackState.STATE_PAUSED))
        assertEquals(false, MediaPlayback.isPlaying(android.media.session.PlaybackState.STATE_STOPPED))
        assertEquals(null, MediaPlayback.isPlaying(null))
    }

    private fun noRootExecutor() =
        io.github.fartown.movo.agent.device.BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { false })

    // ---- media_control：媒体会话版（没授权时交给媒体键工具） ----

    private fun sessionTool(backend: MediaControlBackend?) = MediaSessionControlTool(
        ApplicationProvider.getApplicationContext(),
        { listOf(android.content.ComponentName("io.github.fartown.movo", "io.github.fartown.movo.agent.device.MediaAccessService")) },
        backend?.let { MediaControlTool(it, readBackTimeoutMs = 50, pollIntervalMs = 1) },
    )

    private fun runSession(backend: MediaControlBackend?, args: String): JSONObject =
        JSONObject(pipeline(provider(ContractTool(sessionTool(backend))), ToolEnvironment()).execute(call("media_control", args)).content)

    @Test
    fun mediaSession_withoutAccess_pauseGoesThroughTheKeyToolAndItsReadBack() {
        // 没授权「播放控制」：暂停交给媒体键工具，按会话 / 媒体声音回读确认。
        val backend = FakeMedia(
            listOf(MediaSessionState.Unknown),
            audio = listOf(true, false),
        )
        val json = runSession(backend, """{"action":"pause"}""")
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        assertEquals(listOf(MediaAction.PAUSE), backend.dispatched)
    }

    @Test
    fun mediaSession_withoutAccess_nextIsNotSentBlind() {
        // 上下集不盲发：确认不了是否生效，模型再去界面补一次就会多跳一集（真机 10-07）。
        val backend = FakeMedia(listOf(MediaSessionState.Unknown), audio = listOf(true))
        val json = runSession(backend, """{"action":"next"}""")
        assertEquals("PERMISSION_REQUIRED", json.getString("code"))
        assertTrue(backend.dispatched.isEmpty())
    }

    @Test
    fun mediaSession_withoutAccess_seekAsksForThePermission() {
        val json = runSession(null, """{"action":"seek","position_s":300}""")
        assertEquals("error", json.getString("status"))
        assertEquals("PERMISSION_REQUIRED", json.getString("code"))
    }

    @Test
    fun mediaSession_seekNeedsAPosition() {
        val json = runSession(null, """{"action":"seek"}""")
        assertEquals("error", json.getString("status"))
        assertTrue(json.toString().contains("position_s"))
    }

    // ---- volume_set ----

    @Test
    fun volumeSet_consistent_returnsDoneNoWarning() {
        val backend = object : VolumeSetBackend {
            override fun setAndReadBack(stream: VolumeStream, percent: Int) =
                VolumeReadBack(percent, percent, level = 5, maxLevel = 10, minLevel = 0)
        }
        val p = pipeline(provider(ContractTool(VolumeSetTool(backend))), ToolEnvironment())
        val json = JSONObject(p.execute(call("volume_set", """{"stream":"music","percent":50}""")).content)
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        assertFalse(json.has("warnings"))
        assertEquals(50, json.getJSONObject("data").getInt("actual_percent"))
    }

    @Test
    fun volumeSet_clamped_returnsDoneWithWarning() {
        // 请求 0%，但系统钳制到 20%（level 2，下限为 0）→ 不是达到下限，视为钳制，告警。
        val backend = object : VolumeSetBackend {
            override fun setAndReadBack(stream: VolumeStream, percent: Int) =
                VolumeReadBack(percent, actualPercent = 20, level = 2, maxLevel = 10, minLevel = 0)
        }
        val p = pipeline(provider(ContractTool(VolumeSetTool(backend))), ToolEnvironment())
        val json = JSONObject(p.execute(call("volume_set", """{"stream":"ring","percent":0}""")).content)
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        assertTrue(json.has("warnings"))
        assertEquals("CONFLICT", json.getJSONArray("warnings").getJSONObject(0).getString("code"))
    }

    @Test
    fun volumeSet_requestZeroAtFloor_countsAchievedNoWarning() {
        // 请求 0%，但系统下限为 2（level==minLevel）→ 仍算达成，不告警。
        val backend = object : VolumeSetBackend {
            override fun setAndReadBack(stream: VolumeStream, percent: Int) =
                VolumeReadBack(percent, actualPercent = 0, level = 2, maxLevel = 10, minLevel = 2)
        }
        val p = pipeline(provider(ContractTool(VolumeSetTool(backend))), ToolEnvironment())
        val json = JSONObject(p.execute(call("volume_set", """{"stream":"alarm","percent":0}""")).content)
        assertEquals("ok", json.getString("status"))
        assertFalse(json.has("warnings"))
    }

    @Test
    fun volumeSet_rejected_returnsSystemRejected() {
        val backend = object : VolumeSetBackend {
            override fun setAndReadBack(stream: VolumeStream, percent: Int): VolumeReadBack =
                throw VolumeSetBackend.VolumeRejected("dnd")
        }
        val p = pipeline(provider(ContractTool(VolumeSetTool(backend))), ToolEnvironment())
        val json = JSONObject(p.execute(call("volume_set", """{"stream":"ring","percent":0}""")).content)
        assertEquals("error", json.getString("status"))
        assertEquals("SYSTEM_REJECTED", json.getString("code"))
    }
}
