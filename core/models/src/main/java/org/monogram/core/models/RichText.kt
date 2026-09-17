package org.monogram.core.models

sealed class RichBlock {
    data class Paragraph(
        val text: String,
        val entities: List<TextEntity> = emptyList(),
    ) : RichBlock()

    data class Code(
        val text: String,
        val language: String? = null,
    ) : RichBlock()

    data class Quote(
        val text: String,
        val entities: List<TextEntity> = emptyList(),
        val collapsed: Boolean = false,
        val level: Int = 1,
    ) : RichBlock()

    data class TaskList(
        val items: List<TaskItem> = emptyList(),
    ) : RichBlock()

    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
    ) : RichBlock()

    data class Heading(
        val text: String,
        val level: Int,
        val entities: List<TextEntity> = emptyList(),
    ) : RichBlock()

    data object Rule : RichBlock()

    data class Details(
        val title: String,
        val children: List<RichBlock> = emptyList(),
    ) : RichBlock()

    data class Photo(
        val cacheKey: String,
        val width: Int = 0,
        val height: Int = 0,
    ) : RichBlock()
}

data class TaskItem(
    val text: String,
    val done: Boolean,
    val entities: List<TextEntity> = emptyList(),
)

fun splitRichText(text: String, entities: List<TextEntity> = emptyList()): List<RichBlock> {
    if (text.isEmpty()) return emptyList()
    val hasBlockEntities = entities.any {
        it.kind == "pre" || it.kind == "blockquote" || it.kind == "heading" ||
            it.kind == "details" || it.kind == "photo"
    }
    val blocks = when {
        hasBlockEntities -> splitByBlockEntities(text, entities)
        entities.isNotEmpty() -> listOf(RichBlock.Paragraph(text, entities))
        else -> splitMarkdownFallback(text)
    }
    return blocks
        .map { it.trimBlock() }
        .flatMap { it.explodeRules() }
        .filterNot { it.isBlankBlock() }
        .ifEmpty { listOf(RichBlock.Paragraph(text, entities)) }
}

private fun splitByBlockEntities(text: String, entities: List<TextEntity>): List<RichBlock> {
    val blocks = mutableListOf<RichBlock>()
    val sorted = entities
        .filter {
            it.kind == "pre" || it.kind == "blockquote" || it.kind == "heading" ||
                it.kind == "details" || it.kind == "photo"
        }
        .sortedBy { it.offset }
    var cursor = 0
    val len = text.length
    for (entity in sorted) {
        val start = entity.offset.coerceIn(0, len)
        val end = (entity.offset + entity.length).coerceIn(start, len)
        if (start < cursor) continue
        if (start > cursor) {
            blocks += explodeParagraph(text.substring(cursor, start), shiftEntities(entities, cursor, start))
        }
        val slice = text.substring(start, end)
        when (entity.kind) {
            "pre" -> {
                val body = slice.trimEnd('\n')
                parseMarkdownTable(body)?.let { blocks += it }
                    ?: blocks.add(RichBlock.Code(text = body, language = entity.url))
            }
            "heading" -> {
                val level = entity.url?.toIntOrNull()?.coerceIn(1, 6) ?: 1
                blocks += RichBlock.Heading(
                    text = slice.trim(),
                    level = level,
                    entities = shiftEntities(entities, start, end, skipBlock = true),
                )
            }
            "photo" -> {
                blocks += parsePhotoEntity(entity.url)
            }
            "details" -> {
                val splitAt = slice.indexOf('\n')
                val title = if (splitAt < 0) slice.trim() else slice.take(splitAt).trim()
                val body = if (splitAt < 0) "" else slice.substring(splitAt + 1)
                val innerStart = start + if (splitAt < 0) slice.length else splitAt + 1
                val children = if (body.isBlank()) {
                    emptyList()
                } else {
                    splitRichText(body, shiftEntities(entities, innerStart, end, skipBlock = true))
                }
                blocks += RichBlock.Details(title = title, children = children)
            }
            else -> blocks += RichBlock.Quote(
                text = slice.trim(),
                entities = shiftEntities(entities, start, end, skipBlock = true),
                collapsed = entity.url == "collapsed",
            )
        }
        cursor = end
        if (cursor < len && text[cursor] == '\n') cursor++
    }
    if (cursor < len) {
        blocks += explodeParagraph(text.substring(cursor), shiftEntities(entities, cursor, len))
    }
    return blocks
}

