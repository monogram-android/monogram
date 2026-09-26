package org.monogram.core.models

object TextEntities {
    fun parse(json: String?): List<TextEntity> {
        if (json.isNullOrBlank()) return emptyList()
        val body = json.trim()
        if (!body.startsWith("[") || !body.endsWith("]")) return emptyList()
        val rows = CompactJson.parse(body) as? List<*> ?: return emptyList()
        return rows.mapNotNull { row ->
            val obj = row as? Map<*, *> ?: return@mapNotNull null
            val kind = obj.jsonString("kind") ?: return@mapNotNull null
            TextEntity(
                kind = kind,
                offset = obj.jsonInt("offset") ?: 0,
                length = obj.jsonInt("length") ?: 0,
                url = obj.jsonString("url"),
            )
        }
    }

    fun serialize(entities: List<TextEntity>): String? {
        if (entities.isEmpty()) return null
        return entities.joinToString(separator = ",", prefix = "[", postfix = "]") { entity ->
            buildString {
                append('{')
                append("\"kind\":").append(quote(entity.kind))
                append(",\"offset\":").append(entity.offset)
                append(",\"length\":").append(entity.length)
                entity.url?.let { append(",\"url\":").append(quote(it)) }
                append('}')
            }
        }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                else -> append(ch)
            }
        }
        append('"')
    }
}

/** Telegram-style Markdown fallback when the server sent no entities. */
fun parseInlineMarkdown(text: String): List<TextEntity> {
    if (text.isEmpty()) return emptyList()
    if (text.none { it == '*' || it == '_' || it == '`' || it == '~' || it == '[' || it == '|' || it == '#' }) {
        return emptyList()
    }
    val entities = mutableListOf<TextEntity>()
    var i = 0
    val n = text.length
    while (i < n) {
        when {
            matchDelim(text, i, "**") != null -> {
                val end = matchDelim(text, i, "**")!!
                entities += TextEntity("bold", i + 2, end - (i + 2))
                i = end + 2
            }
            matchDelim(text, i, "__") != null -> {
                val end = matchDelim(text, i, "__")!!
                entities += TextEntity("underline", i + 2, end - (i + 2))
                i = end + 2
            }
            matchDelim(text, i, "~~") != null -> {
                val end = matchDelim(text, i, "~~")!!
                entities += TextEntity("strike", i + 2, end - (i + 2))
                i = end + 2
            }
            matchDelim(text, i, "||") != null -> {
                val end = matchDelim(text, i, "||")!!
                entities += TextEntity("spoiler", i + 2, end - (i + 2))
                i = end + 2
            }
            matchDelim(text, i, "*") != null -> {
                val end = matchDelim(text, i, "*")!!
                entities += TextEntity("italic", i + 1, end - (i + 1))
                i = end + 1
            }
            matchDelim(text, i, "_") != null -> {
                val end = matchDelim(text, i, "_")!!
                entities += TextEntity("italic", i + 1, end - (i + 1))
                i = end + 1
            }
            matchDelim(text, i, "`") != null -> {
                val end = matchDelim(text, i, "`")!!
                entities += TextEntity("code", i + 1, end - (i + 1))
                i = end + 1
            }
            text.startsWith("[", i) -> {
                val labelEnd = text.indexOf(']', i + 1)
                val urlStart = if (labelEnd >= 0 && labelEnd + 1 < n && text[labelEnd + 1] == '(') {
                    labelEnd + 2
                } else {
                    -1
                }
                val urlEnd = if (urlStart >= 0) text.indexOf(')', urlStart) else -1
                if (labelEnd > i + 1 && urlEnd > urlStart) {
                    entities += TextEntity(
                        kind = "text_url",
                        offset = i + 1,
                        length = labelEnd - (i + 1),
                        url = text.substring(urlStart, urlEnd),
                    )
                    i = urlEnd + 1
                } else {
                    i++
                }
            }
            else -> i++
        }
    }
    addMarkdownHeadings(text, entities)
    return entities.sortedBy { it.offset }
}

private fun addMarkdownHeadings(text: String, entities: MutableList<TextEntity>) {
    var lineStart = 0
    while (lineStart < text.length) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val trimmed = line.trimStart()
        var hashes = 0
        while (hashes < trimmed.length && trimmed[hashes] == '#') hashes++
        if (hashes in 1..6 && hashes < trimmed.length && trimmed[hashes] == ' ') {
            val contentStart = lineStart + (line.length - trimmed.length) + hashes + 1
            if (contentStart < lineEnd) {
                entities += TextEntity("bold", contentStart, lineEnd - contentStart)
            }
        }
        lineStart = (lineEnd + 1).coerceAtMost(text.length)
        if (lineEnd == text.length) break
    }
}

private fun matchDelim(text: String, start: Int, delim: String): Int? {
    if (!text.startsWith(delim, start)) return null
    if (start + delim.length >= text.length) return null
    val end = text.indexOf(delim, start + delim.length)
    return end.takeIf { it > start + delim.length }
}
