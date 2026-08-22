package org.monogram.tools.tl.codegen.validation

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

private const val DEFAULT_MAX_FILE_BYTES = 16 * 1024 * 1024
private const val DEFAULT_MAX_DEPTH = 128
private const val DEFAULT_MAX_ARRAY_ELEMENTS = 100_000
private const val DEFAULT_MAX_OBJECT_MEMBERS = 100_000
private const val DEFAULT_MAX_TOTAL_ELEMENTS = 500_000
private const val DEFAULT_MAX_STRING_CHARS = 1024 * 1024

data class JsonReaderLimits(
    val maxFileBytes: Int = DEFAULT_MAX_FILE_BYTES,
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
    val maxArrayElements: Int = DEFAULT_MAX_ARRAY_ELEMENTS,
    val maxObjectMembers: Int = DEFAULT_MAX_OBJECT_MEMBERS,
    val maxTotalElements: Int = DEFAULT_MAX_TOTAL_ELEMENTS,
    val maxStringChars: Int = DEFAULT_MAX_STRING_CHARS,
) {
    init {
        require(maxFileBytes in 1..DEFAULT_MAX_FILE_BYTES)
        require(maxDepth in 1..DEFAULT_MAX_DEPTH)
        require(maxArrayElements in 1..DEFAULT_MAX_ARRAY_ELEMENTS)
        require(maxObjectMembers in 1..DEFAULT_MAX_OBJECT_MEMBERS)
        require(maxTotalElements in 1..DEFAULT_MAX_TOTAL_ELEMENTS)
        require(maxStringChars in 1..DEFAULT_MAX_STRING_CHARS)
    }
}

internal data class StrictJsonText(
    val bytes: ByteArray,
    val text: String,
)

internal object StrictJsonInput {
    fun read(path: Path, limits: JsonReaderLimits): StrictJsonText {
        val bytes = Files.newInputStream(path).use { input ->
            input.readNBytes(limits.maxFileBytes + 1)
        }
        if (bytes.size > limits.maxFileBytes) {
            fail(path, SchemaValidationReason.FILE_TOO_LARGE, "$")
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xef.toByte() &&
            bytes[1] == 0xbb.toByte() &&
            bytes[2] == 0xbf.toByte()
        ) {
            fail(path, SchemaValidationReason.UTF8_BOM_NOT_ALLOWED, "$")
        }
        if (bytes.any { it == '\r'.code.toByte() }) {
            fail(path, SchemaValidationReason.INVALID_LINE_ENDINGS, "$")
        }
        if (bytes.isEmpty() || bytes.last() != '\n'.code.toByte() ||
            (bytes.size > 1 && bytes[bytes.lastIndex - 1] == '\n'.code.toByte())
        ) {
            fail(path, SchemaValidationReason.MISSING_FINAL_LINE_FEED, "$")
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            fail(path, SchemaValidationReason.INVALID_UTF8, "$", error)
        }
        JsonSyntaxScanner(path, text, limits).validate()
        return StrictJsonText(bytes, text)
    }
}

