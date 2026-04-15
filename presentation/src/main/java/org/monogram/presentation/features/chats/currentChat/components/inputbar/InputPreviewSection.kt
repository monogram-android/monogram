package org.monogram.presentation.features.chats.currentChat.components.inputbar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.R
import org.monogram.presentation.features.chats.currentChat.components.chats.buildAnnotatedMessageTextWithEmoji
import org.monogram.presentation.features.chats.currentChat.components.chats.rememberMessageInlineContent
import java.io.File
import java.util.Collections

sealed class InputPreviewState {
    object None : InputPreviewState()
    data class Reply(val message: MessageModel) : InputPreviewState()
    data class Edit(val message: MessageModel) : InputPreviewState()
    data class Media(val paths: List<String>) : InputPreviewState()
    data class Documents(val paths: List<String>) : InputPreviewState()
}

@Composable
fun InputPreviewSection(
    editingMessage: MessageModel?,
    replyMessage: MessageModel?,
    pendingMediaPaths: List<String>,
    pendingDocumentPaths: List<String>,
    onCancelEdit: () -> Unit,
    onCancelReply: () -> Unit,
    onCancelMedia: () -> Unit,
    onCancelDocuments: () -> Unit,
    onMediaOrderChange: (List<String>) -> Unit,
    onDocumentOrderChange: (List<String>) -> Unit,
    onMediaClick: (String) -> Unit
) {
    val previewState =
        remember(editingMessage, replyMessage, pendingMediaPaths, pendingDocumentPaths) {
        when {
            pendingMediaPaths.isNotEmpty() -> InputPreviewState.Media(pendingMediaPaths)
            pendingDocumentPaths.isNotEmpty() -> InputPreviewState.Documents(pendingDocumentPaths)
            editingMessage != null -> InputPreviewState.Edit(editingMessage)
            replyMessage != null -> InputPreviewState.Reply(replyMessage)
            else -> InputPreviewState.None
        }
    }

    AnimatedContent(
        targetState = previewState,
        transitionSpec = {
            val enterTransition = fadeIn(animationSpec = tween(200)) +
                    expandVertically(animationSpec = tween(200))
            val exitTransition = fadeOut(animationSpec = tween(150)) +
                    shrinkVertically(animationSpec = tween(150))
            enterTransition togetherWith exitTransition
        },
        label = "PreviewAnimation"
    ) { state ->
        when (state) {
            is InputPreviewState.Edit -> EditPreview(message = state.message, onCancel = onCancelEdit)
            is InputPreviewState.Reply -> ReplyPreview(message = state.message, onCancel = onCancelReply)
            is InputPreviewState.Media -> MediaPreview(
                paths = state.paths,
                onCancel = onCancelMedia,
                onRemove = { path ->
                    val newList = pendingMediaPaths.toMutableList()
                    newList.remove(path)
                    onMediaOrderChange(newList)
                },
                onMove = { from, to ->
                    val newList = pendingMediaPaths.toMutableList()
                    Collections.swap(newList, from, to)
                    onMediaOrderChange(newList)
                },
                onMediaClick = onMediaClick
            )
            is InputPreviewState.Documents -> DocumentPreview(
                paths = state.paths,
                onCancel = onCancelDocuments,
                onRemove = { path ->
                    val newList = pendingDocumentPaths.toMutableList()
                    newList.remove(path)
                    onDocumentOrderChange(newList)
                }
            )

            InputPreviewState.None -> Spacer(modifier = Modifier.height(0.dp))
        }
    }
}

@Composable
private fun DocumentPreview(
    paths: List<String>,
    onCancel: () -> Unit,
    onRemove: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.action_attach_file_count, paths.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        paths.forEach { path ->
            val file = File(path)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name.ifBlank { "File" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = formatFileSize(file.length()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { onRemove(path) }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_remove)
                    )
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) {
        "${value.toInt()} ${units[index]}"
    } else {
        String.format("%.1f %s", value, units[index])
    }
}

