package io.github.fartown.movo.agent.voice

import io.github.fartown.movo.agent.voice.conversation.VoiceSpeechText
import org.junit.Assert.assertEquals
import org.junit.Test

/** 念之前去格式（语音简短回复方案 §3.2）：只去符号，不改写、不截断；纯文字原样返回。 */
class VoiceSpeechTextTest {
    @Test fun plainSpokenTextIsUnchanged() {
        for (text in listOf(
            "好了，明天早上 7 点的闹钟已经设好。",
            "有，最近两条：今早菜鸟一条到驿站了，取件码 6-2-3011，18 点前到学院路店取。",
            "第一段。\n\n第二段。",
            "还剩大约 61.2 GB，总容量 256 GB。",
            "气温 13℃ 到 26℃，¥45.20，从 A → B。",
            "3*4 等于 12，file_name 不用改，温度>30 度，10-20 分钟，7:00 出发，2.5 公里。",
        )) assertEquals(text, VoiceSpeechText.normalize(text))
    }

    @Test fun headingsListsQuotesAndEmphasisLoseTheirMarks() {
        val text = """
            ## 番茄炒蛋
            1. **备料**：番茄切块
            2. 炒蛋
            - 小窍门：*糖别省*
            > 记得~~少~~放盐
            ---
        """.trimIndent()
        assertEquals("番茄炒蛋。\n备料：番茄切块。\n炒蛋。\n小窍门：糖别省。\n记得少放盐。", VoiceSpeechText.normalize(text))
    }

    @Test fun linksUrlsAndAppCitationsAreNotRead() {
        val text = "明天北京晴 [\\[1\\]](https://weather.example.com/a%28b%29)，详情见[天气网](https://w.example.com)或 https://w.example.com/x 。\n\n" +
            "来源：\n- [2] [某报道](https://news.example.com/2)\n- [3] [另一篇](https://news.example.com/3)"
        assertEquals("明天北京晴，详情见天气网或 。", VoiceSpeechText.normalize(text))
    }

    @Test fun sourcesHeadingInsideTheAnswerIsKeptWhenItIsNotTheAppendedList() {
        val text = "来源：\n这条消息来自菜鸟驿站。"
        assertEquals(text, VoiceSpeechText.normalize(text))
    }

    @Test fun codeBlocksAndTablesAreReplacedWithAPointerToTheScreen() {
        val text = """
            用这个命令：
            ```bash
            ls -la /sdcard
            ```
            结果如下：
            | 项目 | 数值 |
            |---|---|
            | 直径 | 14 万公里 |
            看得出木星最大，用 `ls` 也行。
        """.trimIndent()
        assertEquals(
            "用这个命令：\n${VoiceSpeechText.CODE_ON_SCREEN}\n结果如下：\n${VoiceSpeechText.TABLE_ON_SCREEN}\n看得出木星最大，用 ls 也行。",
            VoiceSpeechText.normalize(text),
        )
    }

    @Test fun barPipeWithoutSeparatorIsNotATable() {
        val text = "选项 A | 选项 B 都可以。"
        assertEquals(text, VoiceSpeechText.normalize(text))
    }

    @Test fun emojiIsDroppedButSymbolsStay() {
        assertEquals("好了 ✨".let { VoiceSpeechText.normalize(it) }, "好了")
        assertEquals("搞定，26℃。", VoiceSpeechText.normalize("搞定👍，26℃。"))
    }

    @Test fun answerThatIsOnlyFormattingStillSaysSomething() {
        // 空串送给引擎不会回调「播完」，会话会卡在「正在回答」。
        assertEquals(VoiceSpeechText.ANSWER_ON_SCREEN, VoiceSpeechText.normalize("https://example.com/only-a-link"))
        assertEquals(VoiceSpeechText.ANSWER_ON_SCREEN, VoiceSpeechText.normalize("---\n🎉"))
        assertEquals("", VoiceSpeechText.normalize("   "))
    }
}
