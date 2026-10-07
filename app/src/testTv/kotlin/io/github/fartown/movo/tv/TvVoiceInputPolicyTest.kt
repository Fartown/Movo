package io.github.fartown.movo.tv

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.voice.conversation.DoubaoDialogEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 语音会话期间节目压低还是暂停（docs/solutions/tv-voice-app/语音时节目声音与断句时延方案.md §3.1）。 */
@RunWith(RobolectricTestRunner::class)
class TvVoiceInputPolicyTest {
    @Test
    fun tclInputDucksTheProgramBecauseItCancelsTheRestOfItsSound() {
        assertTrue(TclPcmInput(ApplicationProvider.getApplicationContext()) {}.duckMediaDuringSession)
    }

    @Test
    fun otherInputsStillAskThePlayerToPause() {
        val plain = object : DoubaoDialogEngine.PcmInput {
            override fun start(feed: (ByteArray) -> Unit) = Unit
            override fun close() = Unit
        }
        assertFalse(plain.duckMediaDuringSession)
    }
}
