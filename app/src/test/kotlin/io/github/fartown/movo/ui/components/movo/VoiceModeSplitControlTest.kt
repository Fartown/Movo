package io.github.fartown.movo.ui.components.movo

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.overlay.AgentOverlayBubble
import io.github.fartown.movo.agent.overlay.AgentOverlayPhase
import io.github.fartown.movo.agent.overlay.AgentOverlayState
import io.github.fartown.movo.agent.overlay.AgentOverlayStatus
import io.github.fartown.movo.agent.voice.session.VoiceChannel
import io.github.fartown.movo.agent.voice.session.VoiceSessionUiState
import io.github.fartown.movo.data.model.AppearanceSettings
import io.github.fartown.movo.data.model.ReasoningEffort
import io.github.fartown.movo.ui.app.AgentAppTheme
import io.github.fartown.movo.ui.components.AgentChatInputBar
import io.github.fartown.movo.ui.components.dispatchVoiceModeStop
import io.github.fartown.movo.ui.model.AgentContextUsageUi
import io.github.fartown.movo.ui.model.AgentModelPickerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
class VoiceModeSplitControlTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun exitSegmentOnlyInvokesExitCallback() {
        var exitCalls = 0
        var keyboardCalls = 0
        showControl(
            onExitVoice = { exitCalls++ },
            onKeyboardInput = { keyboardCalls++ },
        )

        compose.onNodeWithContentDescription(resourceString(R.string.movo_voice_exit)).performClick()

