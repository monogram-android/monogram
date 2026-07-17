package org.monogram.presentation.features.chats.conversation.ui.inputbar

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.MessageEntityType

fun buildEditorPreviewAnnotatedString(
    source: AnnotatedString,
    primaryColor: Color
): AnnotatedString {
    val builder = AnnotatedString.Builder(source.text)

    source.getStringAnnotations(0, source.length)
        .filter { it.tag !in setOf(RICH_ENTITY_TAG, MENTION_TAG, LATEX_TAG) }
        .forEach { builder.addStringAnnotation(it.tag, it.item, it.start, it.end) }

    extractEntities(source, emptyMap()).forEach { entity ->
        val style = entity.type.toPreviewStyle(primaryColor) ?: return@forEach
        val start = entity.offset.coerceIn(0, source.length)
        val end = (entity.offset + entity.length).coerceIn(0, source.length)
        if (start < end) {
            builder.addStyle(style, start, end)
        }
    }

    source.getStringAnnotations(LATEX_TAG, 0, source.length).forEach { annotation ->
        if (annotation.start < annotation.end) {
            builder.addStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = primaryColor.copy(alpha = 0.12f),
                    color = primaryColor
                ),
                annotation.start,
                annotation.end
            )
        }
    }

    source.getStringAnnotations(EDITOR_HEADING_TAG, 0, source.length).forEach { annotation ->
        if (annotation.start < annotation.end) {
            val level = annotation.item.toIntOrNull()?.coerceIn(1, 3) ?: 1
            val fontSize = when (level) {
                1 -> 24.sp
                2 -> 20.sp
                else -> 17.sp
            }
            builder.addStyle(
                SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSize,
                    color = primaryColor
                ),
                annotation.start,
                annotation.end
            )
        }
    }

    source.getStringAnnotations(EDITOR_DIVIDER_TAG, 0, source.length).forEach { annotation ->
        if (annotation.start < annotation.end) {
            builder.addStyle(
                SpanStyle(
                    color = primaryColor.copy(alpha = 0.7f)
                ),
                annotation.start,
                annotation.end
            )
            builder.addStyle(
                ParagraphStyle(textAlign = TextAlign.Center),
                annotation.start,
                annotation.end
            )
        }
    }

    return builder.toAnnotatedString()
}

private fun MessageEntityType.toPreviewStyle(primaryColor: Color): SpanStyle? {
    val codeBackground = primaryColor.copy(alpha = 0.14f)
    val spoilerBackground = primaryColor.copy(alpha = 0.25f)
    val quoteBackground = primaryColor.copy(alpha = 0.09f)

    return when (this) {
        is MessageEntityType.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
        is MessageEntityType.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
        is MessageEntityType.Underline -> SpanStyle(textDecoration = TextDecoration.Underline)
        is MessageEntityType.Strikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        is MessageEntityType.Spoiler -> SpanStyle(color = Color.Transparent, background = spoilerBackground)
        is MessageEntityType.BlockQuote -> SpanStyle(
            color = primaryColor,
            background = quoteBackground,
            fontStyle = FontStyle.Italic
        )

        is MessageEntityType.BlockQuoteExpandable -> SpanStyle(
            color = primaryColor,
            background = quoteBackground,
            fontStyle = FontStyle.Italic
        )

        is MessageEntityType.Code -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
        is MessageEntityType.Pre -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
        is MessageEntityType.TextUrl -> SpanStyle(color = primaryColor, textDecoration = TextDecoration.Underline)
        is MessageEntityType.Mention -> SpanStyle(color = primaryColor)
        is MessageEntityType.TextMention -> SpanStyle(color = primaryColor)
        is MessageEntityType.Hashtag -> SpanStyle(color = primaryColor)
        is MessageEntityType.Cashtag -> SpanStyle(color = primaryColor)
        is MessageEntityType.BotCommand -> SpanStyle(color = primaryColor)
        is MessageEntityType.Url -> SpanStyle(
            color = primaryColor,
            textDecoration = TextDecoration.Underline
        )

        is MessageEntityType.Email -> SpanStyle(
            color = primaryColor,
            textDecoration = TextDecoration.Underline
        )

        is MessageEntityType.PhoneNumber -> SpanStyle(
            color = primaryColor,
            textDecoration = TextDecoration.Underline
        )

        is MessageEntityType.BankCardNumber -> SpanStyle(
            color = primaryColor,
            textDecoration = TextDecoration.Underline
        )
        else -> null
    }
}
