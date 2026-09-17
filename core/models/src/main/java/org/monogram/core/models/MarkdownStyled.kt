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

fun parseMarkdownMapped(raw: String): MappedMarkdown {
    if (raw.isEmpty()) {
        return MappedMarkdown(StyledText(""), intArrayOf(0))
    }
    val out = StringBuilder()
    val entities = mutableListOf<TextEntity>()
    val map = IntArray(raw.length + 1) { -1 }
    parseMarkdownRange(raw, 0, raw.length, out, entities, allowBlocks = true, map = map)
    var last = 0
    for (i in map.indices) {
        if (map[i] < 0) map[i] = last else last = map[i]
    }
    map[raw.length] = out.length
    return MappedMarkdown(StyledText(out.toString(), entities.sortedBy { it.offset }), map)
}

private fun parseMarkdownRange(
    raw: String,
    from: Int,
    to: Int,
    out: StringBuilder,
    entities: MutableList<TextEntity>,
    allowBlocks: Boolean,
    map: IntArray? = null,
) {
    var i = from
    while (i < to) {
        if (allowBlocks && atLineStart(raw, i, from)) {
            if (raw.startsWith("```", i)) {
                val fenceEnd = raw.indexOf('\n', i).let { if (it < 0 || it >= to) to else it }
                val lang = raw.substring(i + 3, fenceEnd).trim().ifBlank { null }
                val bodyStart = (fenceEnd + 1).coerceAtMost(to)
                val close = raw.indexOf("```", bodyStart).let { if (it < 0 || it >= to) -1 else it }
                if (close >= 0) {
                    var bodyEnd = close
                    if (bodyEnd > bodyStart && raw[bodyEnd - 1] == '\n') bodyEnd--
                    markOrig(map, i, bodyStart, out.length)
                    val start = out.length
                    markOrigRange(map, bodyStart, bodyEnd, out.length)
                    out.append(raw, bodyStart, bodyEnd)
                    entities += TextEntity("pre", start, out.length - start, lang)
                    i = (close + 3).coerceAtMost(to)
                    markOrig(map, close, i, out.length)
                    if (i < to && raw[i] == '\n') {
                        markOrig(map, i, i + 1, out.length)
                        i++
                    }
                    continue
                }
            }
            if (raw[i] == '>' || raw.startsWith("**>", i)) {
                val quoteStart = out.length
                data class QuoteLine(val depth: Int, val collapsed: Boolean, val from: Int, val to: Int)
                val quoteLines = mutableListOf<QuoteLine>()
                while (i < to && atLineStart(raw, i, from)) {
                    val lineEnd = raw.indexOf('\n', i).let { if (it < 0 || it >= to) to else it }
                    val line = raw.substring(i, lineEnd)
                    var s = line
                    var collapsed = false
                    var depth = 0
                    if (s.startsWith("**>")) {
                        collapsed = true
                        depth = 1
                        s = s.removePrefix("**>").removePrefix(" ")
                    } else if (s.startsWith(">")) {
                        while (s.startsWith(">")) {
                            depth++
                            s = s.removePrefix(">").removePrefix(" ")
                        }
                    } else {
                        break
                    }
                    val bodyFrom = lineEnd - s.length
                    quoteLines += QuoteLine(depth, collapsed, bodyFrom, lineEnd)
                    markOrig(map, i, bodyFrom, out.length)
                    i = if (lineEnd < to) lineEnd + 1 else to
                    if (lineEnd < to) markOrig(map, lineEnd, lineEnd + 1, out.length)
                }
                // Every depth level emits its own blockquote range (nested quotes).
                val lineSpans = mutableListOf<Triple<Int, Int, QuoteLine>>()
                quoteLines.forEachIndexed { index, line ->
                    if (index > 0) out.append('\n')
                    val start = out.length
                    parseMarkdownRange(raw, line.from, line.to, out, entities, allowBlocks = false, map = map)
                    lineSpans += Triple(start, out.length, line)
                }
                val maxDepth = quoteLines.maxOfOrNull { it.depth } ?: 1
                for (depth in 1..maxDepth) {
                    var runStart: Int? = null
                    var runEnd = 0
                    var collapsed = false
                    for ((start, end, line) in lineSpans) {
                        if (line.depth >= depth) {
                            if (runStart == null) {
                                runStart = start
                                collapsed = line.collapsed && depth == 1
                            }
                            runEnd = end
                            if (line.collapsed && depth == 1) collapsed = true
                        } else if (runStart != null) {
                            entities += TextEntity("blockquote", runStart, runEnd - runStart, if (collapsed) "collapsed" else null)
                            runStart = null
                            collapsed = false
                        }
                    }
                    if (runStart != null) {
                        entities += TextEntity("blockquote", runStart, runEnd - runStart, if (collapsed) "collapsed" else null)
                    }
                }
                continue
            }
            if (raw[i] == '#') {
                var hashes = 0
                while (i + hashes < to && raw[i + hashes] == '#' && hashes < 6) hashes++
                if (hashes in 1..6 && i + hashes < to && raw[i + hashes] == ' ') {
                    val lineEnd = raw.indexOf('\n', i).let { if (it < 0 || it >= to) to else it }
                    val contentStart = i + hashes + 1
                    markOrig(map, i, contentStart, out.length)
                    val start = out.length
                    parseMarkdownRange(raw, contentStart, lineEnd, out, entities, allowBlocks = false, map = map)
                    val len = out.length - start
                    if (len > 0) {
                        entities += TextEntity("heading", start, len, hashes.toString())
                        entities += TextEntity("bold", start, len)
                    }
                    i = if (lineEnd < to) lineEnd + 1 else to
                    if (lineEnd < to) markOrig(map, lineEnd, lineEnd + 1, out.length)
                    continue
                }
            }
        }
        when {
            matchWrap(raw, i, to, "***") != null -> {
                i = emitKindsWrap(raw, i, to, "***", listOf("bold", "italic"), out, entities, map = map)
            }
            matchWrap(raw, i, to, "**") != null -> {
                i = emitWrap(raw, i, to, "**", "bold", out, entities, map = map)
            }
            matchWrap(raw, i, to, "__") != null -> {
                i = emitWrap(raw, i, to, "__", "underline", out, entities, map = map)
            }
            matchWrap(raw, i, to, "~~") != null -> {
                i = emitWrap(raw, i, to, "~~", "strike", out, entities, map = map)
            }
            matchWrap(raw, i, to, "||") != null -> {
                i = emitWrap(raw, i, to, "||", "spoiler", out, entities, map = map)
            }
            singleWrapEnd(raw, i, to, '*') != null -> {
                i = emitSingleWrap(raw, i, to, '*', "italic", out, entities, map = map)
            }
            singleWrapEnd(raw, i, to, '_') != null -> {
                i = emitSingleWrap(raw, i, to, '_', "italic", out, entities, map = map)
            }
            matchWrap(raw, i, to, "`") != null -> {
                i = emitWrap(raw, i, to, "`", "code", out, entities, nested = false, map = map)
            }
            raw.startsWith("[", i) -> {
                val labelEnd = raw.indexOf(']', i + 1).takeIf { it in (i + 1) until to } ?: -1
                val urlStart = if (labelEnd >= 0 && labelEnd + 1 < to && raw[labelEnd + 1] == '(') {
                    labelEnd + 2
                } else {
                    -1
                }
                val urlEnd = if (urlStart >= 0) {
                    raw.indexOf(')', urlStart).takeIf { it in urlStart until to } ?: -1
                } else {
                    -1
                }
                if (labelEnd > i + 1 && urlEnd > urlStart) {
                    markOrig(map, i, i + 1, out.length)
                    val start = out.length
                    parseMarkdownRange(raw, i + 1, labelEnd, out, entities, allowBlocks = false, map = map)
                    markOrig(map, labelEnd, urlStart, out.length)
                    val url = raw.substring(urlStart, urlEnd)
                    markOrig(map, urlStart, urlEnd + 1, out.length)
                    entities += TextEntity("text_url", start, out.length - start, url)
                    i = urlEnd + 1
                } else {
                    markOrig(map, i, i + 1, out.length)
                    out.append(raw[i])
                    i++
                }
            }
            else -> {
                markOrig(map, i, i + 1, out.length)
                out.append(raw[i])
                i++
            }
        }
    }
}