private fun splitMarkdownFallback(text: String): List<RichBlock> {
    val blocks = mutableListOf<RichBlock>()
    val lines = text.split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val fence = fenceLanguage(line)
        if (fence != null) {
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            if (i < lines.size) i++
            val code = body.toString()
            parseMarkdownTable(code)?.let { blocks += it } ?: blocks.add(RichBlock.Code(code, fence.ifBlank { null }))
            continue
        }
        if (isRuleLine(line)) {
            blocks += RichBlock.Rule
            i++
            continue
        }
        if (isTableRow(line)) {
            val tableLines = mutableListOf<String>()
            while (i < lines.size && isTableRow(lines[i])) {
                tableLines += lines[i]
                i++
            }
            parseMarkdownTable(tableLines.joinToString("\n"))?.let { blocks += it }
                ?: blocks.add(RichBlock.Paragraph(tableLines.joinToString("\n")))
            continue
        }
        val heading = headingLine(line)
        if (heading != null) {
            blocks += RichBlock.Heading(text = heading.second, level = heading.first)
            i++
            continue
        }
        if (taskLine(line) != null) {
            val items = mutableListOf<TaskItem>()
            while (i < lines.size) {
                val task = taskLine(lines[i]) ?: break
                items += task
                i++
            }
            blocks += RichBlock.TaskList(items)
            continue
        }
        if (isListLine(line)) {
            val items = StringBuilder()
            while (i < lines.size && isListLine(lines[i]) && taskLine(lines[i]) == null) {
                if (items.isNotEmpty()) items.append('\n')
                items.append(normalizeListLine(lines[i]))
                i++
            }
            blocks += RichBlock.Paragraph(items.toString())
            continue
        }
        if (line.startsWith(">") || line.startsWith("**>")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && (lines[i].startsWith(">") || lines[i].startsWith("**>"))) {
                quoteLines += lines[i]
                i++
            }
            blocks += markdownQuote(quoteLines)
            continue
        }
        val para = StringBuilder()
        while (
            i < lines.size &&
            fenceLanguage(lines[i]) == null &&
            !isTableRow(lines[i]) &&
            !isRuleLine(lines[i]) &&
            !isListLine(lines[i]) &&
            headingLine(lines[i]) == null &&
            !lines[i].startsWith(">") &&
            !lines[i].startsWith("**>") &&
            taskLine(lines[i]) == null
        ) {
            if (para.isNotEmpty()) para.append('\n')
            para.append(lines[i])
            i++
        }
        if (para.isNotEmpty()) blocks += RichBlock.Paragraph(para.toString())
    }
    return blocks
}

private const val OBJECT_REPLACEMENT = '\uFFFC'

private fun explodeParagraph(text: String, entities: List<TextEntity>): List<RichBlock> {
    if (text.contains(OBJECT_REPLACEMENT)) {
        val out = mutableListOf<RichBlock>()
        var from = 0
        while (from < text.length) {
            val at = text.indexOf(OBJECT_REPLACEMENT, from)
            if (at < 0) {
                out += explodePlainGap(text.substring(from), shiftEntities(entities, from, text.length))
                break
            }
            if (at > from) {
                out += explodePlainGap(text.substring(from, at), shiftEntities(entities, from, at))
            }
            val photo = entities.firstOrNull { entity ->
                entity.kind == "photo" && entity.offset <= at && entity.offset + entity.length >= at + 1
            } ?: entities.firstOrNull { it.kind == "photo" }
            out += parsePhotoEntity(photo?.url)
            from = at + 1
        }
        return out
    }
    return explodePlainGap(text, entities)
}

private fun explodePlainGap(text: String, entities: List<TextEntity>): List<RichBlock> {
    val trimmed = text.trim { it == '\n' || it == OBJECT_REPLACEMENT }
    if (trimmed.isBlank()) return emptyList()
    if (isRuleLine(trimmed)) return listOf(RichBlock.Rule)
    if (entities.isEmpty()) {
        parseMarkdownTable(trimmed.trim())?.let { return listOf(it) }
    }
    val lines = trimmed.lines()
    val out = mutableListOf<RichBlock>()
    var i = 0
    while (i < lines.size) {
        if (taskLine(lines[i]) != null) {
            val items = mutableListOf<TaskItem>()
            while (i < lines.size) {
                val task = taskLine(lines[i]) ?: break
                items += task
                i++
            }
            out += RichBlock.TaskList(items)
            continue
        }
        val para = StringBuilder()
        val paraStart = i
        while (i < lines.size && taskLine(lines[i]) == null) {
            if (para.isNotEmpty()) para.append('\n')
            para.append(lines[i])
            i++
        }
        val chunk = para.toString()
        if (chunk.isNotBlank()) {
            val start = lines.take(paraStart).sumOf { it.length + 1 }
            out += RichBlock.Paragraph(
                chunk.replace(OBJECT_REPLACEMENT.toString(), ""),
                shiftEntities(entities, start, start + chunk.length),
            )
        }
    }
    return out.ifEmpty { listOf(RichBlock.Paragraph(trimmed.replace(OBJECT_REPLACEMENT.toString(), ""), entities)) }
}

