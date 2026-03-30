package org.monogram.presentation.features.chats.currentChat.components.chats

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.InlineKeyboardButtonModel
import org.monogram.domain.models.InlineKeyboardButtonType
import org.monogram.domain.models.ReplyMarkupModel
import org.monogram.presentation.core.ui.ItemPosition

@Composable
fun ReplyMarkupView(
    replyMarkup: ReplyMarkupModel,
    onButtonClick: (InlineKeyboardButtonModel) -> Unit,
    modifier: Modifier = Modifier
) {
    when (replyMarkup) {
        is ReplyMarkupModel.InlineKeyboard -> {
            Column(
                modifier = modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                replyMarkup.rows.forEachIndexed { index, row ->
                    val position = when {
                        replyMarkup.rows.size == 1 -> ItemPosition.STANDALONE
                        index == 0 -> ItemPosition.TOP
                        index == replyMarkup.rows.size - 1 -> ItemPosition.BOTTOM
                        else -> ItemPosition.MIDDLE
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        row.forEach { button ->
                            InlineKeyboardButtonView(
                                button = button,
                                onClick = { onButtonClick(button) },
                                position = position,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        else -> {}
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InlineKeyboardButtonView(
    button: InlineKeyboardButtonModel,
    onClick: () -> Unit,
    position: ItemPosition,
    modifier: Modifier = Modifier
) {
    val localClipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current

    val icon = when (button.type) {
        is InlineKeyboardButtonType.Url -> Icons.Rounded.Link
        is InlineKeyboardButtonType.WebApp -> Icons.Rounded.Language
        is InlineKeyboardButtonType.Buy -> Icons.Rounded.ShoppingCart
        is InlineKeyboardButtonType.User -> Icons.Rounded.Person
        else -> null
    }

    val cornerRadius = 12.dp
    val shape = when (position) {
        ItemPosition.TOP -> RoundedCornerShape(
            topStart = cornerRadius,
            topEnd = cornerRadius,
            bottomStart = 2.dp,
            bottomEnd = 2.dp
        )

        ItemPosition.MIDDLE -> RoundedCornerShape(2.dp)
        ItemPosition.BOTTOM -> RoundedCornerShape(
            bottomStart = cornerRadius,
            bottomEnd = cornerRadius,
            topStart = 2.dp,
            topEnd = 2.dp
        )

        ItemPosition.STANDALONE -> RoundedCornerShape(cornerRadius)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape,
        modifier = modifier
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    val dataToCopy = when (val type = button.type) {
                        is InlineKeyboardButtonType.Callback -> String(type.data)
                        is InlineKeyboardButtonType.Url -> type.url
                        is InlineKeyboardButtonType.WebApp -> type.url
                        is InlineKeyboardButtonType.SwitchInline -> type.query
                        else -> null
                    }
                    if (dataToCopy != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        localClipboard.nativeClipboard.setPrimaryClip(
                            ClipData.newPlainText("", AnnotatedString(dataToCopy))
                        )
                    }
                }
            )
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = button.text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
