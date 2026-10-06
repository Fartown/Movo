package io.github.fartown.movo.tv

import io.github.fartown.movo.flavor.FlavorModule
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
}