private class JsonSyntaxScanner(
    private val path: Path,
    private val text: String,
    private val limits: JsonReaderLimits,
) {
    private var index = 0
    private var totalElements = 0

    fun validate() {
        skipWhitespace()
        parseValue(1)
        skipWhitespace()
        if (index != text.length) malformed()
    }

    private fun parseValue(depth: Int) {
        if (depth > limits.maxDepth) {
            fail(path, SchemaValidationReason.JSON_DEPTH_EXCEEDED, location())
        }
        if (++totalElements > limits.maxTotalElements) {
            fail(path, SchemaValidationReason.JSON_MEMBER_LIMIT_EXCEEDED, location())
        }
        if (index >= text.length) malformed()
        when (text[index]) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> parseString()
            't' -> parseLiteral("true")
            'f' -> parseLiteral("false")
            'n' -> {
                if (text.startsWith("null", index)) parseLiteral("null") else nonFiniteOrMalformed()
            }
            'N', 'I' -> nonFiniteOrMalformed()
            '-' -> if (text.startsWith("-Infinity", index)) nonFiniteOrMalformed() else parseNumber()
            in '0'..'9' -> parseNumber()
            else -> malformed()
        }
    }

    private fun parseObject(depth: Int) {
        index++
        skipWhitespace()
        val keys = HashSet<String>()
        var count = 0
        if (take('}')) return
        while (true) {
            if (index >= text.length || text[index] != '"') malformed()
            val keyLocation = location()
            val key = parseString()
            if (!keys.add(key)) {
                fail(path, SchemaValidationReason.DUPLICATE_JSON_KEY, keyLocation)
            }
            if (++count > limits.maxObjectMembers) {
                fail(path, SchemaValidationReason.JSON_MEMBER_LIMIT_EXCEEDED, location())
            }
            skipWhitespace()
            requireChar(':')
            skipWhitespace()
            parseValue(depth + 1)
            skipWhitespace()
            if (take('}')) return
            requireChar(',')
            skipWhitespace()
        }
    }

    private fun parseArray(depth: Int) {
        index++
        skipWhitespace()
        var count = 0
        if (take(']')) return
        while (true) {
            if (++count > limits.maxArrayElements) {
                fail(path, SchemaValidationReason.JSON_ARRAY_LIMIT_EXCEEDED, location())
            }
            parseValue(depth + 1)
            skipWhitespace()
            if (take(']')) return
            requireChar(',')
            skipWhitespace()
        }
    }

    private fun parseString(): String {
        requireChar('"')
        val value = StringBuilder()
        while (index < text.length) {
            val char = text[index++]
            when {
                char == '"' -> return value.toString()
                char == '\\' -> parseEscape(value)
                char < ' ' -> malformed()
                else -> value.append(char)
            }
            if (value.length > limits.maxStringChars) {
                fail(path, SchemaValidationReason.JSON_STRING_LIMIT_EXCEEDED, location())
            }
        }
        malformed()
    }

    private fun parseEscape(value: StringBuilder) {
        if (index >= text.length) malformed()
        when (val escaped = text[index++]) {
            '"', '\\', '/' -> value.append(escaped)
            'b' -> value.append('\b')
            'f' -> value.append('\u000c')
            'n' -> value.append('\n')
            'r' -> value.append('\r')
            't' -> value.append('\t')
            'u' -> {
                val first = parseHexChar()
                if (first.isHighSurrogate()) {
                    if (index + 1 >= text.length || text[index] != '\\' || text[index + 1] != 'u') malformed()
                    index += 2
                    val second = parseHexChar()
                    if (!second.isLowSurrogate()) malformed()
                    value.append(first).append(second)
                } else {
                    if (first.isLowSurrogate()) malformed()
                    value.append(first)
                }
            }
            else -> malformed()
        }
    }

    private fun parseHexChar(): Char {
        if (index + 4 > text.length) malformed()
        var value = 0
        repeat(4) {
            val digit = text[index++].digitToIntOrNull(16) ?: malformed()
            value = value * 16 + digit
        }
        return value.toChar()
    }

    private fun parseNumber() {
        if (take('-') && index >= text.length) malformed()
        if (take('0')) {
            if (index < text.length && text[index].isDigit()) malformed()
        } else {
            if (index >= text.length || text[index] !in '1'..'9') malformed()
            while (index < text.length && text[index].isDigit()) index++
        }
        if (take('.')) {
            if (index >= text.length || !text[index].isDigit()) malformed()
            while (index < text.length && text[index].isDigit()) index++
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index++
            if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
            if (index >= text.length || !text[index].isDigit()) malformed()
            while (index < text.length && text[index].isDigit()) index++
        }
    }

    private fun parseLiteral(literal: String) {
        if (!text.startsWith(literal, index)) malformed()
        index += literal.length
    }

    private fun nonFiniteOrMalformed(): Nothing {
        val nonFinite = listOf("NaN", "Infinity", "-Infinity").any { text.startsWith(it, index) }
        if (nonFinite) fail(path, SchemaValidationReason.NON_FINITE_NUMBER, location())
        malformed()
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index] in charArrayOf(' ', '\t', '\r', '\n')) index++
    }

    private fun take(expected: Char): Boolean {
        if (index < text.length && text[index] == expected) {
            index++
            return true
        }
        return false
    }

    private fun requireChar(expected: Char) {
        if (!take(expected)) malformed()
    }

    private fun malformed(): Nothing = fail(path, SchemaValidationReason.MALFORMED_JSON, location())

    private fun location(): String {
        var line = 1
        var column = 1
        for (position in 0 until index.coerceAtMost(text.length)) {
            if (text[position] == '\n') {
                line++
                column = 1
            } else {
                column++
            }
        }
        return "line:$line,column:$column"
    }
}

internal fun fail(
    path: Path,
    reason: SchemaValidationReason,
    location: String,
    cause: Throwable? = null,
    declarationName: String? = null,
    schemaKey: org.monogram.tools.tl.codegen.model.TlSchemaKey? = null,
): Nothing = throw SchemaValidationException(
    artifactPath = path,
    reason = reason,
    location = location,
    declarationName = declarationName,
    schemaKey = schemaKey,
    cause = cause,
)
