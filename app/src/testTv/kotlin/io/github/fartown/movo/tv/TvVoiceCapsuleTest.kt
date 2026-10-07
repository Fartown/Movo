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
    fun stepTitlesReadAsWhatMovoIsDoingWithoutAStepCount() {
        assertEquals("正在打开「哔哩哔哩」", TvVoicePanel.doing("打开「哔哩哔哩」"))
        assertEquals("正在输入「罗翔」", TvVoicePanel.doing("输入「罗翔」"))
        assertEquals("正在屏幕上找「搜索」", TvVoicePanel.doing("在屏幕上找「搜索」"))
        assertEquals("正在验证悬浮层", TvVoicePanel.doing("正在验证悬浮层"))
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
    fun answersBecomeOneScrollingLine() {
        val answer = "## 要带伞\n明天上海小雨，16–22°。\n- 下午 3 点后雨会变大\n**傍晚**出门带长柄伞。"
        assertEquals("要带伞  明天上海小雨，16–22°。  下午 3 点后雨会变大  傍晚出门带长柄伞。", TvVoicePanel.flatten(answer))
        assertTrue("一行显示，不含换行", '\n' !in TvVoicePanel.flatten(answer))
    }
}
