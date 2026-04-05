package org.monogram.presentation.features.instantview.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import org.monogram.domain.models.webapp.PageBlock
import org.monogram.domain.models.webapp.RichText
import org.monogram.domain.repository.FileRepository
import java.text.SimpleDateFormat
import java.util.*

val LocalOnUrlClick = staticCompositionLocalOf<(String) -> Unit> { { } }
val LocalFileRepository =
    staticCompositionLocalOf<FileRepository> { error("No FileRepository provided") }

fun renderRichText(richText: RichText, linkColor: Color = Color(0xFF2196F3)): AnnotatedString {
    return buildAnnotatedString {
        appendRichText(richText, linkColor)
    }
}

fun AnnotatedString.Builder.appendRichText(richText: RichText, linkColor: Color) {
    when (richText) {
        is RichText.Plain -> append(richText.text)
        is RichText.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            appendRichText(richText.text, linkColor)
        }

        is RichText.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            appendRichText(richText.text, linkColor)
        }

        is RichText.Underline -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
            appendRichText(richText.text, linkColor)
        }

        is RichText.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            appendRichText(richText.text, linkColor)
        }

        is RichText.Fixed -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
            appendRichText(richText.text, linkColor)
        }

        is RichText.Url -> {
            pushStringAnnotation(tag = "URL", annotation = richText.url)
            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                appendRichText(richText.text, linkColor)
            }
            pop()
        }

        is RichText.Texts -> {
            richText.texts.forEach { appendRichText(it, linkColor) }
        }

        is RichText.Anchor -> {
            // Anchor is a position marker, usually invisible in text flow
        }

        is RichText.AnchorLink -> {
            pushStringAnnotation(tag = "ANCHOR", annotation = richText.anchorName)
            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                appendRichText(richText.text, linkColor)
            }
            pop()
        }

        is RichText.EmailAddress -> {
            pushStringAnnotation(tag = "EMAIL", annotation = richText.emailAddress)
            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                appendRichText(richText.text, linkColor)
            }
            pop()
        }

        is RichText.Icon -> {
            // Icon is a position marker, usually invisible in text flow
        }

        is RichText.Marked -> withStyle(SpanStyle(background = Color(0x55FFFF00))) {
            appendRichText(richText.text, linkColor)
        }

        is RichText.PhoneNumber -> {
            pushStringAnnotation(tag = "PHONE", annotation = richText.phoneNumber)
            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                appendRichText(richText.text, linkColor)
            }
            pop()
        }

        is RichText.Reference -> {
            pushStringAnnotation(tag = "URL", annotation = richText.url)
            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                appendRichText(richText.text, linkColor)
            }
            pop()
        }

        is RichText.Subscript -> withStyle(SpanStyle(baselineShift = BaselineShift.Subscript)) {
            appendRichText(richText.text, linkColor)
        }

        is RichText.Superscript -> withStyle(SpanStyle(baselineShift = BaselineShift.Superscript)) {
            appendRichText(richText.text, linkColor)
        }
    }
}

fun PageBlock.containsText(query: String): Boolean {
    return when (this) {
        is PageBlock.Title -> title.containsText(query)
        is PageBlock.Subtitle -> subtitle.containsText(query)
        is PageBlock.AuthorDate -> author.containsText(query) || (publishDate > 0 && SimpleDateFormat(
            "MMM d, yyyy",
            Locale.getDefault()
        ).format(Date(publishDate.toLong() * 1000)).contains(query, ignoreCase = true))

        is PageBlock.Header -> header.containsText(query)
        is PageBlock.Subheader -> subheader.containsText(query)
        is PageBlock.Kicker -> kicker.containsText(query)
        is PageBlock.Paragraph -> text.containsText(query)
        is PageBlock.Preformatted -> text.containsText(query)
        is PageBlock.Footer -> footer.containsText(query)
        is PageBlock.BlockQuote -> text.containsText(query) || credit.containsText(query)
        is PageBlock.PullQuote -> text.containsText(query) || credit.containsText(query)
        is PageBlock.ListBlock -> items.any { item ->
            item.label.contains(
                query,
                ignoreCase = true
            ) || item.pageBlocks.any { it.containsText(query) }
        }

        is PageBlock.Details -> header.containsText(query) || pageBlocks.any { it.containsText(query) }
        is PageBlock.Table -> caption.containsText(query) || cells.any { row -> row.any { it.text.containsText(query) } }
        is PageBlock.RelatedArticles -> header.containsText(query) || articles.any {
            it.title.contains(
                query,
                ignoreCase = true
            ) || it.description.contains(query, ignoreCase = true)
        }

        is PageBlock.PhotoBlock -> caption.text.containsText(query) || caption.credit.containsText(query)
        is PageBlock.VideoBlock -> caption.text.containsText(query) || caption.credit.containsText(query)
        is PageBlock.AnimationBlock -> caption.text.containsText(query) || caption.credit.containsText(query)
        is PageBlock.Collage -> caption.text.containsText(query) || caption.credit.containsText(query) || pageBlocks.any {
            it.containsText(
                query
            )
        }

        is PageBlock.Slideshow -> caption.text.containsText(query) || caption.credit.containsText(query) || pageBlocks.any {
            it.containsText(
                query
            )
        }

        is PageBlock.ChatLink -> title.contains(query, ignoreCase = true)

        is PageBlock.Anchor -> name.contains(query, ignoreCase = true)
        is PageBlock.AudioBlock -> caption.text.containsText(query) || caption.credit.containsText(query)
        is PageBlock.Cover -> cover.containsText(query)
        PageBlock.Divider -> false
        is PageBlock.Embedded -> caption.text.containsText(query) || caption.credit.containsText(query) || url.contains(
            query,
            ignoreCase = true
        ) || html.contains(query, ignoreCase = true)

        is PageBlock.EmbeddedPost -> caption.text.containsText(query) || caption.credit.containsText(query) || author.contains(
            query,
            ignoreCase = true
        ) || pageBlocks.any { it.containsText(query) }

        is PageBlock.MapBlock -> caption.text.containsText(query) || caption.credit.containsText(query)
    }
}

fun RichText.containsText(query: String): Boolean {
    return when (this) {
        is RichText.Plain -> text.contains(query, ignoreCase = true)
        is RichText.Bold -> text.containsText(query)
        is RichText.Italic -> text.containsText(query)
        is RichText.Underline -> text.containsText(query)
        is RichText.Strikethrough -> text.containsText(query)
        is RichText.Fixed -> text.containsText(query)
        is RichText.Url -> text.containsText(query) || url.contains(query, ignoreCase = true)
        is RichText.Texts -> texts.any { it.containsText(query) }
        is RichText.Anchor -> name.contains(query, ignoreCase = true)
        is RichText.AnchorLink -> text.containsText(query) || anchorName.contains(
            query,
            ignoreCase = true
        ) || url.contains(query, ignoreCase = true)

        is RichText.EmailAddress -> text.containsText(query) || emailAddress.contains(query, ignoreCase = true)
        is RichText.Icon -> false
        is RichText.Marked -> text.containsText(query)
        is RichText.PhoneNumber -> text.containsText(query) || phoneNumber.contains(query, ignoreCase = true)
        is RichText.Reference -> text.containsText(query) || anchorName.contains(
            query,
            ignoreCase = true
        ) || url.contains(query, ignoreCase = true)

        is RichText.Subscript -> text.containsText(query)
        is RichText.Superscript -> text.containsText(query)
    }
}