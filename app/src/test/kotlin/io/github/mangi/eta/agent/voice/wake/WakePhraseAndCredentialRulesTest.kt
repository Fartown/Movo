package io.github.mangi.eta.agent.voice.wake

import io.github.mangi.eta.data.model.DoubaoCredentialRules
import io.github.mangi.eta.data.model.DoubaoSpeechCredentials
import io.github.mangi.eta.data.model.WakePhraseRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseAndCredentialRulesTest {
    @Test
    fun defaultPhrase_isXiaoOXiaoO() {
        assertEquals("小O小O", WakePhraseRules.DEFAULT)
        assertEquals("小O小O", WakePhraseRules.normalizeOrDefault(""))
        assertEquals("小O小O", WakePhraseRules.normalizeOrDefault("  "))
        assertEquals("小O小O", WakePhraseRules.normalizeOrDefault("a"))
    }

    @Test
    fun validCustomPhrase_isKept() {
        assertEquals("你好助手", WakePhraseRules.normalizeOrDefault(" 你好助手 "))
        assertTrue(WakePhraseRules.isValid("小王同学"))
        assertFalse(WakePhraseRules.isValid(""))
        assertFalse(WakePhraseRules.isValid("我"))
    }

    @Test
    fun shortPhraseWarning() {
        assertTrue(WakePhraseRules.isShortPhraseWarning("你好"))
        assertFalse(WakePhraseRules.isShortPhraseWarning("小王同学"))
    }

    @Test
    fun wakeMatcher_ignoresPunctuation() {
        assertTrue(WakePhraseMatcher.matches("嗯，小王同学，帮我开灯", "小王同学"))
        assertTrue(WakePhraseMatcher.matches("XIAO WANG", "xiao wang"))
        assertFalse(WakePhraseMatcher.matches("小爱同学", "小王同学"))
    }

    @Test
    fun doubaoCredentials_requireApiKeyOrAppPair() {
        assertFalse(DoubaoSpeechCredentials().hasUsableAuth())
        assertTrue(DoubaoSpeechCredentials(apiKey = "k").hasUsableAuth())
        assertTrue(DoubaoSpeechCredentials(appKey = "a", accessKey = "b").hasUsableAuth())
        assertFalse(DoubaoSpeechCredentials(appKey = "a").hasUsableAuth())

        assertTrue(
            DoubaoCredentialRules.validate(DoubaoSpeechCredentials(apiKey = "k"))
                is DoubaoCredentialRules.Validation.Ok,
        )
        assertTrue(
            DoubaoCredentialRules.validate(DoubaoSpeechCredentials())
                is DoubaoCredentialRules.Validation.Invalid,
        )
        assertTrue(
            DoubaoCredentialRules.validate(
                DoubaoSpeechCredentials(apiKey = "k", endpoint = "http://example"),
            ) is DoubaoCredentialRules.Validation.Invalid,
        )
    }
}
