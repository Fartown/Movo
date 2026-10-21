package io.github.fartown.movo.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.agent.model.InteractionMode
import io.github.fartown.movo.data.model.AppearanceSettings
import io.github.fartown.movo.data.model.ReasoningEffort
import io.github.fartown.movo.ui.app.AgentAppTheme
import io.github.fartown.movo.ui.model.AgentContextUsageUi
import io.github.fartown.movo.ui.model.AgentModelPickerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 真机回归（10-21，小米 360dp 宽）：对话模式有消息后出现用量环，「思考」「联网」带文字时把发送键挤出输入框。
 * 规则（定稿 25-11）：带文字放不下就只留图标；放得下照常带文字。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatToolbarFitTest {
    @get:Rule val compose = createComposeRule()

    private fun setComposer(showContextUsage: Boolean) {
        compose.setContent {
            AgentAppTheme(appearance = AppearanceSettings(), applyInterfaceScale = false) {
                AgentChatInputBar(
                    input = "", modelPickerState = AgentModelPickerUiState(webSearchAvailable = true), isCompacting = false,
                    contextUsage = AgentContextUsageUi(40_000, 128_000), showContextUsage = showContextUsage,
                    isStreaming = false, reasoningEffort = ReasoningEffort.DEFAULT,
                    availableReasoningEfforts = listOf(ReasoningEffort.OFF, ReasoningEffort.DEFAULT, ReasoningEffort.HIGH),
                    pendingImages = emptyList(), pendingFileReferences = emptyList(), isEditingMessage = false,
                    editHasLaterTurns = false, preserveFollowingMessages = false, onReasoningEffortChange = {},
                    onCompactContext = {}, canCompactContext = false, onModelSelected = {}, onSubmit = {}, onStop = {},
                    onAttachImage = {}, onRemoveImage = {}, onAttachFiles = {}, onAttachFolder = {}, onAttachFilePath = {},
                    onRemoveFileReference = {}, onCancelMessageEdit = {}, onToggleListen = {},
                    interactionMode = InteractionMode.CHAT, chatWebSearch = true,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
    }

    @Test
    @Config(sdk = [36], qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
    fun narrowScreenWithTheUsageRingKeepsTheSendButtonInsideAndDropsTheLabels() {
        setComposer(showContextUsage = true)
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val send = compose.onNode(hasContentDescription("发送")).getUnclippedBoundsInRoot()
        assertTrue("发送键超出屏幕：${send.right} > ${root.right}", send.right <= root.right - 20.dp)
        assertEquals(0, compose.onAllNodes(hasText("联网")).fetchSemanticsNodes().size)
    }

    @Test
    @Config(sdk = [36], qualifiers = "zh-rCN-w412dp-h917dp-xxhdpi")
    fun roomyScreenKeepsTheLabels() {
        setComposer(showContextUsage = false)
        assertEquals(1, compose.onAllNodes(hasText("联网")).fetchSemanticsNodes().size)
    }
}
