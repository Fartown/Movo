package io.github.fartown.movo.agent.model

import io.github.fartown.movo.agent.runtime.AgentRunCancelledException
import io.github.fartown.movo.agent.runtime.AgentRunController
import io.github.fartown.movo.data.model.ModelReasoningCapabilities
import io.github.fartown.movo.data.model.ReasoningEffort
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFollowUpSuggesterTest {
    @Test
    fun parsesPlainJsonArray() {
        assertEquals(
            listOf("设置取件提醒", "把取件码发给我自己"),
            AgentFollowUpSuggester.parse("""["设置取件提醒", "把取件码发给我自己"]"""),
        )
    }

    @Test
    fun parsesArrayWrappedInCodeFenceOrSurroundingText() {
        assertEquals(
            listOf("设置取件提醒", "打开菜鸟"),
            AgentFollowUpSuggester.parse("```json\n[\"设置取件提醒\", \"打开菜鸟\"]\n```"),
        )
        assertEquals(
            listOf("Set a pickup reminder", "Text me the code"),
            AgentFollowUpSuggester.parse("Here you go:\n[\"Set a pickup reminder\", \"Text me the code\"]\nThanks"),
        )
        assertEquals(
            listOf("设置取件提醒", "打开菜鸟"),
            AgentFollowUpSuggester.parse("""{"suggestions": ["设置取件提醒", "打开菜鸟"]}"""),
        )
    }

    @Test
    fun keepsAtMostThreeAfterDroppingInvalidEntries() {
        val result = AgentFollowUpSuggester.parse(
            """["设置取件提醒。", "", "  ", 42, null, "设置取件提醒", "第一行\n第二行",
                "这一条追问写得实在是太长太长了已经超过十六个汉字", "查一下我的快递",
                "把取件码发给我自己", "打开菜鸟", "明天提醒我取件"]""",
            userText = "查一下我的快递",
        )
        assertEquals(listOf("设置取件提醒", "把取件码发给我自己", "打开菜鸟"), result)
    }

    @Test
    fun widthLimitIsSixteenChineseCharactersOrThirtyTwoLatin() {
        val sixteen = "一二三四五六七八九十一二三四五六"
        val seventeen = sixteen + "七"
        val latin32 = "a".repeat(32)
        val latin33 = "a".repeat(33)
        assertEquals(
            listOf(sixteen, latin32),
            AgentFollowUpSuggester.parse("""["$sixteen", "$seventeen", "$latin32", "$latin33"]"""),
        )
    }

    @Test
    fun emptyOrMalformedOutputYieldsNothing() {
        listOf(
            "",
            "   ",
            "[]",
            "设置取件提醒",
            "[\"设置取件提醒\"",
            "] 设置取件提醒 [",
            "[{\"text\": \"设置取件提醒\"}]",
            "null",
        ).forEach { output ->
            assertEquals(output, emptyList<String>(), AgentFollowUpSuggester.parse(output))
        }
    }

    @Test
    fun skipsGreetingsButKeepsShortAnswersAfterRealWork() {
        assertFalse(AgentFollowUpSuggester.shouldSuggest("你好！有什么可以帮你？", usedTools = false))
        assertFalse(AgentFollowUpSuggester.shouldSuggest("   ", usedTools = true))
        assertTrue(AgentFollowUpSuggester.shouldSuggest("已打开蓝牙。", usedTools = true))
        assertTrue(AgentFollowUpSuggester.shouldSuggest("你的快递在菜鸟驿站，取件码 3-2-1106，今天 21:00 前可取。", usedTools = false))
    }

    @Test
    fun requestUsesLowestReasoningAndCarriesOnlyTheLatestTurn() {
        val base = config()
        assertEquals(base, AgentFollowUpSuggester.requestConfig(base))
        val canDisable = base.copy(
            reasoningEffort = ReasoningEffort.HIGH,
            reasoningCapabilities = ModelReasoningCapabilities(
                supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
                canDisable = true,
            ),
        )
        assertEquals(ReasoningEffort.OFF, AgentFollowUpSuggester.requestConfig(canDisable).reasoningEffort)
        val mandatory = canDisable.copy(reasoningCapabilities = canDisable.reasoningCapabilities!!.copy(mandatory = true))
        assertEquals(ReasoningEffort.LOW, AgentFollowUpSuggester.requestConfig(mandatory).reasoningEffort)

        val messages = AgentFollowUpSuggester.buildMessages("查一下我的快递", "x".repeat(5_000))
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        val user = messages.getJSONObject(1).getString("content")
        assertTrue(user.contains("查一下我的快递"))
        assertTrue(user.length < 2_300)
    }

    @Test
    fun suggestSendsToolFreeRequestAndParsesReply() = runBlocking {
        val captured = AtomicReference<ProviderRequest>()
        val provider = FakeProvider { request, _ ->
            captured.set(request)
            ProviderResponse(JSONObject().put("content", "[\"设置取件提醒\",\"把取件码发给我自己\"]").put("finish_reason", "stop"))
        }
        val result = AgentFollowUpSuggester.suggest(config(), "查一下我的快递", "取件码 3-2-1106", provider)
        assertEquals(listOf("设置取件提醒", "把取件码发给我自己"), result)
        assertEquals(ProviderRequestPurpose.FOLLOW_UP_SUGGESTIONS, captured.get().purpose)
        assertEquals(0, captured.get().effectiveTools.length())
    }

    @Test
    fun providerFailureIsSilent() = runBlocking {
        val provider = FakeProvider { _, _ -> throw AgentModelFailure("HTTP_500", false, "boom") }
        assertEquals(emptyList<String>(), AgentFollowUpSuggester.suggest(config(), "u", "a".repeat(40), provider))
    }

    @Test
    fun timeoutCancelsTheCallAndGivesUp() = runBlocking {
        val cancelled = CountDownLatch(1)
        val provider = FakeProvider { _, controller ->
            val binding = controller.register { cancelled.countDown() }
            try {
                // 模拟一直不返回的请求：只有被取消时才结束。
                if (!cancelled.await(5, TimeUnit.SECONDS)) error("request was never cancelled")
                throw AgentRunCancelledException()
            } finally {
                binding.close()
            }
        }
        val started = System.nanoTime()
        val result = AgentFollowUpSuggester.suggest(config(), "u", "a".repeat(40), provider, timeoutMs = 100)
        assertEquals(emptyList<String>(), result)
        assertEquals(0L, cancelled.count)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000)
    }

    private fun config() = AgentModelClient.ModelConfig(
        baseUrl = "http://127.0.0.1:1", apiKey = "fixture", model = "fixture", systemPrompt = "",
    )

    private class FakeProvider(
        private val block: (ProviderRequest, AgentRunController) -> ProviderResponse,
    ) : AgentProviderClient {
        override val id: String = "fake"
        override val capabilities = ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = false,
            imageInput = false,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false,
        )

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse = block(request, runController)
    }
}
