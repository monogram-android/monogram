package org.monogram.presentation.features.chats.conversation.ui.inputbar

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import org.monogram.domain.models.MessageEntityType

fun buildEditorPreviewAnnotatedString(
    source: AnnotatedString,
    primaryColor: Color
): AnnotatedString {
    val builder = AnnotatedString.Builder(source.text)

    source.getStringAnnotations(0, source.length)
        .filter { it.tag != RICH_ENTITY_TAG && it.tag != MENTION_TAG }
        .forEach { builder.addStringAnnotation(it.tag, it.item, it.start, it.end) }

    source.getStringAnnotations(MENTION_TAG, 0, source.length).forEach { annotation ->
        builder.addStyle(SpanStyle(color = primaryColor), annotation.start, annotation.end)
    }

    Regex("@(\\w+)").findAll(source.text).forEach { match ->
        builder.addStyle(
            SpanStyle(color = primaryColor),
            match.range.first,
            match.range.last + 1
        )
    }

    source.getStringAnnotations(RICH_ENTITY_TAG, 0, source.length).forEach { annotation ->
        val style = decodeRichEntity(annotation.item)?.toPreviewStyle(primaryColor)
        if (style != null && annotation.start < annotation.end) {
            builder.addStyle(style, annotation.start, annotation.end)
        }
    }

    return builder.toAnnotatedString()
}

fun applyMarkdownFormatting(value: TextFieldValue): TextFieldValue {
    val input = value.text
    val output = StringBuilder()
    val ranges = mutableListOf<Triple<Int, Int, MessageEntityType>>()
    val markerStarts = mutableMapOf<String, MutableList<Int>>()
    var i = 0

    while (i < input.length) {
        val linkMatch = LINK_REGEX.find(input, i)
        if (linkMatch != null && linkMatch.range.first == i) {
            val label = linkMatch.groupValues[1]
            val url = linkMatch.groupValues[2]
            val start = output.length
            output.append(label)
            val end = output.length
            if (start < end && url.isNotBlank()) {
                ranges += Triple(start, end, MessageEntityType.TextUrl(url))
            }
            i = linkMatch.range.last + 1
            continue
        }

        val marker = MARKERS.firstOrNull { input.startsWith(it, i) }
        if (marker != null) {
            val starts = markerStarts.getOrPut(marker) { mutableListOf() }
            if (starts.isNotEmpty()) {
                val start = starts.removeAt(starts.lastIndex)
                val end = output.length
                if (start < end) {
                    val type = markerToType(marker)
                    if (type != null) {
                        ranges += Triple(start, end, type)
                    }
                }
            } else {
                starts += output.length
            }
            i += marker.length
            continue
        }

        output.append(input[i])
        i++
    }

    val annotated = buildAnnotatedString {
        append(output.toString())
        ranges.forEach { (start, end, type) ->
            val key = richEntityToAnnotation(type) ?: return@forEach
            addStringAnnotation(RICH_ENTITY_TAG, key, start, end)
        }
    }
    val newCursor = annotated.length.coerceAtLeast(0)
    return value.copy(annotatedString = annotated, selection = TextRange(newCursor))
}

private fun markerToType(marker: String): MessageEntityType? {
    return when (marker) {
        "**" -> MessageEntityType.Bold
        "_" -> MessageEntityType.Italic
        "~~" -> MessageEntityType.Strikethrough
        "||" -> MessageEntityType.Spoiler
        "`" -> MessageEntityType.Code
        else -> null
    }
}

private fun MessageEntityType.toPreviewStyle(primaryColor: Color): SpanStyle? {
    val codeBackground = primaryColor.copy(alpha = 0.14f)
    val spoilerBackground = primaryColor.copy(alpha = 0.25f)
    return when (this) {
        is MessageEntityType.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
        is MessageEntityType.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
        is MessageEntityType.Underline -> SpanStyle(textDecoration = TextDecoration.Underline)
        is MessageEntityType.Strikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        is MessageEntityType.Spoiler -> SpanStyle(color = Color.Transparent, background = spoilerBackground)
        is MessageEntityType.Code -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
        is MessageEntityType.Pre -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
        is MessageEntityType.TextUrl -> SpanStyle(color = primaryColor, textDecoration = TextDecoration.Underline)
        else -> null
    }
}

private val MARKERS = listOf("**", "~~", "||", "`", "_")
private val LINK_REGEX = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")
