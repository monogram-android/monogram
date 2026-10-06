package org.monogram.core.models

fun remapTextEntities(before: String, after: String, entities: List<TextEntity>): List<TextEntity> {
    if (before == after) return entities
    var prefix = 0
    while (prefix < minOf(before.length, after.length) && before[prefix] == after[prefix]) prefix++
    if (prefix > 0 && prefix < before.length && before[prefix].isLowSurrogate()) prefix--
    var suffix = 0
    while (suffix < minOf(before.length, after.length) - prefix &&
        before[before.lastIndex - suffix] == after[after.lastIndex - suffix]
    ) suffix++
    if (suffix > 0 && before[before.length - suffix].isLowSurrogate()) suffix--
    val oldEnd = before.length - suffix
    val newEnd = after.length - suffix
    val delta = after.length - before.length
    return entities.mapNotNull { entity ->
        val end = entity.offset + entity.length
        when {
            end <= prefix -> entity
            entity.offset >= oldEnd -> entity.copy(offset = entity.offset + delta)
            entity.kind in setOf("custom_emoji", "mention_name", "text_url") -> null
            else -> {
                val start = minOf(entity.offset, prefix)
                val nextEnd = if (end >= oldEnd) end + delta else newEnd
                entity.copy(offset = start, length = nextEnd - start).takeIf { it.length > 0 }
            }
        }
    }
}

fun prepareComposerText(
    raw: String,
    markdown: Boolean,
    entities: List<TextEntity> = emptyList(),
): StyledText {
    val leading = raw.length - raw.trimStart().length
    val input = raw.trim()
    val explicit = entities.mapNotNull { entity ->
        val start = maxOf(entity.offset, leading)
        val end = minOf(entity.offset + entity.length, leading + input.length)
        entity.copy(offset = start - leading, length = end - start).takeIf { it.length > 0 }
    }
    if (!markdown) return StyledText(input, normalizeChatEntities(explicit))
    // Preserve code, links and atomic emoji when interpreting surrounding Markdown.
    val protected = explicit.filter { it.kind in setOf("code", "pre", "custom_emoji", "text_url") }
    val masked = input.toCharArray()
    protected.forEach { entity ->
        for (i in entity.offset until entity.offset + entity.length) masked[i] = '\uE000'
    }
    val mapped = parseComposerMarkupMapped(String(masked))
    val output = mapped.styled.text.toCharArray()
    protected.forEach { entity ->
        for (i in entity.offset until entity.offset + entity.length) {
            output[mapped.origToDisp[i]] = input[i]
        }
    }
    val remapped = explicit.mapNotNull { entity ->
        val start = mapped.origToDisp[entity.offset]
        val end = mapped.origToDisp[entity.offset + entity.length]
        entity.copy(offset = start, length = end - start).takeIf { it.length > 0 }
    }
    return StyledText(String(output), normalizeChatEntities(mapped.styled.entities + remapped))
}

fun normalizeChatEntities(entities: List<TextEntity>): List<TextEntity> {
    val code = entities.filter { it.kind == "code" || it.kind == "pre" }
    return entities.flatMap { entity ->
        if (entity in code) return@flatMap listOf(entity)
        var parts = listOf(entity)
        code.forEach { opaque ->
            parts = parts.flatMap { part ->
                val end = part.offset + part.length
                val opaqueEnd = opaque.offset + opaque.length
                if (end <= opaque.offset || part.offset >= opaqueEnd) listOf(part)
                else buildList {
                    if (part.offset < opaque.offset) add(part.copy(length = opaque.offset - part.offset))
                    if (end > opaqueEnd) add(
                        part.copy(
                            offset = opaqueEnd,
                            length = end - opaqueEnd
                        )
                    )
                }
            }
        }
        parts
    }.distinct().sortedWith(compareBy<TextEntity> { it.offset }.thenByDescending { it.length })
}

fun toggleComposerEntity(
    text: String,
    entities: List<TextEntity>,
    from: Int,
    to: Int,
    kind: String,
    extra: String? = null
): List<TextEntity> {
    val start = from.coerceIn(0, text.length)
    val end = to.coerceIn(start, text.length)
    if (start == end) return entities
    val remove = kind == "clear" || entities.any {
        it.kind == kind && it.url == extra && it.offset <= start && it.offset + it.length >= end
    }
    val retained = entities.flatMap { entity ->
        val finish = entity.offset + entity.length
        if ((kind != "clear" && entity.kind != kind) || finish <= start || entity.offset >= end) listOf(
            entity
        )
        else buildList {
            if (entity.offset < start) add(entity.copy(length = start - entity.offset))
            if (finish > end) add(entity.copy(offset = end, length = finish - end))
        }
    }
    return normalizeChatEntities(
        retained + if (remove) emptyList() else listOf(
            TextEntity(
                kind,
                start,
                end - start,
                extra
            )
        )
    )
}
