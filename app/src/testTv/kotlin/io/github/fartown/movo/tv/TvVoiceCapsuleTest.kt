package io.github.fartown.movo.tv

import android.view.KeyEvent
import io.github.fartown.movo.agent.voice.session.VoiceChannel
import io.github.fartown.movo.flavor.FlavorModule
import io.github.fartown.movo.tv.TvBackHandler.RemoteAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 电视语音胶囊的文字规则（Figma「Movo TV」候选 v2）与连续对话参数。 */
class TvVoiceCapsuleTest {
    @Test
    fun tvConversationEndsAfterTenIdleSecondsAndOpensFullAnswerLocally() {
        assertEquals(10_000L, FlavorModule.voiceIdleTimeoutMs)
        assertTrue("看全文" in FlavorModule.voiceLocalCommands)
    }

    @Test
    fun numberedOptionsBecomeAChoiceCard() {
        val card = TvVoicePanel.parseChoices("找到 3 个《狂飙》，要哪一个？\n1. 狂飙 · 电视剧 2023\n2. **狂飙 第二季**\n3）狂飙 电影版")!!
        assertEquals("找到 3 个《狂飙》，要哪一个？", card.header)
        assertEquals(listOf("狂飙 · 电视剧 2023", "狂飙 第二季", "狂飙 电影版"), card.items)
    }

    @Test
    fun singleSentenceOrSingleNumberIsNotAChoiceCard() {
        assertNull(TvVoicePanel.parseChoices("要带伞，明天小雨 16–22°"))
        assertNull(TvVoicePanel.parseChoices("今天有 1. 个提醒"))
    }

    @Test
    fun capsuleShowsOneLineAndOffersFullTextForLongAnswers() {
        assertEquals("宫保鸡丁的做法一共 6 步", TvVoicePanel.summaryOf("## 宫保鸡丁的做法一共 6 步\n1. 鸡腿肉切丁\n2. 调碗汁"))
        assertEquals("说「看全文」", TvVoicePanel.longHintOf("第一句\n第二句"))
        assertNull(TvVoicePanel.longHintOf("要带伞，明天小雨 16–22°"))
    }

    @Test
    fun pickingUpTheRemoteHandsTheTvBackToTheUser() {
        fun action(key: Int, channel: VoiceChannel, running: Boolean = false, movo: Boolean = false) =
            TvBackHandler.remoteAction(key, channel, running, movo)
        assertEquals(RemoteAction.Yield, action(KeyEvent.KEYCODE_DPAD_RIGHT, VoiceChannel.Listening))
        assertEquals(RemoteAction.Yield, action(KeyEvent.KEYCODE_HOME, VoiceChannel.Off, running = true))
        assertEquals(RemoteAction.Yield, action(KeyEvent.KEYCODE_DPAD_CENTER, VoiceChannel.Thinking, running = true))
        assertEquals(RemoteAction.Interrupt, action(KeyEvent.KEYCODE_DPAD_CENTER, VoiceChannel.Speaking))
        assertEquals(RemoteAction.Hold, action(KeyEvent.KEYCODE_ENTER, VoiceChannel.Hearing))
    }

    @Test
    fun volumeKeysNoSessionAndMovoOwnPagesKeepNormalRemoteBehaviour() {
        assertEquals(RemoteAction.PassThrough, TvBackHandler.remoteAction(KeyEvent.KEYCODE_VOLUME_UP, VoiceChannel.Listening, false, false))
        assertEquals(RemoteAction.PassThrough, TvBackHandler.remoteAction(KeyEvent.KEYCODE_DPAD_DOWN, VoiceChannel.Off, false, false))
        assertEquals(RemoteAction.PassThrough, TvBackHandler.remoteAction(KeyEvent.KEYCODE_DPAD_DOWN, VoiceChannel.Listening, true, true))
    }

    @Test
    fun longAnswersBecomeSingleLineScreens() {
        val answer = "要带。明天上海小雨，16–22°，下午 3 点后雨会变大，傍晚出门最好带一把长柄伞。后天多云转晴，18–25°，不用带伞。"
        val screens = TvVoicePanel.screensOf(answer)
        assertTrue(screens.size > 1)
        assertTrue(screens.all { it.length <= TvVoicePanel.LINE_CHARS })
        assertEquals(answer, screens.joinToString(""))
        assertTrue("优先在标点处断屏", screens.dropLast(1).all { it.last() in "。！？；，" })
    }

    @Test
    fun liveTranscriptRotatesToTheNextScreenWhenTheLineIsFull() {
        assertEquals("明天要不要带伞", TvVoicePanel.transcriptPage(" 明天要不要带伞 "))
        val full = "帮我打开奇异果然后搜索狂飙第二季再从第一集开始播"   // 25 字
        val page = TvVoicePanel.transcriptPage(full)
        assertTrue(page.startsWith("…"))
        assertEquals(full.substring(TvVoicePanel.LINE_CHARS), page.drop(1))
        assertTrue(page.length <= TvVoicePanel.LINE_CHARS)
        // 继续往下说，同一屏只在末尾长字，直到满了再换下一屏
        val longer = full + "放并且把音量调到二十然后"
        assertTrue(TvVoicePanel.transcriptPage(longer).length <= TvVoicePanel.LINE_CHARS)
    }
}
