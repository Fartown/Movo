package io.github.fartown.movo.tv

import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolAvailability
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.flavor.FlavorModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 结束语音对话（docs/solutions/tv-voice-app/语音对话退出方案.md §4.2–4.4）。 */
@RunWith(RobolectricTestRunner::class)
class TvEndCallTest {
    private val tool = TvEndCallTool()
    private val voice = ToolEnvironment(spokenReply = true)
    private val text = ToolEnvironment(spokenReply = false)

    @Test
    fun onlyVoiceRunsCanUseIt() {
        assertEquals(ToolAvailability.Available, tool.availability(voice))
        assertTrue(tool.availability(text) is ToolAvailability.Unavailable)
    }

    @Test
    fun describesOnlyTheContractAndRequiresTheFarewell() {
        assertTrue(tool.summary.length <= 160)
        assertFalse("描述只写契约，何时调用写在用法分节", "调用" in tool.summary)
        val schema = tool.schema(voice)
        assertEquals(TvEndCallTool.REPLY, schema.getJSONArray("required").getString(0))
        assertEquals(setOf(TvEndCallTool.REPLY), schema.getJSONObject("properties").keys().asSequence().toSet())
    }

    @Test
    fun endsTheRunWithTheFarewell() {
        assertTrue(TvEndCallTool.NAME in FlavorModule.finishingTools)
    }

    @Test
    fun failsWhenNoVoiceSessionIsActive() {
        assertFalse(VoiceSessionManager.active)
        val input = tool.parse(ToolArgs.parse("{}"), voice)
        val verdict = tool.execute(input, tool.resolve(input, voice), toolContext(voice))
        assertEquals(ToolErrorCode.NOT_FOUND, (verdict as Verdict.Failed).error.code)
    }

    private fun toolContext(env: ToolEnvironment) = io.github.fartown.movo.agent.tools.core.ToolContext(
        appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        logger = io.github.fartown.movo.core.AndroidAgentLogger,
        runId = "run-1",
        toolCallId = "call-1",
        env = env,
        interaction = io.github.fartown.movo.agent.tools.core.UserInteraction.NONE,
        cancelled = { false },
    )
}
