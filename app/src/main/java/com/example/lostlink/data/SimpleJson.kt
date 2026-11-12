package com.example.lostlink.data

/**
 * Pure-Kotlin RFC 8259 JSON parser and encoder used across data repositories.
 *
 * Avoids `org.json.JSONObject` / `org.json.JSONArray`, which are stubbed out in
 * Android's local JVM `testDebugUnitTest` classpath and throw `RuntimeException: not mocked`.
 */
internal object SimpleJson {
    private const val MAX_DEPTH = 128

    fun parseObject(text: String): Map<String, Any?> {
        val value = Parser(text).parseRoot()
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?>
            ?: throw IllegalArgumentException("Expected a JSON object root")
    }

    fun parseObjectOrNull(text: String): Map<String, Any?>? {
        return runCatching { parseObject(text) }.getOrNull()
    }

    fun encodeObject(entries: Map<String, Any?>): String {
        val sb = StringBuilder()
        appendValue(sb, entries)
        return sb.toString()
    }

    fun stringValue(map: Map<String, Any?>?, key: String, default: String = ""): String {
        val raw = map?.get(key) ?: return default
        return scalarToString(raw) ?: default
    }

    fun intValue(map: Map<String, Any?>?, key: String, default: Int = 0): Int {
        val raw = map?.get(key) ?: return default
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: default
            else -> default
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun objectValue(map: Map<String, Any?>?, key: String): Map<String, Any?>? {
        return map?.get(key) as? Map<String, Any?>
    }

    fun stringListValue(map: Map<String, Any?>?, key: String): List<String> {
        val raw = map?.get(key) as? List<*> ?: return emptyList()
        return raw.mapNotNull { scalarToString(it)?.trim()?.takeIf(String::isNotEmpty) }
    }

    fun scalarToString(value: Any?): String? {
        return when (value) {
            null -> null
            is String -> value
            is Boolean -> value.toString()
            is Long -> value.toString()
            is Int -> value.toString()
            is Double -> {
                if (value % 1.0 == 0.0 && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) {
                    value.toLong().toString()
                } else {
                    value.toString()
                }
            }
            is Number -> value.toString()
            is List<*> -> value.mapNotNull { scalarToString(it) }.joinToString(",")
            else -> null
        }
    }

    private fun appendValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> appendEscapedString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Number -> {
                if (value is Double && (value.isNaN() || value.isInfinite())) {
                    sb.append("null")
                } else if (value is Float && (value.isNaN() || value.isInfinite())) {
                    sb.append("null")
                } else {
                    sb.append(value.toString())
                }
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    val key = k as? String ?: continue
                    if (!first) sb.append(',')
                    first = false
                    appendEscapedString(sb, key)
                    sb.append(':')
                    appendValue(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    appendValue(sb, item)
                }
                sb.append(']')
            }
            else -> appendEscapedString(sb, value.toString())
        }
    }

    private fun appendEscapedString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u")
                        sb.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun parseRoot(): Any? {
            skipWhitespace()
            val value = parseValue(depth = 0)
            skipWhitespace()
            if (pos != src.length) {
                fail("Unexpected trailing character '${src[pos]}' at index $pos")
            }
            return value
        }

        private fun parseValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("JSON nesting depth exceeded $MAX_DEPTH")
            skipWhitespace()
            if (pos >= src.length) fail("Unexpected end of JSON at index $pos")
            return when (val ch = src[pos]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> fail("Unexpected character '$ch' at index $pos")
            }
        }

        private fun parseObject(depth: Int): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val result = LinkedHashMap<String, Any?>()
            if (peek('}')) {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                if (pos >= src.length || src[pos] != '"') {
                    fail("Expected object key string at index $pos")
                }
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue(depth)
                result[key] = value
                skipWhitespace()
                when {
                    peek(',') -> pos++
                    peek('}') -> {
                        pos++
                        return result
                    }
                    else -> fail("Expected ',' or '}' in object at index $pos")
                }
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            expect('[')
            skipWhitespace()
            val result = ArrayList<Any?>()
            if (peek(']')) {
                pos++
                return result
            }
            while (true) {
                result.add(parseValue(depth))
                skipWhitespace()
                when {
                    peek(',') -> pos++
                    peek(']') -> {
                        pos++
                        return result
                    }
                    else -> fail("Expected ',' or ']' in array at index $pos")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when {
                    ch == '"' -> return sb.toString()
                    ch == '\\' -> {
                        if (pos >= src.length) fail("Unterminated escape at index $pos")
                        val esc = src[pos++]
                        when (esc) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) fail("Incomplete \\u escape at index $pos")
                                val hex = src.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: fail("Invalid \\u escape '\\u$hex' at index $pos")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("Invalid escape '\\$esc' at index ${pos - 1}")
                        }
                    }
                    ch.code < 0x20 -> fail("Unescaped control character in string at index ${pos - 1}")
                    else -> sb.append(ch)
                }
            }
            fail("Unterminated string literal")
        }

        private fun parseLiteral(expected: String, result: Any?): Any? {
            if (!src.startsWith(expected, startIndex = pos)) {
                fail("Invalid literal at index $pos")
            }
            pos += expected.length
            return result
        }

        private fun parseNumber(): Number {
            val start = pos
            if (peek('-')) pos++
            if (pos >= src.length) fail("Invalid number at index $start")
            if (src[pos] == '0') {
                pos++
            } else if (src[pos] in '1'..'9') {
                while (pos < src.length && src[pos] in '0'..'9') pos++
            } else {
                fail("Invalid number at index $start")
            }

            var isFloat = false
            if (peek('.')) {
                isFloat = true
                pos++
                if (pos >= src.length || src[pos] !in '0'..'9') {
                    fail("Invalid fraction in number at index $pos")
                }
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }

            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                if (pos >= src.length || src[pos] !in '0'..'9') {
                    fail("Invalid exponent in number at index $pos")
                }
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }

            val token = src.substring(start, pos)
            if (!isFloat) {
                token.toLongOrNull()?.let { return it }
            }
            return token.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
                ?: fail("Number out of range '$token' at index $start")
        }

        private fun skipWhitespace() {
            while (pos < src.length) {
                when (src[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        private fun peek(expected: Char): Boolean = pos < src.length && src[pos] == expected

        private fun expect(expected: Char) {
            if (pos >= src.length || src[pos] != expected) {
                fail("Expected '$expected' at index $pos")
            }
            pos++
        }

        private fun fail(message: String): Nothing {
            throw IllegalArgumentException(message)
        }
    }
}
