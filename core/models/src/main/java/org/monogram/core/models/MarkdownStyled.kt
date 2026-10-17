package org.monogram.core.models

/**
 * Telegram styled text: Markdown -> plain + MessageEntity.
 * Docs: https://core.telegram.org/api/entities
 * Send: https://core.telegram.org/method/messages.sendMessage (`entities`)
 *
 * Offsets/lengths are UTF-16 code units (Kotlin [String] indices).
 */
data class StyledText(
    val text: String,
    val entities: List<TextEntity> = emptyList(),
)

fun parseMarkdownToStyled(raw: String): StyledText = parseMarkdownMapped(raw).styled

data class MappedMarkdown(
    val styled: StyledText,
    val origToDisp: IntArray,
)

fun parseMarkdownMapped(raw: String): MappedMarkdown = parseMappedMarkup(raw, extended = false)

fun parseComposerMarkupMapped(raw: String): MappedMarkdown = parseMappedMarkup(raw, extended = true)

private fun parseMappedMarkup(raw: String, extended: Boolean): MappedMarkdown {
    if (raw.isEmpty()) {
        return MappedMarkdown(StyledText(""), intArrayOf(0))
    }
    val out = StringBuilder()
    val entities = mutableListOf<TextEntity>()
    val map = IntArray(raw.length + 1) { -1 }
    parseMarkdownRange(
        raw,
        0,
        raw.length,
        out,
        entities,
        allowBlocks = true,
        map = map,
        extended = extended
    )
    var last = 0
    for (i in map.indices) {
        if (map[i] < 0) map[i] = last else last = map[i]
    }
    map[raw.length] = out.length
    return MappedMarkdown(
        StyledText(
            out.toString(),
            normalizeChatEntities(entities).sortedBy { it.offset }), map
    )
}

