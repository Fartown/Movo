package io.github.fartown.movo.agent.voice.wake

import android.content.Context
import io.github.fartown.movo.data.model.WakePhraseRules
import java.util.Locale

/** Converts words to model phonemes, rejecting unrecognized words and native syntax. */
internal class WakeKeywordEncoder(
    private val chinese: Map<String, List<String>>,
    private val english: Map<String, List<String>>,
) {
    fun encode(phrase: String): List<String> {
        val text = phrase.trim()
        require(WakePhraseRules.isValid(text)) { "唤醒词至少需要两个字" }
        require(text.codePointCount(0, text.length) <= 32) { "唤醒词最多支持 32 个字" }
        val words = Regex("[A-Za-z']+|\\p{IsHan}").findAll(text).toList()
        require(words.joinToString("") { it.value } == text.filterNot(Char::isWhitespace)) {
            "唤醒词请使用中文或英文单词"
        }
        var variants = listOf("")
        for (word in words) {
            val phones = phonesFor(word.value)
            require(!phones.isNullOrEmpty()) { "唤醒词含暂不支持的字词：${word.value}" }
            variants = variants.flatMap { prefix -> phones.map { "$prefix $it".trim() } }.distinct().take(16)
        }
        return variants
    }

    private fun phonesFor(raw: String): List<String>? {
        chinese[raw]?.let { return it }
        val englishPhones = english[raw.uppercase(Locale.ROOT)] ?: return null
        // 字母 O 混在中文唤醒词里时，实际都读成「欧」（小O小O）；把中文读法也加入变体，
        // 否则只按英文 OW1 编成的关键词在模型上永远不会命中。
        val extra = if (raw.length == 1 && raw.equals("O", ignoreCase = true)) chinese["欧"].orEmpty() else emptyList()
        return (extra + englishPhones).distinct()
    }

    companion object {
        @Volatile private var cached: WakeKeywordEncoder? = null
        fun load(context: Context): WakeKeywordEncoder = cached ?: synchronized(this) {
            cached ?: fromLines(
                context.assets.open("sherpa-kws/zh.phone").bufferedReader().use { it.readLines() },
                context.assets.open("sherpa-kws/en.phone").bufferedReader().use { it.readLines() },
            ).also { cached = it }
        }
        fun fromLines(chinese: List<String>, english: List<String>) = WakeKeywordEncoder(
            chinese.filter { '\t' in it }.associate { it.substringBefore('\t') to it.substringAfter('\t').split('|') },
            english.filter { ' ' in it }.groupBy(
                { it.substringBefore(' ').substringBefore('(') }, { it.substringAfter(' ') },
            ),
        )
    }
}
