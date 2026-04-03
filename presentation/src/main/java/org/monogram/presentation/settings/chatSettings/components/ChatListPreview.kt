package org.monogram.presentation.settings.chatSettings.components

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.ui.ItemPosition

@Composable
fun ChatListPreview(
    messageLines: Int,
    modifier: Modifier = Modifier,
    showPhotos: Boolean = true,
    position: ItemPosition = ItemPosition.STANDALONE
) {
    val cornerRadius = 24.dp
    val shape = remember(position) {
        when (position) {
            ItemPosition.TOP -> RoundedCornerShape(
                topStart = cornerRadius,
                topEnd = cornerRadius,
                bottomStart = 4.dp,
                bottomEnd = 4.dp
            )

            ItemPosition.MIDDLE -> RoundedCornerShape(4.dp)
            ItemPosition.BOTTOM -> RoundedCornerShape(
                bottomStart = cornerRadius,
                bottomEnd = cornerRadius,
                topStart = 4.dp,
                topEnd = 4.dp
            )

            ItemPosition.STANDALONE -> RoundedCornerShape(cornerRadius)
        }
    }

    Column(modifier = modifier) {
        if (position == ItemPosition.TOP || position == ItemPosition.STANDALONE) {
            Text(
                text = stringResource(R.string.chat_list_preview_title),
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp, top = 16.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = shape,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    )
            ) {
                PreviewChatItem(
                    name = stringResource(R.string.preview_name_konata),
                    message = stringResource(R.string.preview_message_konata),
                    time = stringResource(R.string.preview_time_konata),
                    lines = messageLines,
                    showPhotos = showPhotos,
                    isKonata = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                PreviewChatItem(
                    name = stringResource(R.string.preview_name_kagami),
                    message = stringResource(R.string.preview_message_kagami),
                    time = stringResource(R.string.preview_time_kagami),
                    lines = messageLines,
                    showPhotos = showPhotos,
                    isKonata = false
                )
            }
        }

        if (position != ItemPosition.BOTTOM && position != ItemPosition.STANDALONE) {
            Spacer(Modifier.size(2.dp))
        }
    }
}

@Composable
private fun PreviewChatItem(
    name: String,
    message: String,
    time: String,
    lines: Int,
    showPhotos: Boolean,
    isKonata: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(
            visible = showPhotos,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally()
        ) {
            Row {
                Avatar(
                    path = if (isKonata) "local" else null,
                    name = name,
                    size = 48.dp,
                    isLocal = isKonata
                )

                Spacer(modifier = Modifier.width(12.dp))
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = lines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