private fun parseMarkdownTable(text: String): RichBlock.Table? {
    val rows = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { parseTableRow(it) }
        .filter { it.size >= 2 }
    if (rows.size < 2) return null
    val header = rows.first()
    val body = rows.drop(1).filterNot { row -> row.all { cell -> cell.all { it == '-' || it == ':' || it == ' ' } } }
    if (body.isEmpty()) return null
    val width = header.size
    return RichBlock.Table(
        headers = header,
        rows = body.map { row -> (0 until width).map { idx -> row.getOrElse(idx) { "" } } },
    )
}

private fun parseTableRow(line: String): List<String> {
    var body = line.trim()
    if (body.startsWith("|")) body = body.drop(1)
    if (body.endsWith("|")) body = body.dropLast(1)
    return body.split('|').map { it.trim() }
}

private fun headingLine(line: String): Pair<Int, String>? {
    val trimmed = line.trimStart()
    var hashes = 0
    while (hashes < trimmed.length && trimmed[hashes] == '#' && hashes < 6) hashes++
    if (hashes in 1..6 && hashes < trimmed.length && trimmed[hashes] == ' ') {
        return hashes to trimmed.substring(hashes + 1).trim()
    }
    return null
}

private fun isListLine(line: String): Boolean {
    val trimmed = line.trimStart()
    if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
        return true
    }
    var i = 0
    while (i < trimmed.length && trimmed[i].isDigit()) i++
    return i > 0 && i + 1 < trimmed.length && trimmed[i] == '.' && trimmed[i + 1] == ' '
}

private fun normalizeListLine(line: String): String {
    val indent = line.takeWhile { it == ' ' }.length
    val trimmed = line.trimStart()
    val bullet = when {
        trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") ->
            "• " + trimmed.drop(2)
        else -> {
            var i = 0
            while (i < trimmed.length && trimmed[i].isDigit()) i++
            if (i > 0 && i + 1 < trimmed.length && trimmed[i] == '.' && trimmed[i + 1] == ' ') {
                "• " + trimmed.substring(i + 2)
            } else {
                trimmed
            }
        }
    }
    return " ".repeat(indent / 2 * 2) + bullet
}

internal fun isRuleLine(line: String): Boolean {
    val compact = line.trim().filterNot { it.isWhitespace() }
    if (compact.length < 3) return false
    return compact.all { it == '-' || it == '–' || it == '—' || it == '*' || it == '_' || it == '−' }
}

private fun isTableRow(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.count { it == '|' } >= 2
}

private fun fenceLanguage(line: String): String? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("```")) return null
    return trimmed.removePrefix("```").trim()
}

internal fun markdownQuote(lines: List<String>): RichBlock.Quote {
    data class QuoteLine(val depth: Int, val collapsed: Boolean, val body: String)
    val parsed = lines.map { line ->
        var s = line
        if (s.startsWith("**>")) {
            s = s.removePrefix("**>").removePrefix(" ")
            QuoteLine(1, true, s.trimEnd())
        } else {
            var depth = 0
            while (s.startsWith(">")) {
                depth++
                s = s.removePrefix(">").removePrefix(" ")
            }
            QuoteLine(depth.coerceAtLeast(1), false, s.trimEnd())
        }
    }
    val text = parsed.joinToString("\n") { it.body }
    var cursor = 0
    val lineSpans = parsed.map { line ->
        val start = cursor
        val end = start + line.body.length
        cursor = end + 1
        Triple(start, end, line)
    }
    val maxDepth = parsed.maxOf { it.depth }
    val runs = mutableListOf<Triple<Int, Int, Int>>()
    for (depth in 1..maxDepth) {
        var runStart: Int? = null
        var runEnd = 0
        for ((start, end, line) in lineSpans) {
            if (line.depth >= depth) {
                if (runStart == null) runStart = start
                runEnd = end
            } else if (runStart != null) {
                runs += Triple(runStart, runEnd - runStart, depth)
                runStart = null
            }
        }
        if (runStart != null) runs += Triple(runStart, runEnd - runStart, depth)
    }
    // Covering runs are the block's levels; strictly inner runs stay nested.
    var level = 0
    val inner = mutableListOf<TextEntity>()
    for ((start, length, _) in runs) {
        if (start <= 0 && start + length >= text.length) level++ else inner += TextEntity("blockquote", start, length)
    }
    return RichBlock.Quote(
        text = text,
        entities = inner,
        collapsed = parsed.any { it.collapsed },
        level = level.coerceAtLeast(1),
    )
}