        compose.runOnIdle {
            assertEquals(1, exitCalls)
            assertEquals(0, keyboardCalls)
        }
    }

    @Test
    fun compactKeyboardSegmentOnlyInvokesKeyboardCallback() {
        var exitCalls = 0
        var keyboardCalls = 0
        showControl(
            size = VoiceModeSplitControlSize.Compact,
            onExitVoice = { exitCalls++ },
            onKeyboardInput = { keyboardCalls++ },
        )

        compose.onNodeWithContentDescription(resourceString(R.string.movo_voice_temporary_keyboard_input)).performClick()

        compose.runOnIdle {
            assertEquals(0, exitCalls)
            assertEquals(1, keyboardCalls)
        }
    }

    @Test
    fun streamingStopTakesPriorityWhenSpeechIsAlsoActive() {
        var streamingStops = 0
        var speakingStops = 0

        dispatchVoiceModeStop(
            isStreaming = true,
            isSpeaking = true,
            onStopStreaming = { streamingStops++ },
            onStopSpeaking = { speakingStops++ },
        )

        assertEquals(1, streamingStops)
        assertEquals(0, speakingStops)
    }

    @Test
    fun speakingStopRunsWhenThereIsNoStream() {
        var streamingStops = 0
        var speakingStops = 0

        dispatchVoiceModeStop(
            isStreaming = false,
            isSpeaking = true,
            onStopStreaming = { streamingStops++ },
            onStopSpeaking = { speakingStops++ },
        )

        assertEquals(0, streamingStops)
        assertEquals(1, speakingStops)
    }

    @Test
    fun composerVoiceToolbarKeepsSplitHintAndMainActionSeparated() {
        compose.setContent {
            AgentAppTheme(appearance = AppearanceSettings(), applyInterfaceScale = false) {
                AgentChatInputBar(
                    input = "",
                    modelPickerState = AgentModelPickerUiState(),
                    isCompacting = false,
                    contextUsage = AgentContextUsageUi(null, null),
                    showContextUsage = false,
                    isStreaming = true,
                    reasoningEffort = ReasoningEffort.DEFAULT,
                    availableReasoningEfforts = emptyList(),
                    pendingImages = emptyList(),
                    pendingFileReferences = emptyList(),
                    isEditingMessage = false,
                    editHasLaterTurns = false,
                    preserveFollowingMessages = false,
                    onReasoningEffortChange = {},
                    onCompactContext = {},
                    canCompactContext = false,
                    onModelSelected = {},
                    onSubmit = {},
                    onStop = {},
                    onAttachImage = {},
                    onRemoveImage = {},
                    onAttachFiles = {},
                    onAttachFolder = {},
                    onAttachFilePath = {},
                    onRemoveFileReference = {},
                    onCancelMessageEdit = {},
                    voice = VoiceSessionUiState(channel = VoiceChannel.Listening),
                    modifier = Modifier.width(372.dp),
                )
            }
        }

        val exit = compose.onNodeWithContentDescription(resourceString(R.string.movo_voice_exit)).fetchSemanticsNode().boundsInRoot
        val keyboard = compose.onNodeWithContentDescription(
            resourceString(R.string.movo_voice_temporary_keyboard_input),
        ).fetchSemanticsNode().boundsInRoot
        val hint = compose.onNodeWithText(resourceString(R.string.movo_voice_hint_busy)).fetchSemanticsNode().boundsInRoot
        val main = compose.onNodeWithContentDescription(resourceString(R.string.movo_main_stop)).fetchSemanticsNode().boundsInRoot

        assertPxEquals(72f, exit.width)
        assertPxEquals(43f, keyboard.width)
        assertPxEquals(116f, keyboard.right - exit.left)
        assertTrue("split control overlaps the hint", keyboard.right <= hint.left)
        assertTrue("hint overlaps the main action", hint.right <= main.left)
        assertTrue("voice toolbar exceeds 356dp", main.right - exit.left <= dpToPx(356f))
    }

    @Test
    fun overlayVoiceActionsFitTheAdaptivePanelWithoutOverlap() {
        showOverlayVoiceActions()

        assertOverlayActionsSeparated(maxWidthDp = 280f)
    }

    @Test
    @Config(sdk = [36], qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi", fontScale = 1.4f)
    fun overlayVoiceActionsGrowWithoutClippingAtLargeFontScale() {
        showOverlayVoiceActions()

        assertOverlayActionsSeparated(maxWidthDp = 280f)
    }

    private fun showOverlayVoiceActions() {
        compose.setContent {
            AgentAppTheme(appearance = AppearanceSettings(), applyInterfaceScale = false) {
                AgentOverlayBubble(
                    state = AgentOverlayState(
                        phase = AgentOverlayPhase.PAUSED,
                        status = AgentOverlayStatus.Paused,
                    ),
                    onCollapse = {},
                    onPause = {},
                    onResume = {},
                    onStop = {},
                    onSupplementModeChange = {},
                    onSupplement = {},
                    voice = VoiceSessionUiState(channel = VoiceChannel.Listening),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun assertOverlayActionsSeparated(maxWidthDp: Float) {
        val exit = compose.onNodeWithContentDescription(resourceString(R.string.movo_voice_exit)).fetchSemanticsNode().boundsInRoot
        val keyboard = compose.onNodeWithContentDescription(
            resourceString(R.string.movo_voice_temporary_keyboard_input),
        ).fetchSemanticsNode().boundsInRoot
        val endTask = compose.onNodeWithText(resourceString(R.string.movo_work_end_task)).fetchSemanticsNode().boundsInRoot
        val resume = compose.onNodeWithText(resourceString(R.string.movo_overlay_resume)).fetchSemanticsNode().boundsInRoot

        assertPxEquals(52f, exit.width)
        assertPxEquals(31f, keyboard.width)
        assertPxEquals(84f, keyboard.right - exit.left)
        assertTrue("compact split control overlaps the end-task action", keyboard.right <= endTask.left)
        assertTrue("end-task action overlaps pause/resume", endTask.right <= resume.left)
        assertTrue("overlay actions exceed the adaptive panel", resume.right - exit.left <= dpToPx(maxWidthDp))
    }

    private fun showControl(
        size: VoiceModeSplitControlSize = VoiceModeSplitControlSize.Standard,
        onExitVoice: () -> Unit,
        onKeyboardInput: () -> Unit,
    ) = compose.setContent {
        AgentAppTheme(appearance = AppearanceSettings(), applyInterfaceScale = false) {
            VoiceModeSplitControl(
                onExitVoice = onExitVoice,
                onKeyboardInput = onKeyboardInput,
                size = size,
            )
        }
    }

    private fun resourceString(resourceId: Int): String =
        RuntimeEnvironment.getApplication().getString(resourceId)

    private fun assertPxEquals(expectedDp: Float, actualPx: Float) {
        assertEquals(dpToPx(expectedDp), actualPx, 1f)
    }

    private fun dpToPx(value: Float): Float =
        value * RuntimeEnvironment.getApplication().resources.displayMetrics.density
}
