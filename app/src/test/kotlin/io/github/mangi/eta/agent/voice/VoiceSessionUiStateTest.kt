package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.agent.voice.session.VoiceChannel
import io.github.mangi.eta.agent.voice.session.VoiceSessionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionUiStateTest {
    private fun channel(event: String, active: Boolean = true, previous: VoiceChannel = VoiceChannel.Listening) =
        VoiceSessionUiState.channelFor(event, active, previous)

    @Test fun inactiveSessionIsAlwaysOffRegardlessOfEvent() {
        listOf("listening", "speaking", "dispatch", "transcript").forEach { event ->
            assertEquals(VoiceChannel.Off, channel(event, active = false))
        }
    }

    @Test fun controllerEventsMapToExactlyOneChannel() {
        assertEquals(VoiceChannel.Connecting, channel("connecting"))
        assertEquals(VoiceChannel.Hearing, channel("hearing"))
        assertEquals(VoiceChannel.Hearing, channel("transcript"))
        assertEquals(VoiceChannel.Thinking, channel("dispatch"))
        assertEquals(VoiceChannel.Speaking, channel("speaking"))
        assertEquals(VoiceChannel.Listening, channel("committed"))
        assertEquals(VoiceChannel.Off, channel("ended"))
    }

    /** endpoint / input.activity 只刷新文案与字幕，不应该把界面从"正在回答"弹回"我在听"。 */
    @Test fun unmappedEventsKeepTheCurrentChannel() {
        assertEquals(VoiceChannel.Speaking, channel("endpoint", previous = VoiceChannel.Speaking))
        assertEquals(VoiceChannel.Thinking, channel("input.activity", previous = VoiceChannel.Thinking))
    }

    /** 通道还没建立时收到未知事件，至少要表现为"已开始"，不能停在 Off 让麦克风按钮说谎。 */
    @Test fun unmappedEventOnAFreshSessionStartsListening() {
        assertEquals(VoiceChannel.Listening, channel("endpoint", previous = VoiceChannel.Off))
    }

    @Test fun activeAndSpeakingAreDerivedFromTheChannelOnly() {
        assertFalse(VoiceSessionUiState().active)
        assertTrue(VoiceSessionUiState(channel = VoiceChannel.Connecting).active)
        assertTrue(VoiceSessionUiState(channel = VoiceChannel.Speaking).speaking)
        assertFalse(VoiceSessionUiState(channel = VoiceChannel.Listening).speaking)
    }
}
