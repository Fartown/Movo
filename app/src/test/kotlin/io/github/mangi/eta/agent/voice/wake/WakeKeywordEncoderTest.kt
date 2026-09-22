package io.github.mangi.eta.agent.voice.wake

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class WakeKeywordEncoderTest {
    private val assets = File("src/main/assets/sherpa-kws")
    private val encoder = WakeKeywordEncoder.fromLines(
        File(assets, "zh.phone").readLines(), File(assets, "en.phone").readLines(),
    )

    @Test fun bundledDictionaryEncodesDefaultAndCustomPhrasesWithModelTokens() {
        val tokens = File(assets, "tokens.txt").readLines().map { it.substringBefore(' ') }.toSet()
        val default = encoder.encode("小王同学")
        val custom = encoder.encode("你好小莫")
        assertTrue(default.contains("x iǎo w áng t óng x ué"))
        assertTrue(custom.contains("n ǐ h ǎo x iǎo m ò"))
        assertTrue(default.none { it in custom })
        (default + custom + encoder.encode("hello world")).forEach { phrase ->
            phrase.split(' ').forEach { assertTrue("Unknown phoneme $it", it in tokens) }
        }
    }

    @Test fun invalidPhraseAndNativeSyntaxAreRejectedWithoutDefaultSubstitution() {
        for (text in listOf("", "小", "你好 @wake", "你好 #0.01", "👋你好", "xyznotaword")) {
            assertThrows("Expected rejection for $text", IllegalArgumentException::class.java) { encoder.encode(text) }
        }
    }
}