@Composable
private fun ReplyPreview(
    message: MessageModel,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Reply,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp)
        )

        Box(
            modifier = Modifier
                .width(2.dp)
                .height(36.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val (previewText, previewEntities) = when (val content = message.content) {
                is MessageContent.Text -> content.text to content.entities
                else -> {
                    val fallback = when (content) {
                        is MessageContent.Photo -> stringResource(R.string.media_type_photo)
                        is MessageContent.Video -> stringResource(R.string.media_type_video)
                        is MessageContent.Sticker -> stringResource(R.string.media_type_sticker)
                        is MessageContent.Voice -> stringResource(R.string.media_type_voice)
                        is MessageContent.VideoNote -> stringResource(R.string.media_type_video_note)
                        is MessageContent.Gif -> stringResource(R.string.media_type_gif)
                        is MessageContent.Location -> stringResource(R.string.media_type_location)
                        is MessageContent.Venue -> content.title
                        else -> stringResource(R.string.media_type_message)
                    }
                    fallback to emptyList()
                }
            }
            val annotatedPreviewText = buildAnnotatedMessageTextWithEmoji(
                text = previewText,
                entities = previewEntities
            )
            val previewInlineContent = rememberMessageInlineContent(
                entities = previewEntities,
                fontSize = MaterialTheme.typography.bodySmall.fontSize.value
            )

            Text(
                text = annotatedPreviewText,
                inlineContent = previewInlineContent,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.action_cancel_reply),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EditPreview(
    message: MessageModel,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp)
        )

        Box(
            modifier = Modifier
                .width(2.dp)
                .height(36.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.action_edit_message),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val (previewText, previewEntities) = when (val content = message.content) {
                is MessageContent.Text -> content.text to content.entities
                else -> stringResource(R.string.media_type_message) to emptyList()
            }
            val annotatedPreviewText = buildAnnotatedMessageTextWithEmoji(
                text = previewText,
                entities = previewEntities
            )
            val previewInlineContent = rememberMessageInlineContent(
                entities = previewEntities,
                fontSize = MaterialTheme.typography.bodySmall.fontSize.value
            )

            Text(
                text = annotatedPreviewText,
                inlineContent = previewInlineContent,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.action_cancel_edit),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaPreview(
    paths: List<String>,
    onCancel: () -> Unit,
    onRemove: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onMediaClick: (String) -> Unit = {}
) {
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (paths.size > 1) stringResource(R.string.action_send_items_count, paths.size) else stringResource(R.string.action_send_media),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp)
            )
            IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(paths, key = { _, path -> path }) { index, path ->
                val isDragging = draggingIndex == index
                val scale by animateFloatAsState(
                    targetValue = if (isDragging) 1.1f else 1f,
                    animationSpec = tween(200),
                    label = "scale"
                )

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .scale(scale)
                        .zIndex(if (isDragging) 1f else 0f)
                        .pointerInput(paths) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    draggingIndex = index
                                    dragOffset = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffset += dragAmount.x

                                    val itemWidth = with(density) { 88.dp.toPx() }
                                    val targetIndex = when {
                                        dragOffset > itemWidth / 2 -> index + 1
                                        dragOffset < -itemWidth / 2 -> index - 1
                                        else -> index
                                    }

                                    if (targetIndex in paths.indices && targetIndex != index) {
                                        onMove(index, targetIndex)
                                        draggingIndex = targetIndex
                                        dragOffset = 0f
                                    }
                                },
                                onDragEnd = {
                                    draggingIndex = null
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    draggingIndex = null
                                    dragOffset = 0f
                                }
                            )
                        }
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onMediaClick(path) }
                ) {
                    AsyncImage(
                        model = File(path),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    if (path.endsWith(".mp4")) {
                        Icon(
                            imageVector = Icons.Default.PlayCircle,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(24.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(20.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            .clickable { onRemove(path) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_remove),
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}