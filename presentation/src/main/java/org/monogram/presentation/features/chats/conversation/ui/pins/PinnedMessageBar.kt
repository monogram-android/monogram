package org.monogram.presentation.features.chats.conversation.ui.pins

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.spacer.HeightSpacer
import org.monogram.presentation.core.ui.spacer.WidthSpacer

@Composable
fun PinnedMessageBar(
    message: MessageModel,
    count: Int,
    onClose: () -> Unit,
    onClick: () -> Unit,
    onShowAll: () -> Unit
) {
    val pinnedText = if (count > 1) stringResource(R.string.pinned_messages) else stringResource(R.string.pinned_message)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = if (count > 1) onShowAll else onClick),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 2.dp,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )

                WidthSpacer(16.dp)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 2.dp)
                ) {
                    PinHeader(
                        pinnedText = pinnedText,
                        pinCount = count
                    )

                    HeightSpacer(2.dp)

                    AnimatedMessageFooter(message = message)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (count > 1) {
                        ShowAllButton(onClick = onShowAll)
                    }

                    CloseButton(onClick = onClose)
                }
            }
        }
    }
}

@Composable
private fun PinHeader(
    pinnedText: String,
    pinCount: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Rounded.PushPin,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = pinnedText,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 0.1.sp
            )
        )
        PinCount(count = pinCount)
    }
}

@Composable
private fun AnimatedMessageFooter(
    message: MessageModel,
) {
    AnimatedContent(
        targetState = message,
        transitionSpec = {
            (slideInVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)) { it } + fadeIn())
                .togetherWith(slideOutVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)) { -it } + fadeOut())
        },
        label = "PinnedMessageContent"
    ) { msg ->
        Text(
            text = msg.content.toTypeName(),
            style = MaterialTheme.typography.bodyMedium.copy(
                lineHeight = 18.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PinCount(count: Int) {
    if (count > 1) {
        Surface(
            modifier = Modifier.padding(start = 8.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            shape = CircleShape
        ) {
            Text(
                text = count.toString(),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
private fun ShowAllButton(
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.FormatListBulleted,
            contentDescription = stringResource(R.string.pinned_show_all),
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CloseButton(
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.pinned_unpin),
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


@Preview(showBackground = true, locale = "ru")
@Composable
private fun MessageBarPreview() {
    MaterialTheme {
        val fakeData = MessageModel(
            id = 11,
            date = 11,
            isOutgoing = true,
            senderName = "SVolf",
            chatId = 1212,
            content = MessageContent.Text("Опрос: какой VPN протокол вы используете на постоянной основе?")
        )

        PinnedMessageBar(
            message = fakeData,
            count = 1366,
            onClose = {},
            onClick = {},
            onShowAll = {}
        )
    }
}
