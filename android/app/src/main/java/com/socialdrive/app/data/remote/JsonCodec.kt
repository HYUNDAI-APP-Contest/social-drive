package com.socialdrive.app.data.remote

internal sealed interface JsonValue {
    data class Object(val values: Map<String, JsonValue>) : JsonValue
    data class Array(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val value: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

internal object JsonCodec {
    fun parse(bytes: ByteArray): JsonValue = Parser(bytes.toString(Charsets.UTF_8)).parse()

    fun encode(value: JsonValue): ByteArray = buildString { appendValue(value) }.toByteArray(Charsets.UTF_8)

    private fun StringBuilder.appendValue(value: JsonValue) {
        when (value) {
            is JsonValue.Object -> value.values.entries.joinTo(this, prefix = "{", postfix = "}") { (key, child) ->
                buildString { appendQuoted(key); append(':'); appendValue(child) }
            }
            is JsonValue.Array -> value.values.joinTo(this, prefix = "[", postfix = "]") { child ->
                buildString { appendValue(child) }
            }
            is JsonValue.StringValue -> appendQuoted(value.value)
            is JsonValue.NumberValue -> append(value.value)
            is JsonValue.BooleanValue -> append(value.value)
            JsonValue.NullValue -> append("null")
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    private class Parser(private val source: String) {
        private var index = 0
        private var nodes = 0

        fun parse(): JsonValue {
            val value = parseValue(depth = 0)
            whitespace()
            require(index == source.length) { "Trailing JSON content" }
            return value
        }

        private fun parseValue(depth: Int): JsonValue {
            require(depth <= MAX_DEPTH) { "JSON nesting limit exceeded" }
            nodes += 1
            require(nodes <= MAX_NODES) { "JSON node limit exceeded" }
            whitespace()
            require(index < source.length) { "Unexpected end of JSON" }
            return when (source[index]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> JsonValue.StringValue(parseString())
                't' -> literal("true", JsonValue.BooleanValue(true))
                'f' -> literal("false", JsonValue.BooleanValue(false))
                'n' -> literal("null", JsonValue.NullValue)
                '-', in '0'..'9' -> parseNumber()
                else -> error("Invalid JSON token")
            }
        }

        private fun parseObject(depth: Int): JsonValue.Object {
            index += 1
            whitespace()
            if (take('}')) return JsonValue.Object(emptyMap())
            val values = linkedMapOf<String, JsonValue>()
            while (true) {
                whitespace()
                require(index < source.length && source[index] == '"') { "Object key required" }
                val key = parseString()
                whitespace()
                require(take(':')) { "Object separator required" }
                require(values.put(key, parseValue(depth + 1)) == null) { "Duplicate JSON key" }
                whitespace()
                if (take('}')) break
                require(take(',')) { "Object item separator required" }
            }
            return JsonValue.Object(values)
        }

        private fun parseArray(depth: Int): JsonValue.Array {
            index += 1
            whitespace()
            if (take(']')) return JsonValue.Array(emptyList())
            val values = mutableListOf<JsonValue>()
            while (true) {
                values += parseValue(depth + 1)
                whitespace()
                if (take(']')) break
                require(take(',')) { "Array item separator required" }
            }
            return JsonValue.Array(values)
        }

        private fun parseString(): String {
            require(take('"'))
            val result = StringBuilder()
            while (index < source.length) {
                val character = source[index++]
                when (character) {
                    '"' -> return result.toString().also { require(it.length <= MAX_STRING_CHARS) { "JSON string limit exceeded" } }
                    '\\' -> {
                        require(index < source.length) { "Incomplete escape" }
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                require(index + 4 <= source.length) { "Incomplete unicode escape" }
                                val code = source.substring(index, index + 4).toInt(16)
                                result.append(code.toChar())
                                index += 4
                            }
                            else -> error("Invalid escape")
                        }
                    }
                    else -> {
                        require(character.code >= 0x20) { "Control character in string" }
                        result.append(character)
                    }
                }
                require(result.length <= MAX_STRING_CHARS) { "JSON string limit exceeded" }
            }
            error("Unterminated string")
        }

        private fun parseNumber(): JsonValue.NumberValue {
            val start = index
            take('-')
            if (take('0')) {
                require(index >= source.length || !source[index].isDigit()) { "Leading zero" }
            } else {
                require(index < source.length && source[index] in '1'..'9') { "Invalid number" }
                while (index < source.length && source[index].isDigit()) index += 1
            }
            if (take('.')) {
                require(index < source.length && source[index].isDigit()) { "Invalid fraction" }
                while (index < source.length && source[index].isDigit()) index += 1
            }
            if (index < source.length && source[index] in "eE") {
                index += 1
                if (index < source.length && source[index] in "+-") index += 1
                require(index < source.length && source[index].isDigit()) { "Invalid exponent" }
                while (index < source.length && source[index].isDigit()) index += 1
            }
            return JsonValue.NumberValue(source.substring(start, index))
        }

        private fun <T : JsonValue> literal(text: String, value: T): T {
            require(source.startsWith(text, index)) { "Invalid literal" }
            index += text.length
            return value
        }

        private fun take(character: Char): Boolean = if (index < source.length && source[index] == character) {
            index += 1
            true
        } else {
            false
        }

        private fun whitespace() {
            while (index < source.length && source[index] in " \n\r\t") index += 1
        }

        companion object {
            private const val MAX_DEPTH = 32
            private const val MAX_NODES = 10_000
            private const val MAX_STRING_CHARS = 16_384
        }
    }
}

internal fun JsonValue.objectValue(): Map<String, JsonValue> = (this as? JsonValue.Object)?.values
    ?: throw CloudContractException("INVALID_RESPONSE")

internal fun Map<String, JsonValue>.string(name: String): String = (get(name) as? JsonValue.StringValue)?.value
    ?: throw CloudContractException("INVALID_RESPONSE")

internal fun Map<String, JsonValue>.optionalString(name: String): String? = when (val value = get(name)) {
    null, JsonValue.NullValue -> null
    is JsonValue.StringValue -> value.value
    else -> throw CloudContractException("INVALID_RESPONSE")
}

internal fun Map<String, JsonValue>.integer(name: String): Int = (get(name) as? JsonValue.NumberValue)?.value?.toIntOrNull()
    ?: throw CloudContractException("INVALID_RESPONSE")

internal fun Map<String, JsonValue>.long(name: String): Long = (get(name) as? JsonValue.NumberValue)?.value?.toLongOrNull()
    ?: throw CloudContractException("INVALID_RESPONSE")

internal fun Map<String, JsonValue>.boolean(name: String): Boolean = (get(name) as? JsonValue.BooleanValue)?.value
    ?: throw CloudContractException("INVALID_RESPONSE")

internal fun Map<String, JsonValue>.array(name: String): List<JsonValue> = (get(name) as? JsonValue.Array)?.values
    ?: throw CloudContractException("INVALID_RESPONSE")

internal fun jsonObject(vararg values: Pair<String, JsonValue>): JsonValue.Object = JsonValue.Object(linkedMapOf(*values))
internal fun jsonString(value: String): JsonValue = JsonValue.StringValue(value)
internal fun jsonNumber(value: Number): JsonValue = JsonValue.NumberValue(value.toString())
internal fun jsonBoolean(value: Boolean): JsonValue = JsonValue.BooleanValue(value)
