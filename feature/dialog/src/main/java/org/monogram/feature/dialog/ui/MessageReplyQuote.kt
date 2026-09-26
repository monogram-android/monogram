package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.feature.dialog.R

@Composable
internal fun MessageReplyQuote(
    quote: String?,
    quoteKind: String?,
    quotedOutgoing: Boolean?,
    quotedSender: String?,
    onContainer: Color,
    onQuoteClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidthInBubble()
            .height(IntrinsicSize.Min)
            .clip(MaterialTheme.shapes.small)
            .background(onContainer.copy(alpha = 0.08f))
            .then(
                if (onQuoteClick != null) {
                    Modifier.clickable(onClick = onQuoteClick)
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(
            modifier = Modifier.padding(
                start = 8.dp,
                end = 8.dp,
                top = 6.dp,
                bottom = 6.dp,
            ),
        ) {
            replyQuoteAuthor(
                quotedOutgoing = quotedOutgoing,
                quotedSender = quotedSender,
                youLabel = stringResource(R.string.dialog_you),
            )?.let { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = quote ?: replyMediaFallbackLabel(quoteKind),
                style = MaterialTheme.typography.bodySmall,
                color = onContainer.copy(alpha = 0.85f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun replyQuoteAuthor(
    quotedOutgoing: Boolean?,
    quotedSender: String?,
    youLabel: String,
): String? = when {
    quotedOutgoing == true -> youLabel
    !quotedSender.isNullOrBlank() -> quotedSender
    else -> null
}

@Composable
private fun replyMediaFallbackLabel(kind: String?): String = stringResource(
    when (kind) {
        "photo" -> R.string.dialog_reply_photo
        "video" -> R.string.dialog_reply_video
        "sticker", "sticker_animated" -> R.string.dialog_reply_sticker
        "gif" -> R.string.dialog_reply_gif
        "document" -> R.string.dialog_reply_document
        else -> R.string.dialog_replying
    },
)
