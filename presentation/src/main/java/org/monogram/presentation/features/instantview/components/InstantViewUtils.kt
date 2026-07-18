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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.core.telegram.TelegramLinkDomains
import org.monogram.domain.models.FileDownloadEvent
import org.monogram.domain.models.webapp.PageBlock
import org.monogram.domain.models.webapp.PageBlockCaption
import org.monogram.domain.models.webapp.RichText
import org.monogram.domain.repository.FileRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

        is RichText.Spoiler -> withStyle(
            SpanStyle(
                background = Color(0x66000000),
                color = Color.Transparent
            )
        ) {
            appendRichText(richText.text, linkColor)
        }

        is RichText.DateTime -> {
            pushStringAnnotation(tag = "DATE_TIME", annotation = richText.unixTime.toString())
            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                appendRichText(richText.text, linkColor)
            }
            pop()
        }

        is RichText.Mention -> {
            pushStringAnnotation(
                tag = "URL",
                annotation = "${TelegramLinkDomains.DEFAULT_BASE_URL}/${
                    richText.username.removePrefix(
                        "@"
                    )
                }"
            )
            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                appendRichText(richText.text, linkColor)
            }
            pop()
        }

        is RichText.Hashtag -> {
            pushStringAnnotation(tag = "SEARCH", annotation = richText.hashtag)
            withStyle(SpanStyle(color = linkColor)) {
                appendRichText(richText.text, linkColor)
            }
            pop()
        }

        is RichText.Cashtag -> {
            pushStringAnnotation(tag = "SEARCH", annotation = richText.cashtag)
            withStyle(SpanStyle(color = linkColor)) {
                appendRichText(richText.text, linkColor)
            }
            pop()
        }

        is RichText.BotCommand -> withStyle(SpanStyle(color = linkColor)) {
            appendRichText(richText.text, linkColor)
        }

        is RichText.Fixed -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
            appendRichText(richText.text, linkColor)
        }

        is RichText.MentionName -> {
            pushStringAnnotation(tag = "USER", annotation = richText.userId.toString())
            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                appendRichText(richText.text, linkColor)
            }
            pop()
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

        is RichText.BankCardNumber -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
            appendRichText(richText.text, linkColor)
        }

        is RichText.Icon -> {
            // Icon is a position marker, usually invisible in text flow
        }

        is RichText.CustomEmoji -> append(richText.alternativeText)

        is RichText.MathematicalExpression -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
            append(richText.expression)
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
            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                if (richText.url.isNotBlank()) {
                    pushStringAnnotation(tag = "URL", annotation = richText.url)
                }
                appendRichText(richText.text, linkColor)
                if (richText.url.isNotBlank()) {
                    pop()
                }
            }
        }

        is RichText.ReferenceLink -> {
            pushStringAnnotation(tag = "URL", annotation = richText.url)
            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                appendRichText(richText.text, linkColor)
            }
            pop()
        }

        is RichText.Diff -> {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = Color.Gray)) {
                appendRichText(richText.oldText, linkColor)
            }
            val newText = richTextPlainText(richText.text)
            val oldText = richTextPlainText(richText.oldText)
            if (oldText.isNotBlank() && newText.isNotBlank()) {
                append(" -> ")
            }
            appendRichText(richText.text, linkColor)
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
        is PageBlock.SectionHeading -> text.containsText(query)
        is PageBlock.Kicker -> kicker.containsText(query)
        is PageBlock.Paragraph -> text.containsText(query)
        is PageBlock.Preformatted -> text.containsText(query)
        is PageBlock.Footer -> footer.containsText(query)
        is PageBlock.Thinking -> text.containsText(query)
        is PageBlock.MathematicalExpression -> expression.contains(query, ignoreCase = true)
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
        is PageBlock.VoiceNoteBlock -> caption.text.containsText(query) || caption.credit.containsText(
            query
        )
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
        is PageBlock.Unsupported -> typeName.contains(query, ignoreCase = true)
    }
}

