package io.github.fartown.movo.agent.voice.session

import android.app.Activity
import android.app.Application
import android.content.Context
import io.github.fartown.movo.ui.AgentConversationSheetActivity
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VoiceEntryRoutingTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val activity = Activity()
    private val host = Any()
    @After fun cleanup() {
        VoiceSurfaceTracker.setChatVisible(host, false)
        VoiceSurfaceTracker.onActivityPaused(activity)
    }
    private fun systemEntry(autoListen: Boolean) {
        VoiceEntry.startFromSystemEntry(context, autoListen)
        val intent = shadowOf(context as Application).broadcastIntents.last()
        assertEquals(AssistantEntryReceiver::class.java.name, intent.component?.className)
        // Deliver Android's explicit broadcast at the main-process receiver boundary.
        AssistantEntryReceiver().onReceive(context, intent)
    }
    @Test fun systemEntryNeverConsultsTheCallingProcessVisibilityOrStartsAudioItself() {
        VoiceEntry.startFromSystemEntry(context, autoListen = true)
        assertNull(shadowOf(context as Application).nextStartedActivity)
        assertNull(shadowOf(context as Application).nextStartedService)
        val intent = shadowOf(context as Application).broadcastIntents.last()
        assertEquals(AssistantEntryReceiver::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(io.github.fartown.movo.agent.voice.MovoAssistantVoiceService.EXTRA_AUTO_LISTEN, false))
    }
    @Test fun openingAssistantWithoutAutoListenLaunchesTheChatWithoutStartingMicrophoneService() {
        systemEntry(autoListen = false)
        assertEquals(AgentConversationSheetActivity::class.java.name, shadowOf(context as Application).nextStartedActivity?.component?.className)
        assertNull(shadowOf(context as Application).nextStartedService)
    }
    @Test fun resumedSettingsActivityDoesNotCountAsAVisibleChat() {
        VoiceSurfaceTracker.onActivityResumed(activity)
        assertTrue(VoiceSurfaceTracker.appVisible)
        assertFalse(VoiceSurfaceTracker.chatVisible)
        systemEntry(autoListen = true)
        assertEquals(AgentConversationSheetActivity::class.java.name, shadowOf(context as Application).nextStartedActivity?.component?.className)
        assertNull(shadowOf(context as Application).nextStartedService)
    }
    @Test fun startingWithoutMicrophonePermissionNeverStartsTheForegroundService() {
        shadowOf(context as Application).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        assertEquals(VoiceEntry.StartResult.MIC_PERMISSION_REQUIRED, VoiceEntry.startInPlace(context))
        assertNull(shadowOf(context as Application).nextStartedService)
        // 规范 8.11 不用 Toast：原因写进输入框上方的语音提示。
        assertNull(ShadowToast.getLatestToast())
        assertEquals(VoiceEntry.MIC_REQUIRED_NOTICE, VoiceSessionManager.state.value.notice)
    }
    @Test fun callersWithoutAComposerGetTheReasonBackInsteadOfANotice() {
        shadowOf(context as Application).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        val before = VoiceSessionManager.state.value.notice
        assertEquals(VoiceEntry.StartResult.MIC_PERMISSION_REQUIRED, VoiceEntry.startInPlace(context, showNotice = false))
        assertEquals(before, VoiceSessionManager.state.value.notice)
        assertNull(ShadowToast.getLatestToast())
    }
    @Test fun startingWithPermissionStartsTheMicrophoneService() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        assertEquals(VoiceEntry.StartResult.STARTED, VoiceEntry.startInPlace(context))
        assertEquals(io.github.fartown.movo.agent.voice.MovoAssistantVoiceService.ACTION_START_VOICE,
            shadowOf(context as Application).nextStartedService?.action)
    }
    @Test fun alreadyVisibleChatUsesTheExistingHostAndPausedChatIsNotVisible() {
        VoiceSurfaceTracker.onActivityResumed(activity)
        VoiceSurfaceTracker.setChatVisible(host, true)
        systemEntry(autoListen = false)
        assertNull(shadowOf(context as Application).nextStartedActivity)
        assertNull(shadowOf(context as Application).nextStartedService)
        VoiceSurfaceTracker.onActivityPaused(activity)
        assertFalse(VoiceSurfaceTracker.chatVisible)
    }
}