internal fun parsePhotoEntity(url: String?): RichBlock.Photo {
    val parts = url.orEmpty().split(':')
    val id = parts.getOrNull(1).orEmpty()
    val cacheKey = if (id.isNotEmpty()) "photo:$id" else url.orEmpty()
    val dims = parts.getOrNull(2)?.split('x').orEmpty()
    return RichBlock.Photo(
        cacheKey = cacheKey,
        width = dims.getOrNull(0)?.toIntOrNull() ?: 0,
        height = dims.getOrNull(1)?.toIntOrNull() ?: 0,
    )
}

internal fun taskLine(line: String): TaskItem? {
    val trimmed = line.trimStart().replace("\uFE0F", "").replace("\uFE0E", "")
    val mark = trimmed.firstOrNull()
    if (mark == '☑' || mark == '✅' || mark == '✔' || mark == '☒') {
        return TaskItem(trimmed.drop(1).trim(), done = true)
    }
    if (mark == '☐' || mark == '□' || mark == '▢' || mark == '○') {
        return TaskItem(trimmed.drop(1).trim(), done = false)
    }
    val rest = when {
        trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> trimmed.drop(2)
        else -> trimmed
    }
    return when {
        rest.startsWith("[x] ", ignoreCase = true) -> TaskItem(rest.drop(4).trimEnd(), done = true)
        rest.startsWith("[ ] ") -> TaskItem(rest.drop(4).trimEnd(), done = false)
        else -> null
    }
}

private fun shiftEntities(
    entities: List<TextEntity>,
    start: Int,
    end: Int,
    skipBlock: Boolean = false,
): List<TextEntity> = entities.mapNotNull { entity ->
    val covers = entity.offset <= start && entity.offset + entity.length >= end
    if (skipBlock && covers && (entity.kind == "pre" || entity.kind == "blockquote" || entity.kind == "heading" || entity.kind == "details" || entity.kind == "photo")) {
        return@mapNotNull null
    }
    if (entity.kind == "pre" || entity.kind == "heading" || entity.kind == "details" || entity.kind == "photo") {
        return@mapNotNull null
    }
    if (entity.kind == "blockquote" && covers) {
        return@mapNotNull null
    }
    val entityStart = entity.offset
    val entityEnd = entity.offset + entity.length
    val lo = maxOf(entityStart, start)
    val hi = minOf(entityEnd, end)
    if (lo >= hi) return@mapNotNull null
    entity.copy(offset = lo - start, length = hi - lo)
}

private fun RichBlock.trimBlock(): RichBlock = when (this) {
    is RichBlock.Paragraph -> copy(text = text.trim('\n'))
    is RichBlock.Code -> copy(text = text.trim('\n'))
    is RichBlock.Quote -> copy(text = text.trim('\n'))
    is RichBlock.TaskList -> copy(items = items.map { it.copy(text = it.text.trim('\n')) })
    is RichBlock.Heading -> copy(text = text.trim('\n'))
    is RichBlock.Table -> this
    is RichBlock.Rule -> this
    is RichBlock.Details -> copy(
        title = title.trim('\n'),
        children = children.map { it.trimBlock() },
    )
    is RichBlock.Photo -> this
}

private fun RichBlock.isBlankBlock(): Boolean = when (this) {
    is RichBlock.Paragraph -> text.isBlank()
    is RichBlock.Code -> text.isBlank()
    is RichBlock.Quote -> text.isBlank()
    is RichBlock.TaskList -> items.isEmpty() || items.all { it.text.isBlank() }
    is RichBlock.Heading -> text.isBlank()
    is RichBlock.Table -> headers.isEmpty()
    is RichBlock.Rule -> false
    is RichBlock.Details -> title.isBlank() && children.all { it.isBlankBlock() }
    is RichBlock.Photo -> false
}

private fun RichBlock.explodeRules(): List<RichBlock> {
    val paragraph = this as? RichBlock.Paragraph ?: return listOf(this)
    val lines = paragraph.text.split('\n')
    if (lines.none { isRuleLine(it) }) return listOf(this)
    return lines.map { line ->
        if (isRuleLine(line)) RichBlock.Rule else RichBlock.Paragraph(line, paragraph.entities)
    }
}
