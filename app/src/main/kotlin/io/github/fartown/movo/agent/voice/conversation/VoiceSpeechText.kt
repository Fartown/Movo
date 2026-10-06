package io.github.fartown.movo.agent.voice.conversation

/**
 * 念之前的确定性去格式（语音简短回复方案 §3.2）：只影响送去 TTS 的文字，屏幕上仍是原文。
 * 不调模型、不改写句子、不截断；提示词生效时模型本来就写纯文字，这里基本原样返回，只防偶尔残留的符号被念出来。
 */
internal object VoiceSpeechText {
    const val CODE_ON_SCREEN = "代码在屏幕上。"
    const val TABLE_ON_SCREEN = "表格在屏幕上。"

    /** 去完格式成了空串时念这一句：空串送给引擎不会回调「播完」，会话会卡在「正在回答」。 */
    const val ANSWER_ON_SCREEN = "回答在屏幕上。"

    fun normalize(text: String): String {
        if (text.isBlank()) return ""
        val lines = dropSources(text.replace("\r\n", "\n").replace('\r', '\n').lines())
        val out = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                // 空行原样保留：纯文字的答复去格式前后一字不差。
                out += ""
                index++
                continue
            }
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                val marker = fence.groupValues[1]
                index++
                while (index < lines.size && !lines[index].trimStart().startsWith(marker)) index++
                index++
                out += CODE_ON_SCREEN
                continue
            }
            if ('|' in line && index + 1 < lines.size && TABLE_SEPARATOR.matches(lines[index + 1])) {
                index += 2
                while (index < lines.size && '|' in lines[index] && lines[index].isNotBlank()) index++
                out += TABLE_ON_SCREEN
                continue
            }
            spokenLine(line)?.let(out::add)
            index++
        }
        val spoken = out.joinToString("\n").trim()
        return spoken.ifEmpty { ANSWER_ON_SCREEN }
    }

    /** App 给联网搜索结果补的「来源：」列表（ResponsesCitationFormatter）在末尾，整段不念。 */
    private fun dropSources(lines: List<String>): List<String> {
        val start = lines.indexOfLast { it.trim() == "来源：" }
        if (start < 0) return lines
        val rest = lines.drop(start + 1).filter { it.isNotBlank() }
        return if (rest.all { SOURCE_ITEM.matches(it) }) lines.take(start) else lines
    }

    private fun spokenLine(raw: String): String? {
        if (RULE.matches(raw)) return null
        var line = raw
        var block = false
        while (true) {
            val stripped = BLOCK_PREFIXES.fold(line) { acc, regex -> regex.replaceFirst(acc, "") }
            if (stripped == line) break
            block = true
            line = stripped
        }
        line = inline(line).trim()
        if (line.isEmpty()) return null
        // 标题、列表项去掉符号后各自成句，念的时候能停顿。
        if (block && line.last() !in SENTENCE_END) line += "。"
        return line
    }

    private fun inline(text: String): String {
        var line = text
        line = CITATION.replace(line, "")
        line = IMAGE.replace(line) { it.groupValues[1] }
        line = LINK.replace(line) { it.groupValues[1] }
        line = AUTOLINK.replace(line, "")
        line = URL.replace(line, "")
        line = INLINE_CODE.replace(line) { it.groupValues[1] }
        line = BOLD.replace(line) { it.groupValues[1] }
        line = ITALIC.replace(line) { it.groupValues[1] }
        line = STRIKE.replace(line) { it.groupValues[1] }
        line = BREAK.replace(line, " ")
        line = stripEmoji(line)
        return SPACES.replace(line, " ")
    }

    /** 只去表情；℃、¥、→ 这类符号保留，不能按符号类别一刀切。 */
    private fun stripEmoji(text: String): String = buildString {
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            if (!isEmoji(codePoint)) appendCodePoint(codePoint)
            offset += Character.charCount(codePoint)
        }
    }

    private fun isEmoji(codePoint: Int): Boolean =
        codePoint in 0x1F000..0x1FAFF || codePoint in 0x2600..0x27BF ||
            codePoint == 0xFE0F || codePoint == 0x200D || codePoint == 0x20E3

    private val SENTENCE_END = setOf('。', '！', '？', '；', '：', '.', '!', '?', ';', ':', '，', ',', '…')
    private val FENCE = Regex("""^\s{0,3}(```|~~~).*$""")
    private val TABLE_SEPARATOR = Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$""")
    private val SOURCE_ITEM = Regex("""^\s*-\s*\[\d+]\s*\[.*]\(.*\)\s*$""")
    private val RULE = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
    private val BLOCK_PREFIXES = listOf(
        Regex("""^\s{0,3}#{1,6}\s+"""),
        Regex("""^\s{0,3}>\s?"""),
        Regex("""^\s*[-*+]\s+(\[[ xX]]\s+)?"""),
        Regex("""^\s*\d{1,3}[.)]\s+"""),
    )
    private val CITATION = Regex("""\s?\[\\\[\d+\\]]\([^)]*\)""")
    private val IMAGE = Regex("""!\[([^\]]*)]\([^)]*\)""")
    private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val AUTOLINK = Regex("""<https?://[^>\s]+>""")
    private val URL = Regex("""https?://[^\s)\]，。、；！？]+""")
    private val INLINE_CODE = Regex("""`([^`]+)`""")
    private val BOLD = Regex("""\*\*(.+?)\*\*""")
    private val ITALIC = Regex("""(?<![\w*])\*(?![\s*])(.+?)(?<![\s*])\*(?![\w*])""")
    private val STRIKE = Regex("""~~(.+?)~~""")
    private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""[ \t]{2,}""")
}
