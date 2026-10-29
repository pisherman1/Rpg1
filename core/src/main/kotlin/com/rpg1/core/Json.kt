package com.rpg1.core

/**
 * Minimal JSON for save files: maps, lists, strings, numbers, booleans and null.
 * Numbers parse as Long (or Double if they have a fraction or exponent).
 */
object Json {
    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(v, sb)
            is Boolean, is Int, is Long -> sb.append(v.toString())
            is Double -> sb.append(if (v.isFinite()) v.toString() else "0")
            is Map<*, *> -> {
                sb.append('{')
                v.entries.forEachIndexed { i, (k, value) ->
                    if (i > 0) sb.append(',')
                    writeString(k.toString(), sb)
                    sb.append(':')
                    write(value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                v.forEachIndexed { i, value ->
                    if (i > 0) sb.append(',')
                    write(value, sb)
                }
                sb.append(']')
            }
            is IntArray -> write(v.toList(), sb)
            is BooleanArray -> write(v.toList(), sb)
            else -> throw IllegalArgumentException("Can't write ${v::class.simpleName} as JSON")
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        if (p.i != text.length) p.fail("trailing characters")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun fail(msg: String): Nothing = throw IllegalArgumentException("Bad JSON at $i: $msg")

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("unexpected '$c'")
            }
        }

        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("expected $word")
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            skipWs()
            if (s.getOrNull(i) == '}') { i++; return m }
            while (true) {
                skipWs()
                if (s.getOrNull(i) != '"') fail("expected key")
                val k = str()
                skipWs()
                if (s.getOrNull(i) != ':') fail("expected ':'")
                i++
                m[k] = value()
                skipWs()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            skipWs()
            if (s.getOrNull(i) == ']') { i++; return l }
            while (true) {
                l += value()
                skipWs()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        fun str(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s.getOrNull(i++)) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("bad escape '$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
        }
    }
}

/** Typed accessors for parsed JSON objects; a missing or mistyped field throws. */
@Suppress("UNCHECKED_CAST")
class JsonObj(val map: Map<String, Any?>) {
    fun int(k: String): Int = (map[k] as? Number)?.toInt() ?: missing(k)
    fun long(k: String): Long = (map[k] as? Number)?.toLong() ?: missing(k)
    fun bool(k: String): Boolean = map[k] as? Boolean ?: missing(k)
    fun str(k: String): String = map[k] as? String ?: missing(k)
    fun strOrNull(k: String): String? = map[k] as? String
    fun obj(k: String): JsonObj = JsonObj(map[k] as? Map<String, Any?> ?: missing(k))
    fun list(k: String): List<Any?> = map[k] as? List<Any?> ?: missing(k)
    fun objs(k: String): List<JsonObj> = list(k).map { JsonObj(it as Map<String, Any?>) }
    fun ints(k: String): IntArray = list(k).map { (it as Number).toInt() }.toIntArray()
    fun bools(k: String): BooleanArray = list(k).map { it as Boolean }.toBooleanArray()
    fun has(k: String) = map.containsKey(k) && map[k] != null

    private fun missing(k: String): Nothing = throw IllegalArgumentException("Save file is missing '$k'")
}
