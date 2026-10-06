package io.github.fartown.movo.diagnostics.runlog

/** 数字原文：解析后再写回时不改写格式（1.50 还是 1.50，大整数不丢精度）。 */
internal class JsonNumber(val raw: String) {
    override fun equals(other: Any?): Boolean = other is JsonNumber && other.raw == raw
    override fun hashCode(): Int = raw.hashCode()
    override fun toString(): String = raw
}

/**
 * 运行日志自己的 JSON 读写：对象保持键的顺序，数字保留原文，字符串只转义 JSON 必须转义的字符。
 * 不用 org.json：它在 Android 与 JVM 上键顺序、`/` 的转义不同，重写日志时会改动原文。
 */
internal object RunLogJson {
    /** 嵌套过深的文本按普通字符串处理，避免栈溢出。 */
    private const val MAX_DEPTH = 256

    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> quote(value, out)
            is Boolean -> out.append(value)
            is JsonNumber -> out.append(value.raw)
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Double -> if (value.isFinite()) out.append(value) else quote(value.toString(), out)
            is Float -> if (value.isFinite()) out.append(value) else quote(value.toString(), out)
            is Number -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) out.append(',')
                    first = false
                    quote(key.toString(), out)
                    out.append(':')
                    write(item, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    write(item, out)
                }
                out.append(']')
            }
            is Array<*> -> write(value.asList(), out)
            else -> quote(value.toString(), out)
        }
    }

    fun quote(text: String, out: StringBuilder) {
        out.append('"')
        for (char in text) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                // U+2028/2029 合法但会断开部分查看器的行。
                ' ', ' ' -> out.append("\\u").append(hex4(char.code))
                else -> if (char < ' ') out.append("\\u").append(hex4(char.code)) else out.append(char)
            }
        }
        out.append('"')
    }

    private fun hex4(code: Int): String = code.toString(16).padStart(4, '0')

    /** 解析完整的 JSON 文本；格式不对时抛 [IllegalArgumentException]。 */
    fun parse(text: String): Any? = Parser(text).document()

    /** 去掉首尾空白后以 `{…}` 或 `[…]` 包起来：值本身可能是 JSON 文本。 */
    fun looksLikeJson(text: String): Boolean {
        val first = text.indexOfFirst { !it.isJsonWhitespace() }
        if (first < 0) return false
        val last = text.indexOfLast { !it.isJsonWhitespace() }
        return (text[first] == '{' && text[last] == '}') || (text[first] == '[' && text[last] == ']')
    }

    private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\n' || this == '\r' || this == '\t'

    private class Parser(private val text: String) {
        private var index = 0

        fun document(): Any? {
            val value = value(0)
            skipWhitespace()
            if (index != text.length) fail("trailing data")
            return value
        }

        private fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("too deep")
            skipWhitespace()
            if (index >= text.length) fail("unexpected end")
            return when (val char = text[index]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (char == '-' || char in '0'..'9') number() else fail("unexpected '$char'")
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            index++ // {
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { index++; return result }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected key")
                val key = string()
                skipWhitespace()
                if (peek() != ':') fail("expected ':'")
                index++
                result[key] = value(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> { index++; return result }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            index++ // [
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { index++; return result }
            while (true) {
                result += value(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> { index++; return result }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            index++ // opening quote
            val out = StringBuilder()
            var start = index
            while (true) {
                if (index >= text.length) fail("unterminated string")
                val char = text[index]
                when {
                    char == '"' -> {
                        out.append(text, start, index)
                        index++
                        return out.toString()
                    }
                    char == '\\' -> {
                        out.append(text, start, index)
                        if (index + 1 >= text.length) fail("bad escape")
                        when (val escaped = text[index + 1]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 6 > text.length) fail("bad unicode escape")
                                val code = text.substring(index + 2, index + 6).toIntOrNull(16) ?: fail("bad unicode escape")
                                out.append(code.toChar())
                                index += 4
                            }
                            else -> fail("bad escape '$escaped'")
                        }
                        index += 2
                        start = index
                    }
                    char < ' ' -> fail("control character in string")
                    else -> index++
                }
            }
        }

        private fun number(): JsonNumber {
            val start = index
            if (peek() == '-') index++
            if (peek() == '0') {
                index++
            } else {
                if (peek() !in '1'..'9') fail("bad number")
                while (peek() in '0'..'9') index++
            }
            if (peek() == '.') {
                index++
                if (peek() !in '0'..'9') fail("bad number")
                while (peek() in '0'..'9') index++
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                if (peek() !in '0'..'9') fail("bad number")
                while (peek() in '0'..'9') index++
            }
            return JsonNumber(text.substring(start, index))
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, index)) fail("unexpected literal")
            index += word.length
            return value
        }

        private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

        private fun skipWhitespace() {
            while (index < text.length) {
                val char = text[index]
                if (char == ' ' || char == '\n' || char == '\r' || char == '\t') index++ else return
            }
        }

        private fun fail(reason: String): Nothing = throw IllegalArgumentException("$reason at $index")
    }
}
