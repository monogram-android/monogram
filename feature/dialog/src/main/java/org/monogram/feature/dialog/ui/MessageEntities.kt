package org.monogram.feature.dialog.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import org.monogram.core.models.TextEntity

internal fun AnnotatedString.Builder.applyMessageEntities(
    text: String,
    entities: List<TextEntity>,
    linkColor: Color,
    revealSpoilers: Boolean = true,
    onLink: ((String) -> Unit)? = null,
    onSpoilerClick: (() -> Unit)? = null,
) {
    val len = text.length
    val linkStyle = TextLinkStyles(
        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
    )
    for (entity in entities) {
        val start = entity.offset.coerceIn(0, len)
        val end = (entity.offset + entity.length).coerceIn(start, len)
        if (start >= end) continue
        val slice = text.substring(start, end)
        when (entity.kind) {
            "bold" -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
            "italic" -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
            "underline" -> addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
            "strike" -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
            "code", "pre", "bank_card" -> addStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = Color.Black.copy(alpha = 0.08f),
                ),
                start,
                end,
            )
            "spoiler" -> {
                if (revealSpoilers) {
                    addStyle(SpanStyle(background = Color.Black.copy(alpha = 0.12f)), start, end)
                } else {
                    addStyle(
                        SpanStyle(
                            color = Color.Transparent,
                            background = Color.Black.copy(alpha = 0.72f),
                        ),
                        start,
                        end,
                    )
                }
                // Tapping the spoiler itself toggles it. Without this the bubble-wide tap wins and
                // opens the message menu, leaving spoilers impossible to reveal.
                if (onSpoilerClick != null) {
                    addLink(
                        LinkAnnotation.Clickable(
                            tag = SpoilerLinkTag,
                            styles = TextLinkStyles(),
                            linkInteractionListener = { onSpoilerClick() },
                        ),
                        start,
                        end,
                    )
                }
            }
            "blockquote" -> addStyle(
                SpanStyle(fontStyle = FontStyle.Italic, color = linkColor.copy(alpha = 0.92f)),
                start,
                end,
            )
            "superscript" -> addStyle(
                SpanStyle(fontSize = 0.75.em, baselineShift = BaselineShift.Superscript),
                start,
                end,
            )
            "subscript" -> addStyle(
                SpanStyle(fontSize = 0.75.em, baselineShift = BaselineShift.Subscript),
                start,
                end,
            )
            else -> {
                val href = entityHref(entity.kind, entity.url, slice)
                if (href != null && onLink != null) {
                    addLink(LinkAnnotation.Clickable(href, linkStyle) { onLink(href) }, start, end)
                } else if (href != null) {
                    addLink(LinkAnnotation.Url(href, linkStyle), start, end)
                } else if (entity.kind in setOf("hashtag", "bot_command", "cashtag", "mention")) {
                    addStyle(
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                        start,
                        end,
                    )
                }
            }
        }
    }
}

internal fun entityHref(kind: String, url: String?, slice: String): String? = when (kind) {
    "text_url" -> absoluteLink(url)
    "anchor" -> url?.let { if (it.startsWith("#")) it else "#$it" }
    "url" -> absoluteLink(slice) ?: absoluteLink(url)
    "email" -> "mailto:$slice"
    "phone" -> {
        val digits = slice.filter { it.isDigit() || it == '+' }
        digits.takeIf { it.isNotBlank() }?.let { "tel:$it" }
    }
    "mention" -> {
        val user = slice.removePrefix("@").takeIf { it.isNotBlank() }
        user?.let { "https://t.me/$it" }
    }
    "mention_name", "text_mention" -> url?.takeIf { it.isNotBlank() }?.let { "tg://user?id=$it" }
    else -> null
}

private val AbsoluteLinkScheme = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*://")

private val OpaqueLinkScheme = Regex(
    "^(?:mailto|tel|sms|smsto|geo|tg|magnet|bitcoin|market|whatsapp):",
    RegexOption.IGNORE_CASE,
)

private val TelegramHost = Regex(
    "^(?:www\\.)?(?:t\\.me|telegram\\.me|telegram\\.dog)(?=[/?#]|$)",
    RegexOption.IGNORE_CASE,
)

internal fun absoluteLink(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    if (AbsoluteLinkScheme.containsMatchIn(value) || OpaqueLinkScheme.containsMatchIn(value)) return value
    if (value.any { it.isWhitespace() || it.isISOControl() }) return null
    val host = value.substringBefore('/').substringBefore('?').substringBefore('#')
    if (!looksLikeWebHost(host)) return null
    return (if (TelegramHost.containsMatchIn(value)) "https://" else "http://") + value
}

private fun looksLikeWebHost(host: String): Boolean {
    if (host.length < 4 || host.startsWith('.') || host.endsWith('.') || !host.contains('.')) return false
    return host.all { it.isLetterOrDigit() || it in ".-_:[]%" }
}

/** Tag of the in-text spoiler link; the tag itself is never displayed. */
private const val SpoilerLinkTag = "monogram:spoiler"
