package io.github.fartown.movo.ui.markdown

/**
 * 修正中文里的 `**加粗**`：CommonMark 要求 `**` 在「一侧是文字、另一侧是标点」时不能作为起止，
 * 所以「点击**「桌面」**进入」这类写法会原样显示星号。在这种 `**` 与标点之间插入零宽空格（U+200B，
 * 既不算空白也不算标点），让起止判定成立；显示上看不见。
 *
 * 只处理与中日韩文字或全角标点相邻的情况，英文排版保持 CommonMark 原意；行内代码与代码块不动。
 */
internal object CjkEmphasis {
    private const val ZWSP = '​'

    fun normalize(source: String): String {
        if (!source.contains("**")) return source
        val out = StringBuilder(source.length + 8)
        var i = 0
        var inFence = false
        var lineStart = true
        var codeTicks = 0
        while (i < source.length) {
            val c = source[i]
            if (lineStart) {
                val trimmed = source.substring(i).trimStart(' ')
                if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) inFence = !inFence
            }
            lineStart = c == '\n'
            if (inFence) {
                out.append(c)
                i++
                continue
            }
            if (c == '`') {
                var run = 0
                while (i + run < source.length && source[i + run] == '`') run++
                codeTicks = if (codeTicks == 0) run else if (codeTicks == run) 0 else codeTicks
                out.append(source, i, i + run)
                i += run
                continue
            }
            if (codeTicks == 0 && c == '*' && isDoubleStar(source, i)) {
                val prev = source.getOrNull(i - 1)
                val next = source.getOrNull(i + 2)
                // 收尾 `**`：前面是标点、后面是文字 → 不满足右侧定界，在前面补零宽空格。
                if (prev != null && next != null && isPunct(prev) && isWordChar(next) && (isCjk(next) || isCjkPunct(prev))) {
                    out.append(ZWSP)
                }
                out.append("**")
                // 起始 `**`：前面是文字、后面是标点 → 不满足左侧定界，在后面补零宽空格。
                if (prev != null && next != null && isWordChar(prev) && isPunct(next) && (isCjk(prev) || isCjkPunct(next))) {
                    out.append(ZWSP)
                }
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** 恰好两个星号（不是 `***` 等更长的串的一部分）。 */
    private fun isDoubleStar(s: String, i: Int): Boolean =
        i + 1 < s.length && s[i + 1] == '*' && s.getOrNull(i - 1) != '*' && s.getOrNull(i + 2) != '*'

    private fun isWordChar(c: Char): Boolean = !c.isWhitespace() && !isPunct(c) && c != ZWSP

    private fun isPunct(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
        Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true
        else -> false
    }

    private fun isCjk(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    private fun isCjkPunct(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
            c == '“' || c == '”' || c == '‘' || c == '’' || c == '…' || c == '—'
    }
}