private fun atLineStart(raw: String, i: Int, from: Int): Boolean =
    i == from || (i > 0 && raw[i - 1] == '\n')

private fun matchWrap(raw: String, start: Int, to: Int, delim: String): Int? {
    if (!raw.startsWith(delim, start) || start + delim.length >= to) return null
    val end = raw.indexOf(delim, start + delim.length)
    if (end < 0 || end >= to) return null
    return end
}

/** Closing marker for a single-character delimiter; `*` inside `**` is skipped. */
private fun singleWrapEnd(raw: String, start: Int, to: Int, ch: Char): Int? {
    if (start >= to || raw[start] != ch) return null
    var i = start + 1
    while (i < to) {
        if (raw[i] == ch) {
            val prevSame = i > start + 1 && raw[i - 1] == ch
            val nextSame = i + 1 < to && raw[i + 1] == ch
            if (!prevSame && !nextSame) return i
        }
        i++
    }
    return null
}

private fun emitSingleWrap(
    raw: String,
    start: Int,
    to: Int,
    ch: Char,
    kind: String,
    out: StringBuilder,
    entities: MutableList<TextEntity>,
    map: IntArray?,
): Int {
    val innerFrom = start + 1
    val end = singleWrapEnd(raw, start, to, ch) ?: return start + 1
    markOrig(map, start, innerFrom, out.length)
    val markStart = out.length
    parseMarkdownRange(raw, innerFrom, end, out, entities, allowBlocks = false, map = map)
    val len = out.length - markStart
    if (len > 0) entities += TextEntity(kind, markStart, len)
    markOrig(map, end, end + 1, out.length)
    return end + 1
}

