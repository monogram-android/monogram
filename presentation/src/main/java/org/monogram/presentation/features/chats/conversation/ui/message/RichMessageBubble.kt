package org.monogram.presentation.features.chats.conversation.ui.message

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.monogram.domain.models.ForwardInfo
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.WebPage
import org.monogram.domain.models.webapp.PageBlock
import org.monogram.domain.repository.FileRepository
import org.monogram.presentation.R
import org.monogram.presentation.core.util.DateFormatManager
import org.monogram.presentation.features.instantview.InstantViewBlock
import org.monogram.presentation.features.instantview.components.LocalFileRepository
import org.monogram.presentation.features.instantview.components.LocalOnUrlClick
import org.monogram.presentation.features.instantview.components.renderedTextOrNull
import org.monogram.presentation.features.instantview.components.resolvePathForViewer
import org.monogram.presentation.features.viewers.FullscreenImageItem
import org.monogram.presentation.features.stickers.ui.view.shimmerEffect

@Composable
internal fun RichMessageBubble(
    content: MessageContent.RichMessage,
    msg: MessageModel,
    isOutgoing: Boolean,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    fontSize: Float,
    isGroup: Boolean = false,
    bubbleRadius: Float = 18f,
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    onClick: (Offset) -> Unit = {},
    onLongClick: (Offset) -> Unit = {},
    onLinkPreviewAction: ((LinkPreviewAction) -> Unit)? = null,
    toProfile: (Long) -> Unit = {},
    onForwardOriginClick: (ForwardInfo) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val fileRepository: FileRepository = org.koin.compose.koinInject()
    val dateFormatManager: DateFormatManager = org.koin.compose.koinInject()
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    fun openRichPhoto(photo: WebPage.Photo, caption: String?, sourceUrl: String) {
        if (onLinkPreviewAction == null) return
        val previewPath = photo.path ?: photo.thumbnailPath.orEmpty()
        onLinkPreviewAction(
            LinkPreviewAction.OpenImageViewer(
                PreviewImageViewerRequest(
                    images = listOf(
                        FullscreenImageItem(
                            id = "rich:${photo.originalFileId.takeIf { it != 0 } ?: photo.fileId}:$sourceUrl",
                            previewSource = previewPath,
                            originalFileId = photo.originalFileId.takeIf { it != 0 }
                                ?: photo.fileId,
                            caption = caption
                        )
                    ),
                    sourceUrl = sourceUrl.ifBlank { previewPath }
                )
            )
        )
    }

    fun openRichVideo(
        path: String?,
        fileId: Int,
        supportsStreaming: Boolean,
        caption: String?,
        sourceUrl: String
    ) {
        if (onLinkPreviewAction == null) return
        scope.launch {
            val resolvedPath = fileRepository.resolvePathForViewer(fileId, path)
            if (resolvedPath.isNullOrBlank() && (!supportsStreaming || fileId == 0)) return@launch
            onLinkPreviewAction(
                LinkPreviewAction.OpenVideoViewer(
                    PreviewVideoViewerRequest(
                        path = resolvedPath.orEmpty(),
                        fileId = fileId,
                        supportsStreaming = supportsStreaming,
                        caption = caption,
                        sourceUrl = sourceUrl.ifBlank { resolvedPath.orEmpty() }
                    )
                )
            )
        }
    }

    val cornerRadius = bubbleRadius.dp
    val smallCorner = (bubbleRadius / 4f).coerceAtLeast(4f).dp
    val tailCorner = 2.dp
    val bubbleShape =
        remember(isOutgoing, isSameSenderAbove, isSameSenderBelow, cornerRadius, smallCorner) {
            RoundedCornerShape(
                topStart = if (!isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
                topEnd = if (isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
                bottomStart = if (!isOutgoing) {
                    if (isSameSenderBelow) smallCorner else tailCorner
                } else cornerRadius,
                bottomEnd = if (isOutgoing) {
                    if (isSameSenderBelow) smallCorner else tailCorner
                } else cornerRadius
            )
        }

    val backgroundColor =
        if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor =
        if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val timeColor = contentColor.copy(alpha = 0.7f)
    val timeText = formatTime(msg.date, dateFormatManager.getHourMinuteFormat())
    val renderModels = remember(msg.id, content.blocks) {
        content.blocks.mapIndexed { index, block ->
            RichBlockRenderModel(
                block = block,
                sourceUrl = block.richSourceUrl(),
                caption = block.richCaptionOrNull(),
                stateKeyPrefix = "message:${msg.id}:block:$index"
            )
        }
    }

    Column(
        modifier = modifier
            .widthIn(min = 120.dp),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = backgroundColor,
            contentColor = contentColor,
            tonalElevation = 1.dp,
            modifier = Modifier.pointerInput(msg.id) {
                detectTapGestures(
                    onTap = onClick,
                    onLongPress = onLongClick
                )
            }
        ) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isGroup && !isOutgoing && !isSameSenderAbove) {
                    MessageSenderName(msg, toProfile = toProfile)
                }

                msg.forwardInfo?.let { forward ->
                    ForwardContent(forward, isOutgoing, onForwardClick = onForwardOriginClick)
                }
                msg.replyToMsg?.let { reply ->
                    ReplyContent(
                        replyToMsg = reply,
                        isOutgoing = isOutgoing,
                        onClick = { onReplyClick(reply) }
                    )
                }

                CompositionLocalProvider(
                    LocalOnUrlClick provides { url -> uriHandler.openUri(normalizeUrl(url)) },
                    LocalFileRepository provides fileRepository
                ) {
                    if (!content.isFull && content.blocks.isEmpty()) {
                        RichMessagePlaceholder()
                    } else {
                        renderModels.forEach { renderModel ->
                            InstantViewBlock(
                                block = renderModel.block,
                                textSizeMultiplier = (fontSize / 16f).coerceIn(0.75f, 1.5f),
                                stateKeyPrefix = renderModel.stateKeyPrefix,
                                useMessageQuoteStyle = true,
                                onRichTextTap = onClick,
                                onRichTextLongPress = onLongClick,
                                onOpenPhotoFullscreen = { photo, caption ->
                                    openRichPhoto(
                                        photo = photo,
                                        caption = renderModel.caption
                                            ?: caption.renderedTextOrNull(),
                                        sourceUrl = renderModel.sourceUrl
                                    )
                                },
                                onOpenVideoFullscreen = { path, fileId, supportsStreaming, caption ->
                                    openRichVideo(
                                        path = path,
                                        fileId = fileId,
                                        supportsStreaming = supportsStreaming,
                                        caption = caption,
                                        sourceUrl = renderModel.sourceUrl
                                    )
                                }
                            )
                        }
                    }
                }

                MessageFooterRow(
                    messageId = msg.id,
                    timeText = timeText,
                    color = timeColor,
                    isEdited = msg.editDate > 0,
                    isOutgoing = isOutgoing,
                    isRead = msg.isRead,
                    sendingState = msg.sendingState,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }

        MessageReactionsView(
            reactions = msg.reactions,
            onReactionClick = onReactionClick,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

private data class RichBlockRenderModel(
    val block: PageBlock,
    val sourceUrl: String,
    val caption: String?,
    val stateKeyPrefix: String
)

private fun PageBlock.richSourceUrl(): String =
    when (this) {
        is PageBlock.PhotoBlock -> url
        is PageBlock.Embedded -> url
        is PageBlock.EmbeddedPost -> url
        else -> ""
    }

private fun PageBlock.richCaptionOrNull(): String? =
    when (this) {
        is PageBlock.PhotoBlock -> caption.renderedTextOrNull()
        is PageBlock.VideoBlock -> caption.renderedTextOrNull()
        is PageBlock.AnimationBlock -> caption.renderedTextOrNull()
        is PageBlock.AudioBlock -> caption.renderedTextOrNull()
        is PageBlock.Embedded -> caption.renderedTextOrNull()
        is PageBlock.EmbeddedPost -> caption.renderedTextOrNull()
        is PageBlock.Collage -> caption.renderedTextOrNull()
        is PageBlock.Slideshow -> caption.renderedTextOrNull()
        is PageBlock.MapBlock -> caption.renderedTextOrNull()
        else -> null
    }

@Composable
private fun RichMessagePlaceholder() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.shimmerEffect()
    ) {
        Text(
            text = stringResource(R.string.loading_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}
