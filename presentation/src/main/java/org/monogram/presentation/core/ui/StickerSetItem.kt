package org.monogram.presentation.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.R
import org.monogram.presentation.features.stickers.ui.view.StickerImage

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerSetItem(
    stickerSet: StickerSetModel,
    position: ItemPosition,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onArchive: () -> Unit,
    showReorder: Boolean = false
) {
    val shape = remember(position) { getItemShape(position) }
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 12.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showReorder) {
                Icon(
                    imageVector = Icons.Rounded.DragHandle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            StickerThumbnail(stickerSet)

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stickerSet.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .basicMarquee()
                    )
                    if (stickerSet.isOfficial) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.Verified,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.stickers_count_format, stickerSet.stickers.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (stickerSet.isInstalled && stickerSet.isArchived) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.sticker_set_archived),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (stickerSet.isInstalled) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    StickerSetActionsMenu(
                        expanded = showMenu,
                        onDismiss = { showMenu = false },
                        isArchived = stickerSet.isArchived,
                        onArchive = {
                            showMenu = false
                            onArchive()
                        },
                        onRemove = {
                            showMenu = false
                            onToggle()
                        }
                    )
                }
            } else {
                FilledTonalButton(
                    onClick = onToggle,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.action_add_sticker_set),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun StickerSetActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isArchived: Boolean,
    onArchive: () -> Unit,
    onRemove: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(x = 0.dp, y = (-8).dp),
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        DropdownMenuItem(
            text = { Text(if (isArchived) stringResource(R.string.menu_unarchive) else stringResource(R.string.menu_archive)) },
            onClick = onArchive,
            leadingIcon = {
                Icon(
                    if (isArchived) Icons.Rounded.Unarchive else Icons.Rounded.Archive,
                    contentDescription = null
                )
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_remove_set)) },
            onClick = onRemove,
            leadingIcon = {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            colors = MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.error,
                leadingIconColor = MaterialTheme.colorScheme.error
            )
        )
    }
}

@Composable
private fun StickerThumbnail(stickerSet: StickerSetModel) {
    val stickerRepository = koinInject<StickerRepository>()
    val firstSticker = stickerSet.stickers.firstOrNull()
    val targetSticker = stickerSet.thumbnail ?: firstSticker

    var currentPath by remember(targetSticker) { mutableStateOf(targetSticker?.path) }

    LaunchedEffect(targetSticker) {
        if (targetSticker != null && currentPath == null) {
            stickerRepository.getStickerFile(targetSticker.id).collect { path ->
                if (path != null) {
                    currentPath = path
                }
            }
        }
    }

    val emoji = firstSticker?.emoji

    Surface(
        modifier = Modifier.size(56.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (currentPath != null) {
                StickerImage(
                    path = currentPath,
                    modifier = Modifier.size(48.dp),
                    animate = false
                )
            } else if (!emoji.isNullOrEmpty()) {
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.headlineSmall
                )
            } else {
                Icon(
                    Icons.Rounded.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

fun getItemShape(position: ItemPosition): Shape {
    val cornerRadius = 20.dp
    return when (position) {
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
}