fun RichText.containsText(query: String): Boolean {
    return when (this) {
        is RichText.Plain -> text.contains(query, ignoreCase = true)
        is RichText.Bold -> text.containsText(query)
        is RichText.Italic -> text.containsText(query)
        is RichText.Underline -> text.containsText(query)
        is RichText.Strikethrough -> text.containsText(query)
        is RichText.Spoiler -> text.containsText(query)
        is RichText.DateTime -> text.containsText(query) || unixTime.toString().contains(query)
        is RichText.Mention -> text.containsText(query) || username.contains(
            query,
            ignoreCase = true
        )

        is RichText.Hashtag -> text.containsText(query) || hashtag.contains(
            query,
            ignoreCase = true
        )

        is RichText.Cashtag -> text.containsText(query) || cashtag.contains(
            query,
            ignoreCase = true
        )

        is RichText.BotCommand -> text.containsText(query) || botCommand.contains(
            query,
            ignoreCase = true
        )
        is RichText.Fixed -> text.containsText(query)
        is RichText.MentionName -> text.containsText(query) || userId.toString().contains(query)
        is RichText.Url -> text.containsText(query) || url.contains(query, ignoreCase = true)
        is RichText.Texts -> texts.any { it.containsText(query) }
        is RichText.Anchor -> name.contains(query, ignoreCase = true)
        is RichText.AnchorLink -> text.containsText(query) || anchorName.contains(
            query,
            ignoreCase = true
        ) || url.contains(query, ignoreCase = true)

        is RichText.EmailAddress -> text.containsText(query) || emailAddress.contains(query, ignoreCase = true)
        is RichText.BankCardNumber -> text.containsText(query) || bankCardNumber.contains(
            query,
            ignoreCase = true
        )
        is RichText.Icon -> false
        is RichText.CustomEmoji -> alternativeText.contains(query, ignoreCase = true)
        is RichText.MathematicalExpression -> expression.contains(query, ignoreCase = true)
        is RichText.Marked -> text.containsText(query)
        is RichText.PhoneNumber -> text.containsText(query) || phoneNumber.contains(query, ignoreCase = true)
        is RichText.Reference -> text.containsText(query) || anchorName.contains(
            query,
            ignoreCase = true
        ) || url.contains(query, ignoreCase = true)
        is RichText.ReferenceLink -> text.containsText(query) || referenceName.contains(
            query,
            ignoreCase = true
        ) || url.contains(query, ignoreCase = true)

        is RichText.Diff -> text.containsText(query) || oldText.containsText(query)

        is RichText.Subscript -> text.containsText(query)
        is RichText.Superscript -> text.containsText(query)
    }
}

fun PageBlockCaption.renderedTextOrNull(): String? {
    val text = buildString {
        richTextPlainText(this@renderedTextOrNull.text).takeIf { it.isNotBlank() }?.let(::append)
        richTextPlainText(this@renderedTextOrNull.credit).takeIf { it.isNotBlank() }
            ?.let { credit ->
                if (isNotEmpty()) append("\n")
                append(credit)
            }
    }
    return text.ifBlank { null }
}

fun richTextPlainText(richText: RichText): String = renderRichText(richText).text

suspend fun FileRepository.resolvePathForViewer(
    fileId: Int,
    initialPath: String?
): String? {
    if (!initialPath.isNullOrBlank()) return initialPath
    if (fileId == 0) return null
    val cachedPath = getFilePath(fileId)
    if (!cachedPath.isNullOrBlank()) return cachedPath

    downloadFile(fileId)

    val completedPath = withTimeoutOrNull(60_000L) {
        fileDownloadFlow
            .filterIsInstance<FileDownloadEvent.Completed>()
            .filter { it.fileId == fileId }
            .mapNotNull { event -> event.path.takeIf { it.isNotEmpty() } }
            .first()
    }

    return completedPath ?: getFilePath(fileId)
}