private fun parseMarkdownRange(
    raw: String,
    from: Int,
    to: Int,
    out: StringBuilder,
    entities: MutableList<TextEntity>,
    allowBlocks: Boolean,
    map: IntArray? = null,
    extended: Boolean = false,
    depth: Int = 0,
) {
    var i = from
    while (i < to) {
        if (extended && depth < 64) {
            val next = emitComposerMarkup(raw, i, to, out, entities, allowBlocks, map, depth)
            if (next != null) {
                i = next; continue
            }
        }
        if (allowBlocks && atLineStart(raw, i, from) && raw.startsWith("```", i)) {
            val lineEnd = raw.indexOf('\n', i + 3).takeIf { it in (i + 3) until to }
            val close = lineEnd?.let {
                raw.indexOf("```", it + 1).takeIf { end -> end in (it + 1) until to }
            }
            if (lineEnd != null && close != null) {
                val bodyStart = lineEnd + 1
                val bodyEnd = if (close > bodyStart && raw[close - 1] == '\n') close - 1 else close
                markOrig(map, i, bodyStart, out.length)
                val start = out.length
                markOrigRange(map, bodyStart, bodyEnd, out.length)
                out.append(raw, bodyStart, bodyEnd)
                val length = raw.substring(bodyStart, bodyEnd).trimEnd().length
                if (length > 0) entities += TextEntity(
                    "pre",
                    start,
                    length,
                    raw.substring(i + 3, lineEnd).trim().ifEmpty { null })
                markOrig(map, bodyEnd, close + 3, out.length)
                i = close + 3
                continue
            }
        }
        if (allowBlocks && atLineStart(raw, i, from) && (raw[i] == '>' || raw.startsWith(
                "**>",
                i
            ))
        ) {
            val start = out.length
            var collapsed = false
            var first = true
            while (i < to && (raw[i] == '>' || raw.startsWith("**>", i))) {
                val lineEnd = raw.indexOf('\n', i).takeIf { it in i until to } ?: to
                var bodyStart = i
                if (raw.startsWith("**>", i)) {
                    collapsed = true; bodyStart += 3
                } else while (bodyStart < lineEnd && raw[bodyStart] == '>') {
                    bodyStart++
                    if (bodyStart < lineEnd && raw[bodyStart] == ' ') bodyStart++
                }
                if (bodyStart < lineEnd && raw[bodyStart] == ' ') bodyStart++
                if (!first) out.append('\n')
                first = false
                markOrig(map, i, bodyStart, out.length)
                parseMarkdownRange(
                    raw,
                    bodyStart,
                    lineEnd,
                    out,
                    entities,
                    false,
                    map,
                    extended,
                    depth + 1
                )
                i = lineEnd
                if (i == to) break
                val next = i + 1
                if (next < to && (raw[next] == '>' || raw.startsWith("**>", next))) {
                    markOrig(map, i, next, out.length)
                    i = next
                } else break
            }
            val length = out.substring(start).trimEnd().length
            if (length > 0) entities += TextEntity(
                "blockquote",
                start,
                length,
                if (collapsed) "collapsed" else null
            )
            continue
        }
        val delimiter = when {
            raw.startsWith("**", i) -> "**"
            raw.startsWith("__", i) -> "__"
            raw.startsWith("~~", i) -> "~~"
            raw.startsWith("||", i) -> "||"
            raw[i] == '`' -> "`"
            else -> null
        }
        if (delimiter != null) {
            val close = closingMarker(raw, i + delimiter.length, to, delimiter, extended)
            if (close != null && close > i + delimiter.length) {
                val kind = when (delimiter) {
                    "**" -> "bold"; "__" -> "italic"; "~~" -> "strike"; "||" -> "spoiler"; else -> "code"
                }
                i = emitWrap(
                    raw,
                    i,
                    close + delimiter.length,
                    delimiter,
                    kind,
                    out,
                    entities,
                    nested = kind != "code",
                    map = map,
                    extended = extended,
                    depth = depth
                )
                continue
            }
        }
        if (raw[i] == '[' || raw.startsWith("![", i)) {
            val labelStart = if (raw[i] == '!') i + 2 else i + 1
            val labelEnd = raw.indexOf(']', labelStart).takeIf { it in (labelStart + 1) until to }
            val urlStart = labelEnd?.takeIf { it + 1 < to && raw[it + 1] == '(' }?.plus(2)
            val urlEnd = urlStart?.let { start ->
                raw.indexOf(')', start).takeIf { it in (start + 1) until to }
            }
            if (labelEnd != null && urlStart != null && urlEnd != null) {
                val start = out.length
                markOrig(map, i, labelStart, start)
                parseMarkdownRange(
                    raw,
                    labelStart,
                    labelEnd,
                    out,
                    entities,
                    false,
                    map,
                    extended,
                    depth + 1
                )
                markOrig(map, labelEnd, urlEnd + 1, out.length)
                val length = out.substring(start).trimEnd().length
                val url = raw.substring(urlStart, urlEnd)
                if (length > 0) entities += if (url.startsWith("tg://emoji?id=")) {
                    TextEntity("custom_emoji", start, length, url.removePrefix("tg://emoji?id="))
                } else TextEntity("text_url", start, length, url)
                i = urlEnd + 1
                continue
            }
        }
        if (raw[i] == '\\' && i + 1 < to && raw[i + 1] in "\\`*_~|[]()!#$>") {
            markOrig(map, i, i + 1, out.length)
            i++
        }
        markOrig(map, i, i + 1, out.length)
        out.append(raw[i++])
    }
}

