package org.monogram.presentation.features.chats.conversation.ui.inputbar

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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.monogram.domain.models.LinkPreviewTarget
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.WebPage
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.shimmerBackground
import org.monogram.presentation.features.chats.conversation.ui.message.LinkPreviewAction
import org.monogram.presentation.features.chats.conversation.ui.message.buildAnnotatedMessageTextWithEmoji
import org.monogram.presentation.features.chats.conversation.ui.message.rememberMessageInlineContent
import org.monogram.presentation.features.chats.conversation.ui.message.resolveLinkPreview
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
internal fun InputPreviewSection(
    editingMessage: MessageModel?,
    replyMessage: MessageModel?,
    draftLinkTargets: List<LinkPreviewTarget>,
    selectedDraftLinkPreviewUrl: String?,
    draftLinkPreview: WebPage?,
    isDraftLinkPreviewLoading: Boolean,
    draftLinkPreviewError: String?,
    isDraftLinkPreviewDisabledForSend: Boolean,
    pendingMediaPaths: List<String>,
    pendingDocumentPaths: List<String>,
    onCancelEdit: () -> Unit,
    onCancelReply: () -> Unit,
    onSelectDraftLinkPreview: (String) -> Unit,
    onDismissDraftLinkPreview: () -> Unit,
    onRestoreDraftLinkPreview: () -> Unit,
    onCancelMedia: () -> Unit,
    onCancelDocuments: () -> Unit,
    onAddMedia: () -> Unit,
    onAddDocuments: () -> Unit,
    onMediaOrderChange: (List<String>) -> Unit,
    onDocumentOrderChange: (List<String>) -> Unit,
    onMediaClick: (String) -> Unit,
    onDraftLinkPreviewAction: (LinkPreviewAction) -> Unit = {}
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
                onAdd = onAddMedia,
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
                onAdd = onAddDocuments,
                onRemove = { path ->
                    val newList = pendingDocumentPaths.toMutableList()
                    newList.remove(path)
                    onDocumentOrderChange(newList)
                }
            )

            InputPreviewState.None -> {
                val hasContextPreview = editingMessage != null || replyMessage != null
                val hasDraftLinkPreview = draftLinkTargets.isNotEmpty() &&
                        (draftLinkPreview != null || isDraftLinkPreviewLoading || draftLinkPreviewError != null || isDraftLinkPreviewDisabledForSend)

                if (!hasContextPreview && !hasDraftLinkPreview) {
                    Spacer(modifier = Modifier.height(0.dp))
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (editingMessage != null) {
                            EditPreview(message = editingMessage, onCancel = onCancelEdit)
                        } else if (replyMessage != null) {
                            ReplyPreview(message = replyMessage, onCancel = onCancelReply)
                        }

                        if (hasDraftLinkPreview) {
                            DraftLinkPreviewSection(
                                targets = draftLinkTargets,
                                selectedUrl = selectedDraftLinkPreviewUrl,
                                preview = draftLinkPreview,
                                isLoading = isDraftLinkPreviewLoading,
                                error = draftLinkPreviewError,
                                isDisabledForSend = isDraftLinkPreviewDisabledForSend,
                                onSelect = onSelectDraftLinkPreview,
                                onDismiss = onDismissDraftLinkPreview,
                                onRestore = onRestoreDraftLinkPreview,
                                onPreviewAction = onDraftLinkPreviewAction
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftLinkPreviewSection(
    targets: List<LinkPreviewTarget>,
    selectedUrl: String?,
    preview: WebPage?,
    isLoading: Boolean,
    error: String?,
    isDisabledForSend: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
    onPreviewAction: (LinkPreviewAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.draft_link_preview_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.draft_link_preview_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (targets.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                targets.forEach { target ->
                    val selected = target.normalizedUrl == selectedUrl
                    AssistChip(
                        onClick = { onSelect(target.normalizedUrl) },
                        label = {
                            Text(
                                text = target.toComposerTabLabel(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.widthIn(max = 144.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            labelColor = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        when {
            isDisabledForSend -> ComposerDraftLinkPreviewStatusCard(
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.LinkOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                },
                title = stringResource(R.string.draft_link_preview_disabled),
                body = stringResource(R.string.draft_link_preview_remove),
                actionLabel = stringResource(R.string.action_restore),
                onAction = onRestore
            )

            isLoading -> ComposerDraftLinkPreviewLoadingCard()

            preview != null -> ComposerDraftLinkPreviewCard(
                preview = preview,
                onAction = onPreviewAction
            )

            error != null -> ComposerDraftLinkPreviewStatusCard(
                title = stringResource(R.string.draft_link_preview_unavailable),
                body = error
            )
        }
    }
}

@Composable
private fun ComposerDraftLinkPreviewCard(
    preview: WebPage,
    onAction: (LinkPreviewAction) -> Unit
) {
    val resolved = remember(preview) { preview.resolveLinkPreview() }
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(14.dp)
            )
            .padding(10.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .combinedClickable(onClick = { onAction(resolved.primaryAction) }),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = resolved.meta.kicker,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            resolved.meta.title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            resolved.meta.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        resolved.thumbnailData?.let { mediaData ->
            Box(
                modifier = Modifier
                    .width(116.dp)
                    .height(92.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(onClick = { onAction(resolved.mediaAction) })
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(mediaData)
                        .apply {
                            resolved.thumbnailCacheKey?.let {
                                memoryCacheKey(it)
                                diskCacheKey(it)
                            }
                        }
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                if (resolved.showPlayOverlay) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(40.dp)
                            .background(Color.Black.copy(alpha = 0.42f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayCircle,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerDraftLinkPreviewLoadingCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(14.dp)
            )
            .padding(10.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = stringResource(R.string.loading_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .height(12.dp)
                    .shimmerBackground(RoundedCornerShape(999.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .height(18.dp)
                    .shimmerBackground(RoundedCornerShape(10.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .height(14.dp)
                    .shimmerBackground(RoundedCornerShape(10.dp))
            )
        }

        Box(
            modifier = Modifier
                .width(116.dp)
                .height(92.dp)
                .clip(RoundedCornerShape(12.dp))
                .shimmerBackground(RoundedCornerShape(12.dp))
        )
    }
}

@Composable
private fun ComposerDraftLinkPreviewStatusCard(
    title: String,
    body: String,
    icon: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        icon?.invoke()
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(text = actionLabel)
            }
        }
    }
}

private fun LinkPreviewTarget.toComposerTabLabel(): String {
    val compactHost = host.removePrefix("www.").takeIf { it.isNotBlank() }
    return compactHost ?: displayLabel.ifBlank { normalizedUrl }
}

@Composable
private fun DocumentPreview(
    paths: List<String>,
    onCancel: () -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .heightIn(max = 240.dp),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onAdd) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(R.string.action_add))
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(
                paths,
                key = { index, path -> "document_preview_${path}_$index" }) { _, path ->
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
    val data = buildReplyPreviewData(message = message)
    InputContextPreviewCard(
        data = data,
        accentColor = MaterialTheme.colorScheme.primary,
        onCancel = onCancel
    )
}

@Composable
private fun EditPreview(
    message: MessageModel,
    onCancel: () -> Unit
) {
    val data = buildEditPreviewData(message = message)
    InputContextPreviewCard(
        data = data,
        accentColor = MaterialTheme.colorScheme.tertiary,
        onCancel = onCancel
    )
}

private data class InputContextPreviewData(
    val title: String,
    val sender: String?,
    val mediaTypeLabel: String?,
    val previewText: String,
    val previewEntities: List<MessageEntity>,
    val previewThumbnailPath: String?,
    val cancelDescription: String,
    val maxPreviewLines: Int
)

@Composable
private fun InputContextPreviewCard(
    data: InputContextPreviewData,
    accentColor: Color,
    onCancel: () -> Unit
) {
    val annotatedPreviewText = buildAnnotatedMessageTextWithEmoji(
        text = data.previewText,
        entities = data.previewEntities
    )
    val previewInlineContent = rememberMessageInlineContent(
        entities = data.previewEntities,
        fontSize = MaterialTheme.typography.bodySmall.fontSize.value
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(40.dp)
                .background(accentColor, RoundedCornerShape(999.dp))
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 40.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = data.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                data.sender?.takeIf { it.isNotBlank() }?.let { senderName ->
                    Text(
                        text = senderName,
                        style = MaterialTheme.typography.labelMedium,
                        color = accentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                data.mediaTypeLabel?.let { typeLabel ->
                    Box(
                        modifier = Modifier
                            .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = typeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                            maxLines = 1
                        )
                    }
                }

                Text(
                    text = annotatedPreviewText,
                    inlineContent = previewInlineContent,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = data.maxPreviewLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        data.previewThumbnailPath?.let { path ->
            AsyncImage(
                model = File(path),
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )
        }

        IconButton(
            onClick = onCancel,
            modifier = Modifier.size(30.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = data.cancelDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun buildReplyPreviewData(message: MessageModel): InputContextPreviewData {
    val mediaTypeMessage = stringResource(R.string.media_type_message)
    val mediaTypePhoto = stringResource(R.string.media_type_photo)
    val mediaTypeVideo = stringResource(R.string.media_type_video)
    val mediaTypeSticker = stringResource(R.string.media_type_sticker)
    val mediaTypeVoice = stringResource(R.string.media_type_voice)
    val mediaTypeVideoNote = stringResource(R.string.media_type_video_note)
    val mediaTypeGif = stringResource(R.string.media_type_gif)
    val mediaTypeLocation = stringResource(R.string.media_type_location)

    val previewContent = message.content.toPreviewContent(
        mediaTypeMessage = mediaTypeMessage,
        mediaTypePhoto = mediaTypePhoto,
        mediaTypeVideo = mediaTypeVideo,
        mediaTypeSticker = mediaTypeSticker,
        mediaTypeVoice = mediaTypeVoice,
        mediaTypeVideoNote = mediaTypeVideoNote,
        mediaTypeGif = mediaTypeGif,
        mediaTypeLocation = mediaTypeLocation
    )

    return InputContextPreviewData(
        title = stringResource(R.string.menu_reply),
        sender = message.senderName,
        mediaTypeLabel = previewContent.mediaTypeLabel,
        previewText = previewContent.text,
        previewEntities = previewContent.entities,
        previewThumbnailPath = previewContent.thumbnailPath,
        cancelDescription = stringResource(R.string.action_cancel_reply),
        maxPreviewLines = 2
    )
}

@Composable
private fun buildEditPreviewData(message: MessageModel): InputContextPreviewData {
    val mediaTypeMessage = stringResource(R.string.media_type_message)
    val mediaTypePhoto = stringResource(R.string.media_type_photo)
    val mediaTypeVideo = stringResource(R.string.media_type_video)
    val mediaTypeSticker = stringResource(R.string.media_type_sticker)
    val mediaTypeVoice = stringResource(R.string.media_type_voice)
    val mediaTypeVideoNote = stringResource(R.string.media_type_video_note)
    val mediaTypeGif = stringResource(R.string.media_type_gif)
    val mediaTypeLocation = stringResource(R.string.media_type_location)

    val previewContent = message.content.toPreviewContent(
        mediaTypeMessage = mediaTypeMessage,
        mediaTypePhoto = mediaTypePhoto,
        mediaTypeVideo = mediaTypeVideo,
        mediaTypeSticker = mediaTypeSticker,
        mediaTypeVoice = mediaTypeVoice,
        mediaTypeVideoNote = mediaTypeVideoNote,
        mediaTypeGif = mediaTypeGif,
        mediaTypeLocation = mediaTypeLocation
    )

    return InputContextPreviewData(
        title = stringResource(R.string.action_edit_message),
        sender = null,
        mediaTypeLabel = previewContent.mediaTypeLabel,
        previewText = previewContent.text,
        previewEntities = previewContent.entities,
        previewThumbnailPath = previewContent.thumbnailPath,
        cancelDescription = stringResource(R.string.action_cancel_edit),
        maxPreviewLines = 1
    )
}

private data class PreviewContentData(
    val text: String,
    val entities: List<MessageEntity>,
    val mediaTypeLabel: String?,
    val thumbnailPath: String?
)

private fun MessageContent.toPreviewContent(
    mediaTypeMessage: String,
    mediaTypePhoto: String,
    mediaTypeVideo: String,
    mediaTypeSticker: String,
    mediaTypeVoice: String,
    mediaTypeVideoNote: String,
    mediaTypeGif: String,
    mediaTypeLocation: String
): PreviewContentData {
    return when (this) {
        is MessageContent.Text -> PreviewContentData(
            text = text,
            entities = entities,
            mediaTypeLabel = null,
            thumbnailPath = null
        )

        is MessageContent.Photo -> {
            val captionText = caption.ifBlank { mediaTypePhoto }
            PreviewContentData(
                text = captionText,
                entities = if (caption.isBlank()) emptyList() else entities,
                mediaTypeLabel = mediaTypePhoto,
                thumbnailPath = thumbnailPath ?: path
            )
        }

        is MessageContent.Video -> {
            val captionText = caption.ifBlank { mediaTypeVideo }
            PreviewContentData(
                text = captionText,
                entities = if (caption.isBlank()) emptyList() else entities,
                mediaTypeLabel = mediaTypeVideo,
                thumbnailPath = thumbnailPath ?: path
            )
        }

        is MessageContent.Gif -> {
            val captionText = caption.ifBlank { mediaTypeGif }
            PreviewContentData(
                text = captionText,
                entities = if (caption.isBlank()) emptyList() else entities,
                mediaTypeLabel = mediaTypeGif,
                thumbnailPath = path
            )
        }

        is MessageContent.Sticker -> PreviewContentData(
            text = mediaTypeSticker,
            entities = emptyList(),
            mediaTypeLabel = mediaTypeSticker,
            thumbnailPath = path
        )

        is MessageContent.Voice -> PreviewContentData(
            text = mediaTypeVoice,
            entities = emptyList(),
            mediaTypeLabel = mediaTypeVoice,
            thumbnailPath = null
        )

        is MessageContent.VideoNote -> PreviewContentData(
            text = mediaTypeVideoNote,
            entities = emptyList(),
            mediaTypeLabel = mediaTypeVideoNote,
            thumbnailPath = thumbnail ?: path
        )

        is MessageContent.Location -> PreviewContentData(
            text = mediaTypeLocation,
            entities = emptyList(),
            mediaTypeLabel = mediaTypeLocation,
            thumbnailPath = null
        )

        is MessageContent.Venue -> PreviewContentData(
            text = title.ifBlank { mediaTypeLocation },
            entities = emptyList(),
            mediaTypeLabel = mediaTypeLocation,
            thumbnailPath = null
        )

        is MessageContent.Document -> {
            val fallback = fileName.ifBlank { mediaTypeMessage }
            val captionText = caption.ifBlank { fallback }
            PreviewContentData(
                text = captionText,
                entities = if (caption.isBlank()) emptyList() else entities,
                mediaTypeLabel = mediaTypeMessage,
                thumbnailPath = null
            )
        }

        is MessageContent.Audio -> {
            val captionText = caption.ifBlank { title.ifBlank { mediaTypeMessage } }
            PreviewContentData(
                text = captionText,
                entities = if (caption.isBlank()) emptyList() else entities,
                mediaTypeLabel = mediaTypeMessage,
                thumbnailPath = null
            )
        }

        else -> PreviewContentData(
            text = mediaTypeMessage,
            entities = emptyList(),
            mediaTypeLabel = mediaTypeMessage,
            thumbnailPath = null
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaPreview(
    paths: List<String>,
    onCancel: () -> Unit,
    onAdd: () -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onAdd) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(R.string.action_add))
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(
                paths,
                key = { index, path -> "media_preview_${path}_$index" }) { index, path ->
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