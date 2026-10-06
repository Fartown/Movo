package io.github.fartown.movo.agent.model

import io.github.fartown.movo.agent.runtime.AgentLegacyConversationProjection
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationCodecTest {
    @Test
    fun toolRoundTripPreservesReasoningContentForCompatibleProviders() {
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", JSONObject.NULL)
            .put("reasoning_content", "先分析工具参数")
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call-1")
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", "device_info")
                                .put("arguments", "{}")
                        )
                )
            )

        val durable = AgentConversationCodec.durableMessage(assistant)
        val replayed = AgentConversationCodec.toJsonObject(durable)

        assertEquals("先分析工具参数", replayed.getString("reasoning_content"))
        assertEquals("call-1", replayed.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
    }

    @Test
    fun durableImageObservationNeverPersistsBase64Payload() {
        val message = AgentConversationCodec.durableMessage(
            AgentConversationCodec.userMessage(
                text = "屏幕观察",
                images = listOf(
                    AgentModelClient.ModelImage(
                        reference = "data:image/png;base64,${"A".repeat(20_000)}",
                        mimeType = "image/png",
                        bytes = 15_000,
                    )
                ),
            )
        )

        assertFalse(message.contentJson.contains("base64"))
        assertTrue(message.contentJson.contains("未写入持久会话"))
    }

    @Test
    fun ipcTranscriptHasHardBudgetAndNeverStartsWithOrphanToolResult() {
        val messages = buildList {
            repeat(20) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "回答-$index-${"x".repeat(20_000)}",
                    )
                )
                add(
                    AgentModelClient.ConversationMessage(
                        role = "tool",
                        toolCallId = "call-$index",
                        content = "结果-${"y".repeat(20_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "assistant", content = "最终答案"))
        }

        val encoded = AgentLegacyConversationProjection.encode(messages, AgentLegacyConversationProjection.DIRECT_CHARS)
        val decoded = AgentConversationCodec.decodeTranscript(encoded)

        assertTrue(encoded.length <= AgentLegacyConversationProjection.DIRECT_CHARS)
        assertTrue(decoded.isNotEmpty())
        assertFalse(decoded.first().role == "tool")
        assertTrue(decoded.first().content.contains("容量上限已压缩"))
        assertTrue(decoded.last().content.contains("最终答案"))
    }

    @Test
    fun conversationCheckpointPreservesEveryMessageBeyondLegacyBudget() {
        val messages = buildList {
            repeat(20) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "回答-$index-${"x".repeat(20_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "user", content = "继续处理最新任务"))
        }

        val encoded = AgentConversationCodec.encodeConversationCheckpoint(messages)
        val decoded = AgentConversationCodec.decodeTranscript(encoded)

        assertTrue(encoded.length > 96_000)
        assertEquals(messages, decoded)
        assertEquals("继续处理最新任务", decoded.last().content)
    }

    /** 提示缓存方案第 3 版：原始输出项随历史存档，只有同一个服务商和模型才原样回放；含敏感调用的那一步不存。 */
    @Test
    fun responsesOutputItemsAreStoredAndReplayedOnlyForTheSameModel() {
        val origin = "openai_compatible|p1|https://example.invalid/v1|responses|m1"
        val items = JSONArray().put(JSONObject().put("type", "reasoning").put("encrypted_content", "opaque"))
        val source = JSONObject().put("role", "assistant").put("content", "完成")
        ResponsesEphemeralState.attachOutputItems(source, items, origin)
        val history = AgentConversationCodec.assistantHistoryMessage(source, emptyList())
        assertEquals(origin, ResponsesEphemeralState.outputOrigin(history))

        val stored = AgentConversationCodec.decodeTranscript(
            AgentConversationCodec.encodeTranscriptForStorage(listOf(AgentConversationCodec.durableMessage(history))),
        ).single()
        assertEquals(items.toString(), stored.responsesOutputJson)
        assertEquals(origin, stored.responsesOrigin)
        // 同一个服务商和模型：原样回放。
        assertEquals(items.toString(), ResponsesEphemeralState.outputItems(AgentConversationCodec.toJsonObject(stored, origin)).toString())
        // 换了模型、或调用方不关心（压缩、摘要）：不带，按普通助手消息重建。
        assertNull(ResponsesEphemeralState.outputItems(AgentConversationCodec.toJsonObject(stored, "$origin-other")))
        assertNull(ResponsesEphemeralState.outputItems(AgentConversationCodec.toJsonObject(stored)))
    }

    /** 不脱敏（和 Codex、Claude Code 一致）：读文件这类工具的参数、结果和原始输出项都原样存。 */
    @Test
    fun toolArgumentsResultsAndOutputItemsAreStoredVerbatim() {
        val call = JSONObject().put("role", "assistant").put("content", "").put(
            "tool_calls",
            JSONArray().put(
                JSONObject().put("id", "c1").put("type", "function")
                    .put("function", JSONObject().put("name", "file_read").put("arguments", "{\"path\":\"/sdcard/note.txt\"}")),
            ),
        )
        ResponsesEphemeralState.attachOutputItems(
            call,
            JSONArray().put(JSONObject().put("type", "function_call").put("call_id", "c1").put("arguments", "{\"path\":\"/sdcard/note.txt\"}")),
            "o",
        )
        val result = JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "买牛奶")

        val transcript = AgentConversationCodec.transcript(JSONArray().put(call).put(result), 0)
        val encoded = AgentConversationCodec.encodeTranscriptForStorage(transcript)
        assertTrue(encoded.contains("/sdcard/note.txt"))
        assertEquals("买牛奶", transcript[1].content)
        assertTrue(transcript[0].responsesOutputJson.contains("note.txt"))
    }
}
