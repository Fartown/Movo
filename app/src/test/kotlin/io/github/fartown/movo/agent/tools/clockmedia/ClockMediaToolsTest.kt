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

    @Test
    fun clockCreate_notAttributed_returnsUnknown() {
        val backend = object : ClockCreateBackend {
            override fun createAndVerify(input: ClockCreateInput, env: ToolEnvironment) =
                ClockCreateResult.NotAttributed
        }
        val p = pipeline(provider(ContractTool(ClockCreateTool(backend))), ToolEnvironment())
        val result = p.execute(call("clock_create", """{"type":"timer","duration_seconds":300}"""))
        val json = JSONObject(result.content)
        assertEquals("unknown", json.getString("status"))
        assertEquals("OUTCOME_UNKNOWN", json.getString("code"))
        assertFalse(json.has("effect_verified"))
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

    @Test
    fun mediaControl_activeSession_returnsDispatchedUnverified() {
        val backend = object : MediaControlBackend {
            override fun sessionState(env: ToolEnvironment) = MediaSessionState.Active("com.example.player")
            override fun dispatch(action: MediaAction) = Unit
        }
        val p = pipeline(provider(ContractTool(MediaControlTool(backend))), ToolEnvironment())
        val json = JSONObject(p.execute(call("media_control", """{"action":"pause"}""")).content)
        assertEquals("ok", json.getString("status"))
        assertFalse(json.getBoolean("effect_verified")) // 送达型
        assertEquals("com.example.player", json.getJSONObject("data").getString("session"))
    }

    @Test
    fun mediaControl_noSessionPause_returnsNotFound() {
        val backend = object : MediaControlBackend {
            override fun sessionState(env: ToolEnvironment) = MediaSessionState.None
            override fun dispatch(action: MediaAction) = Unit
        }
        val p = pipeline(provider(ContractTool(MediaControlTool(backend))), ToolEnvironment())
        val json = JSONObject(p.execute(call("media_control", """{"action":"pause"}""")).content)
        assertEquals("error", json.getString("status"))
        assertEquals("NOT_FOUND", json.getString("code"))
    }

    @Test
    fun mediaControl_noSessionPlay_stillDispatches() {
        var dispatched = false
        val backend = object : MediaControlBackend {
            override fun sessionState(env: ToolEnvironment) = MediaSessionState.None
            override fun dispatch(action: MediaAction) { dispatched = true }
        }
        val p = pipeline(provider(ContractTool(MediaControlTool(backend))), ToolEnvironment())
        val json = JSONObject(p.execute(call("media_control", """{"action":"play"}""")).content)
        assertEquals("ok", json.getString("status"))
        assertTrue(dispatched)
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
