package io.github.fartown.movo.ui.components

import io.github.fartown.movo.agent.model.InteractionMode
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Test

/** 切换处的说明行只出现在模式跟上一条用户消息不同的那条上（定稿 25-4 / 25-5）。 */
class ModeSwitchesTest {
    @Test
    fun onlyTheFirstMessageAfterASwitchGetsTheRow() {
        val messages = listOf(
            UserMessageUi("u1", "查快递"),
            AgentMessageUi("a1", "有 2 条"),
            UserMessageUi("u2", "超时怎么收费？", interactionMode = InteractionMode.CHAT),
            AgentMessageUi("a2", "按时段"),
            UserMessageUi("u3", "那最多收多少？", interactionMode = InteractionMode.CHAT),
            UserMessageUi("u4", "帮我设个提醒"),
        )
        assertEquals(mapOf("u2" to InteractionMode.CHAT, "u4" to InteractionMode.AGENT), modeSwitchesOf(messages))
    }

    @Test
    fun aConversationThatStartsInChatModeHasNoRow() {
        val messages = listOf(UserMessageUi("u1", "你好", interactionMode = InteractionMode.CHAT), AgentMessageUi("a1", "你好"))
        assertEquals(emptyMap<String, InteractionMode>(), modeSwitchesOf(messages))
    }
}
