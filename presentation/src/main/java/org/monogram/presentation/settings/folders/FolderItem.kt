package org.monogram.presentation.settings.folders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.FolderModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition

@Composable
fun FolderItem(
    folder: FolderModel,
    isSystem: Boolean,
    position: ItemPosition,
    onClick: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val icon = if (isSystem) Icons.Rounded.AllInbox else getFolderIcon(folder.iconName) ?: Icons.Rounded.Folder
    val iconColor = if (isSystem) Color(0xFF4285F4) else Color(0xFFF9AB00)

    val cornerRadius = 24.dp
    val shape = when (position) {
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

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color = iconColor.copy(0.15f), shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = iconColor
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 18.sp
                )
                Text(
                    text = if (isSystem) {
                        stringResource(R.string.folders_system_all_chats)
                    } else {
                        stringResource(R.string.folders_user_chats_count, folder.includedChatIds.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isSystem) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onMoveUp != null) {
                        IconButton(onClick = onMoveUp) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.folders_move_up),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (onMoveDown != null) {
                        IconButton(onClick = onMoveDown) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.folders_move_down),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

fun getFolderIcon(iconName: String?): ImageVector? {
    return when (iconName) {
        "All" -> Icons.Rounded.AllInbox
        "Unread" -> Icons.Rounded.MarkChatUnread
        "Unmuted" -> Icons.Rounded.NotificationsActive
        "Bots" -> Icons.Rounded.SmartToy
        "Channels" -> Icons.Rounded.Campaign
        "Groups" -> Icons.Rounded.Groups
        "Private" -> Icons.Rounded.Person
        "Custom" -> Icons.Rounded.Folder
        "Setup" -> Icons.Rounded.Settings
        "Cat" -> Icons.Rounded.Pets
        "Crown" -> Icons.Rounded.EmojiEvents
        "Favorite" -> Icons.Rounded.Star
        "Flower" -> Icons.Rounded.LocalFlorist
        "Game" -> Icons.Rounded.Gamepad
        "Home" -> Icons.Rounded.Home
        "Love" -> Icons.Rounded.Favorite
        "Mask" -> Icons.Rounded.TheaterComedy
        "Party" -> Icons.Rounded.Celebration
        "Sport" -> Icons.Rounded.SportsBasketball
        "Study" -> Icons.Rounded.School
        "Trade" -> Icons.Rounded.TrendingUp
        "Travel" -> Icons.Rounded.Flight
        "Work" -> Icons.Rounded.Work
        "Airplane" -> Icons.Rounded.AirplanemodeActive
        "Book" -> Icons.Rounded.Book
        "Light" -> Icons.Rounded.Lightbulb
        "Like" -> Icons.Rounded.ThumbUp
        "Money" -> Icons.Rounded.Payments
        "Note" -> Icons.Rounded.Description
        "Palette" -> Icons.Rounded.Palette
        else -> null
    }
}