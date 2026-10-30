package io.github.fartown.movo.ui.app

import io.github.fartown.movo.agent.model.AgentFileReference
import io.github.fartown.movo.agent.model.AgentFileReferenceKind
import io.github.fartown.movo.agent.model.AgentFileReferencePromptCodec
import io.github.fartown.movo.agent.model.InteractionMode
import io.github.fartown.movo.ui.model.AgentChatUiState
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.MonitorEventKindUi
import io.github.fartown.movo.ui.model.MonitorEventMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 重新生成前的确认（定稿 26-1）与「⋯」里换模式重做（定稿 26-2）的判断。 */
class RedoTurnRulesTest {
    private fun step(id: String, title: String, readOnly: Boolean?) = ToolActivityMessageUi(
        id = id, toolName = "ui_tap", status = ToolActivityStatusUi.Success, argumentsSummary = title, readOnly = readOnly,
    )

    private fun state(vararg messages: io.github.fartown.movo.ui.model.AgentChatMessageUi) =
        AgentChatUiState(messages = messages.toList(), input = "", isStreaming = false, thinkingEnabled = false)

    @Test
    fun onlyStepsThatAreNotReadOnlyInThisTurnCount() {
        val state = state(
            UserMessageUi(id = "u1", content = "给妈妈发微信，说周末回家吃饭"),
            step("t1", "看屏幕", readOnly = true),
            step("t2", "在「微信」里输入「周末回家吃饭」", readOnly = false),
            // 旧数据没记是否只读：宁可多问一次。
            step("t3", "点按「发送」", readOnly = null),
            AgentMessageUi(id = "a1", content = "发好了。"),
            UserMessageUi(id = "u2", content = "再设个明早 7 点的闹钟"),
            step("t4", "新建闹钟 7:00", readOnly = false),
            AgentMessageUi(id = "a2", content = "设好了。"),
        )
        assertEquals(listOf("t2", "t3"), AgentConversationRevisionReducer.deviceOperations(state, "a1").map { it.id })
        assertEquals(listOf("t4"), AgentConversationRevisionReducer.deviceOperations(state, "a2").map { it.id })
    }

    @Test
    fun aTurnThatOnlyLookedDoesNotAsk() {
        val state = state(
            UserMessageUi(id = "u1", content = "这页在讲什么？"),
            step("t1", "看屏幕", readOnly = true),
            AgentMessageUi(id = "a1", content = "在讲……"),
        )
        assertTrue(AgentConversationRevisionReducer.deviceOperations(state, "a1").isEmpty())
    }

    @Test
    fun redoOffersTheOtherModeExceptForEventTurnsAndFilesInChat() {
        val withFile = AgentFileReferencePromptCodec.format(
            "总结一下",
            listOf(AgentFileReference(displayName = "a.pdf", absolutePath = "/sdcard/Download/a.pdf", kind = AgentFileReferenceKind.File)),
        )
        val targets = AgentConversationRevisionReducer.redoModeTargets(listOf(
            UserMessageUi(id = "u1", content = "快递柜超时一般怎么收费？", interactionMode = InteractionMode.CHAT),
            AgentMessageUi(id = "a1", content = "多数快递柜……"),
            UserMessageUi(id = "u2", content = "明早 7 点叫我起床"),
            AgentMessageUi(id = "a2", content = "好了。"),
            UserMessageUi(id = "u3", content = withFile),
            AgentMessageUi(id = "a3", content = "这份文件讲的是……"),
            MonitorEventMessageUi(
                id = "m1", taskId = "task", name = "喝水提醒", kind = MonitorEventKindUi.Event, seq = 1, atMillis = 1L,
                text = "到点了", startsTurn = true,
            ),
            AgentMessageUi(id = "a4", content = "该喝水了。"),
        ))
        assertEquals(mapOf("a1" to InteractionMode.AGENT, "a2" to InteractionMode.CHAT), targets)
    }
}
