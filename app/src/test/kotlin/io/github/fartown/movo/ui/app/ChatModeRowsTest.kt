package io.github.fartown.movo.ui.app

import io.github.fartown.movo.agent.model.InteractionMode
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Test

/** 对话模式发出的用户消息借工具列记模式（定稿 25，不改表结构）。 */
class ChatModeRowsTest {
    @Test
    fun userMessageModeSurvivesTheRow() {
        val chat = UserMessageUi(id = "user-1", content = "快递柜超时怎么收费？", interactionMode = InteractionMode.CHAT)
        val chatRow = chat.toConversationRow("c1", 1024)!!
        assertEquals(USER_CHAT_MODE_EXTRA, chatRow.toolsJson)
        assertEquals(InteractionMode.CHAT, (chatRow.toChatMessage() as UserMessageUi).interactionMode)

        val agentRow = UserMessageUi(id = "user-2", content = "帮我设个提醒").toConversationRow("c1", 2048)!!
        assertEquals("[]", agentRow.toolsJson)
        assertEquals(InteractionMode.AGENT, (agentRow.toChatMessage() as UserMessageUi).interactionMode)
    }

    @Test
    fun webSearchSwitchSurvivesTheRowOnlyInChatMode() {
        val web = UserMessageUi(id = "user-3", content = "今天杭州天气", interactionMode = InteractionMode.CHAT, webSearch = true)
        val webRow = web.toConversationRow("c1", 3072)!!
        assertEquals(USER_CHAT_MODE_WEB_EXTRA, webRow.toolsJson)
        val restored = webRow.toChatMessage() as UserMessageUi
        assertEquals(InteractionMode.CHAT, restored.interactionMode)
        assertEquals(true, restored.webSearch)

        // Agent 模式不记联网（Agent 用服务商自己的联网设置）。
        val agentRow = UserMessageUi(id = "user-4", content = "查天气", webSearch = true).toConversationRow("c1", 4096)!!
        assertEquals("[]", agentRow.toolsJson)
        assertEquals(false, (agentRow.toChatMessage() as UserMessageUi).webSearch)
    }

    @Test
    fun stepReadOnlyFlagSurvivesTheRow() {
        // 步骤行借工具列记是否只读（定稿 26-1，不改表结构）；旧数据没有记录时读回 null。
        listOf(true, false, null).forEachIndexed { index, readOnly ->
            val step = ToolActivityMessageUi(
                id = "tool-$index", toolName = "ui_tap", status = ToolActivityStatusUi.Success,
                argumentsSummary = "点按「发送」", readOnly = readOnly,
            )
            val restored = step.toConversationRow("c1", index.toLong())!!.toChatMessage() as ToolActivityMessageUi
            assertEquals(readOnly, restored.readOnly)
        }
    }
}