private val composerHeading = Regex("#{1,6}[ \t]+(?=\\S)")
private val composerList = Regex("([ \t]*)([-+*]|[0-9]{1,9}[.)])[ \t]+(?=\\S)")
private val composerTag = Regex("<(/?)([A-Za-z][A-Za-z0-9-]*)([^<>]*)>")
private val composerAttribute =
    Regex("([A-Za-z][A-Za-z0-9-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
private val composerCharacter =
    Regex("&(?:amp|lt|gt|quot|apos|nbsp|#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6});")
private val composerContainerTags = setOf(
    "b",
    "strong",
    "i",
    "em",
    "u",
    "ins",
    "s",
    "strike",
    "del",
    "tg-spoiler",
    "span",
    "a",
    "tg-emoji",
    "code",
    "pre",
    "blockquote",
    "p",
    "div",
    "ul",
    "ol",
    "li",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
)

private fun htmlCharacter(value: String): String? = when (value) {
    "&amp;" -> "&"; "&lt;" -> "<"; "&gt;" -> ">"; "&quot;" -> "\""; "&apos;" -> "'"; "&nbsp;" -> "\u00a0"
    else -> {
        val number = value.removePrefix("&#").removeSuffix(";")
        val point = if (number.startsWith("x", true)) number.drop(1)
            .toIntOrNull(16) else number.toIntOrNull()
        point?.takeIf { Character.isValidCodePoint(it) && it !in 0xD800..0xDFFF && it != 0 }
            ?.let { String(Character.toChars(it)) }
    }
}

private fun decodeHtml(value: String): String =
    composerCharacter.replace(value) { htmlCharacter(it.value) ?: it.value }

private fun emitComposerMarkup(
    raw: String, from: Int, to: Int, out: StringBuilder, entities: MutableList<TextEntity>,
    allowBlocks: Boolean, map: IntArray?, depth: Int,
): Int? {
    if (allowBlocks && atLineStart(raw, from, 0)) {
        composerHeading.matchAt(raw, from)?.takeIf { it.range.last < to }?.let { heading ->
            val bodyStart = heading.range.last + 1
            val lineEnd = raw.indexOf('\n', bodyStart).takeIf { it in bodyStart until to } ?: to
            val start = out.length
            markOrig(map, from, bodyStart, start)
            parseMarkdownRange(raw, bodyStart, lineEnd, out, entities, false, map, true, depth + 1)
            if (out.length > start) entities += TextEntity("bold", start, out.length - start)
            return lineEnd
        }
        composerList.matchAt(raw, from)?.takeIf { it.range.last < to }?.let { list ->
            val bodyStart = list.range.last + 1
            val marker = list.groupValues[2]
            // Rules are literal text, and list markers have no Telegram entity.
            if (raw.substring(bodyStart, raw.indexOf('\n', bodyStart).takeIf { it >= 0 } ?: to)
                    .trim().all { it == marker.first() }) return@let
            val prefix =
                list.groupValues[1] + if (marker.length == 1) "• " else marker.dropLast(1) + ". "
            markOrig(map, from, bodyStart, out.length)
            out.append(prefix)
            val checkbox = when {
                raw.startsWith("[ ] ", bodyStart) -> "☐ "; raw.startsWith(
                    "[x] ",
                    bodyStart,
                    true
                ) -> "☑ "; else -> null
            }
            if (checkbox != null) {
                markOrig(map, bodyStart, bodyStart + 4, out.length)
                out.append(checkbox)
                return bodyStart + 4
            }
            return bodyStart
        }
    }
    if (raw[from] == '&') {
        val character = composerCharacter.matchAt(raw, from)?.takeIf { it.range.last < to }
        val decoded = character?.let { htmlCharacter(it.value) }
        if (character != null && decoded != null) {
            markOrig(map, from, character.range.last + 1, out.length)
            out.append(decoded)
            return character.range.last + 1
        }
    }
    if (raw[from] != '<') return null
    val open =
        composerTag.matchAt(raw, from)?.takeIf { it.range.last < to && it.groupValues[1].isEmpty() }
            ?: return null
    val name = open.groupValues[2].lowercase()
    val attributes = composerAttribute.findAll(open.groupValues[3]).associate {
        it.groupValues[1].lowercase() to decodeHtml(
            it.groupValues.drop(2).firstOrNull { value -> value.isNotEmpty() }.orEmpty()
        )
    }
    if (name == "br") {
        markOrig(map, from, open.range.last + 1, out.length)
        out.append('\n')
        return open.range.last + 1
    }
    val kind = when (name) {
        "b", "strong", "h1", "h2", "h3", "h4", "h5", "h6" -> "bold"
        "i", "em" -> "italic"; "u", "ins" -> "underline"; "s", "strike", "del" -> "strike"
        "tg-spoiler" -> "spoiler"
        "span" -> if (attributes["class"] == "tg-spoiler") "spoiler" else return null
        "a" -> if (!attributes["href"].isNullOrBlank()) "text_url" else return null
        "tg-emoji" -> if (attributes["emoji-id"]?.toLongOrNull()
                ?.let { it > 0 } == true
        ) "custom_emoji" else return null

        "code", "pre", "blockquote" -> name
        "p", "div", "ul", "ol", "li" -> null
        else -> return null
    }
    val stack = mutableListOf(name)
    var cursor = open.range.last + 1
    var closing: MatchResult? = null
    while (cursor < to) {
        val tag = composerTag.find(raw, cursor)?.takeIf { it.range.last < to } ?: break
        cursor = tag.range.last + 1
        val tagName = tag.groupValues[2].lowercase()
        if (kind == "code" || kind == "pre" || kind == "custom_emoji") {
            if (tagName == name && tag.groupValues[1] == "/") {
                closing = tag; break
            }
            continue
        }
        val opaque = stack.lastOrNull()
        if (opaque == "code" || opaque == "pre" || opaque == "tg-emoji") {
            if (tagName == opaque && tag.groupValues[1] == "/") stack.removeAt(stack.lastIndex)
            continue
        }
        if (tagName !in composerContainerTags) continue
        if (tag.groupValues[1] == "/") {
            if (stack.lastOrNull() != tagName) return null
            stack.removeAt(stack.lastIndex)
        } else stack.add(tagName)
        if (stack.isEmpty()) {
            closing = tag; break
        }
    }
    val close = closing ?: return null
    var bodyStart = open.range.last + 1
    var bodyEnd = close.range.first
    var extra = when (kind) {
        "text_url" -> attributes["href"]
        "custom_emoji" -> attributes["emoji-id"]
        "blockquote" -> if (Regex(
                "\\bexpandable\\b",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(open.groupValues[3])
        ) "collapsed" else null

        else -> null
    }
    if (kind == "pre") {
        val code = composerTag.matchAt(raw, bodyStart)
        val codeClose = composerTag.findAll(raw, bodyStart).firstOrNull {
            it.groupValues[1] == "/" && it.groupValues[2].equals(
                "code",
                true
            ) && it.range.last + 1 == bodyEnd
        }
        if (code != null && code.groupValues[2].equals("code", true) && codeClose != null) {
            extra = composerAttribute.findAll(code.groupValues[3])
                .firstOrNull { it.groupValues[1].equals("class", true) }
                ?.let { it.groupValues.drop(2).firstOrNull { value -> value.isNotEmpty() } }
                ?.takeIf { it.startsWith("language-") }?.removePrefix("language-")
            bodyStart = code.range.last + 1
            bodyEnd = codeClose.range.first
        }
    }
    if (name == "li") out.append("• ")
    markOrig(map, from, bodyStart, out.length)
    val start = out.length
    if (kind == "pre" || kind == "code" || kind == "custom_emoji") {
        var i = bodyStart
        while (i < bodyEnd) {
            val character = composerCharacter.matchAt(raw, i)?.takeIf { it.range.last < bodyEnd }
            val decoded = character?.let { htmlCharacter(it.value) }
            if (character != null && decoded != null) {
                markOrig(map, i, character.range.last + 1, out.length)
                out.append(decoded); i = character.range.last + 1
            } else {
                markOrig(map, i, i + 1, out.length); out.append(raw[i++])
            }
        }
    } else parseMarkdownRange(
        raw,
        bodyStart,
        bodyEnd,
        out,
        entities,
        allowBlocks,
        map,
        true,
        depth + 1
    )
    if (kind != null && out.length > start) {
        // Telegram forbids nested blockquote entities.
        if (kind == "blockquote") entities.removeAll { it.kind == "blockquote" && it.offset >= start }
        entities += TextEntity(kind, start, out.length - start, extra)
    }
    markOrig(map, bodyEnd, close.range.last + 1, out.length)
    if (name in setOf("p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6") &&
        close.range.last + 1 < to && raw[close.range.last + 1] != '\n' && out.lastOrNull() != '\n'
    ) out.append('\n')
    return close.range.last + 1
}

private fun atLineStart(raw: String, i: Int, from: Int): Boolean =
    i == from || (i > 0 && raw[i - 1] == '\n')

private fun closingMarker(
    raw: String,
    from: Int,
    to: Int,
    delimiter: String,
    extended: Boolean = false
): Int? {
    var i = from
    while (i < to) {
        if (raw[i] == '\\') {
            i += 2; continue
        }
        if (extended && delimiter != "`" && raw[i] == '<') {
            val tag = composerTag.matchAt(raw, i)
            val name = tag?.groupValues?.get(2)?.lowercase()
            if (tag != null && tag.groupValues[1].isEmpty() && (name == "code" || name == "pre")) {
                val close = composerTag.findAll(raw, tag.range.last + 1).firstOrNull {
                    it.range.last < to && it.groupValues[1] == "/" && it.groupValues[2].equals(
                        name,
                        true
                    )
                }
                if (close != null) {
                    i = close.range.last + 1; continue
                }
            }
        }
        if (raw.startsWith(delimiter, i) && i + delimiter.length <= to) return i
        if (delimiter != "`" && raw[i] == '`') {
            val close = raw.indexOf('`', i + 1).takeIf { it in (i + 1) until to }
            if (close != null) {
                i = close + 1; continue
            }
        }
        i++
    }
    return null
}

data class ComposerEdit(
    val text: String,
    val cursor: Int,
)

fun wrapMarkdown(
    text: String,
    start: Int,
    end: Int,
    left: String,
    right: String = left
): ComposerEdit {
    val lo = start.coerceIn(0, text.length)
    val hi = end.coerceIn(lo, text.length)
    val selected = text.substring(lo, hi)
    val next = text.substring(0, lo) + left + selected + right + text.substring(hi)
    val cursor =
        if (selected.isEmpty()) lo + left.length else lo + left.length + selected.length + right.length
    return ComposerEdit(next, cursor)
}

fun wrapQuote(text: String, start: Int, end: Int): ComposerEdit {
    val lo = start.coerceIn(0, text.length)
    val hi = end.coerceIn(lo, text.length)
    val lineStart =
        if (lo == 0) 0 else text.lastIndexOf('\n', lo - 1).let { if (it < 0) 0 else it + 1 }
    val selected = text.substring(lineStart, hi)
    val quoted = selected.lineSequence().joinToString("\n") { line -> nestQuoteLine(line) }
    val next = text.substring(0, lineStart) + quoted + text.substring(hi)
    return ComposerEdit(next, lineStart + quoted.length)
}

internal fun nestQuoteLine(line: String): String {
    if (line.startsWith("**>")) return ">$line"
    var depth = 0
    var rest = line
    while (rest.startsWith(">")) {
        depth++
        rest = rest.removePrefix(">")
        if (rest.startsWith(" ")) rest = rest.drop(1)
    }
    return ">".repeat(depth + 1) + " " + rest
}

private fun emitWrap(
    raw: String,
    start: Int,
    to: Int,
    delim: String,
    kind: String,
    out: StringBuilder,
    entities: MutableList<TextEntity>,
    nested: Boolean = true,
    map: IntArray? = null,
    extended: Boolean = false,
    depth: Int = 0,
): Int {
    val end = closingMarker(raw, start + delim.length, to, delim, extended) ?: return start + 1
    val innerFrom = start + delim.length
    markOrig(map, start, innerFrom, out.length)
    val markStart = out.length
    if (nested) {
        parseMarkdownRange(
            raw,
            innerFrom,
            end,
            out,
            entities,
            allowBlocks = false,
            map = map,
            extended = extended,
            depth = depth + 1
        )
    } else {
        markOrigRange(map, innerFrom, end, out.length)
        out.append(raw, innerFrom, end)
    }
    val len = out.substring(markStart).trimEnd().length
    if (len > 0) entities += TextEntity(kind, markStart, len)
    markOrig(map, end, end + delim.length, out.length)
    return end + delim.length
}

private fun markOrig(map: IntArray?, from: Int, to: Int, disp: Int) {
    if (map == null) return
    val hi = minOf(to, map.lastIndex)
    val lo = maxOf(from, 0)
    for (i in lo until hi) map[i] = disp
}

private fun markOrigRange(map: IntArray?, from: Int, to: Int, dispStart: Int) {
    if (map == null) return
    val hi = minOf(to, map.lastIndex)
    val lo = maxOf(from, 0)
    for (i in lo until hi) map[i] = dispStart + (i - lo)
}