private fun emitKindsWrap(
    raw: String,
    start: Int,
    to: Int,
    delim: String,
    kinds: List<String>,
    out: StringBuilder,
    entities: MutableList<TextEntity>,
    map: IntArray?,
): Int {
    val end = matchWrap(raw, start, to, delim) ?: return start + 1
    val innerFrom = start + delim.length
    markOrig(map, start, innerFrom, out.length)
    val markStart = out.length
    parseMarkdownRange(raw, innerFrom, end, out, entities, allowBlocks = false, map = map)
    val len = out.length - markStart
    if (len > 0) kinds.forEach { entities += TextEntity(it, markStart, len) }
    markOrig(map, end, end + delim.length, out.length)
    return end + delim.length
}

data class ComposerEdit(
    val text: String,
    val cursor: Int,
)

fun wrapMarkdown(text: String, start: Int, end: Int, left: String, right: String = left): ComposerEdit {
    val lo = start.coerceIn(0, text.length)
    val hi = end.coerceIn(lo, text.length)
    val selected = text.substring(lo, hi)
    val next = text.substring(0, lo) + left + selected + right + text.substring(hi)
    val cursor = if (selected.isEmpty()) lo + left.length else lo + left.length + selected.length + right.length
    return ComposerEdit(next, cursor)
}

fun wrapQuote(text: String, start: Int, end: Int): ComposerEdit {
    val lo = start.coerceIn(0, text.length)
    val hi = end.coerceIn(lo, text.length)
    val lineStart = if (lo == 0) 0 else text.lastIndexOf('\n', lo - 1).let { if (it < 0) 0 else it + 1 }
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
): Int {
    val end = matchWrap(raw, start, to, delim) ?: return start + 1
    val innerFrom = start + delim.length
    markOrig(map, start, innerFrom, out.length)
    val markStart = out.length
    if (nested) {
        parseMarkdownRange(raw, innerFrom, end, out, entities, allowBlocks = false, map = map)
    } else {
        markOrigRange(map, innerFrom, end, out.length)
        out.append(raw, innerFrom, end)
    }
    val len = out.length - markStart
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
