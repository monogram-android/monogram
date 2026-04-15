package org.monogram.presentation.features.chats.currentChat.chatContent

import android.content.ClipData
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.features.chats.currentChat.ChatComponent
import org.monogram.presentation.features.instantview.InstantViewer
import org.monogram.presentation.features.viewers.ImageViewer
import org.monogram.presentation.features.viewers.VideoViewer
import org.monogram.presentation.features.viewers.YouTubeViewer
import org.monogram.presentation.features.webapp.MiniAppViewer
import org.monogram.presentation.features.webapp.components.InvoiceDialog
import org.monogram.presentation.features.webapp.components.MiniAppTOSBottomSheet
import org.monogram.presentation.features.webview.InternalWebView

@Composable
fun ChatContentViewers(
    chatUiState: ChatComponent.ChatUiState,
    appearanceState: ChatComponent.AppearanceState,
    messagesState: ChatComponent.MessagesState,
    mediaViewerState: ChatComponent.MediaViewerState,
    component: ChatComponent,
    localClipboard: Clipboard
) {
    InstantViewOverlay(mediaViewerState, component)
    YouTubeOverlay(chatUiState, messagesState, mediaViewerState, component, localClipboard)
    MiniAppOverlay(chatUiState, mediaViewerState, component)
    WebViewOverlay(mediaViewerState, component)
    ImagesOverlay(chatUiState, appearanceState, messagesState, mediaViewerState, component, localClipboard)
    VideoOverlay(chatUiState, appearanceState, messagesState, mediaViewerState, component, localClipboard)
    InvoiceOverlay(chatUiState, mediaViewerState, component)
    MiniAppTOSOverlay(mediaViewerState, component)
}

@Composable
private fun InstantViewOverlay(state: ChatComponent.MediaViewerState, component: ChatComponent) {
    AnimatedVisibility(
        visible = state.instantViewUrl != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        state.instantViewUrl?.let { url ->
            InstantViewer(
                url = url,
                messageRepository = component.repositoryMessage,
                fileRepository = component.repositoryMessage,
                onDismiss = { component.onDismissInstantView() },
                onOpenWebView = { component.onOpenWebView(it) }
            )
        }
    }
}

@Composable
private fun YouTubeOverlay(
    chatUiState: ChatComponent.ChatUiState,
    messagesState: ChatComponent.MessagesState,
    mediaViewerState: ChatComponent.MediaViewerState,
    component: ChatComponent,
    localClipboard: Clipboard
) {
    AnimatedVisibility(
        visible = mediaViewerState.youtubeUrl != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        mediaViewerState.youtubeUrl?.let { url ->
            YouTubeViewer(
                videoUrl = url,
                onDismiss = { component.onDismissYouTube() },
                onForward = {
                    component.onForwardMessage(messagesState.messages.find {
                        (it.content as? MessageContent.Text)?.text?.contains(
                            url
                        ) == true
                    } ?: return@YouTubeViewer)
                },
                onCopyLink = {
                    localClipboard.nativeClipboard.setPrimaryClip(
                        ClipData.newPlainText("", AnnotatedString(it))
                    )
                },
                onCopyText = {
                    localClipboard.nativeClipboard.setPrimaryClip(
                        ClipData.newPlainText("", AnnotatedString(it))
                    )
                },
                isPipEnabled = !chatUiState.isInstalledFromGooglePlay
            )
        }
    }
}

@Composable
private fun MiniAppOverlay(
    chatUiState: ChatComponent.ChatUiState,
    mediaViewerState: ChatComponent.MediaViewerState,
    component: ChatComponent
) {
    AnimatedVisibility(
        visible = mediaViewerState.miniAppUrl != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        if (mediaViewerState.miniAppUrl != null && mediaViewerState.miniAppName != null) {
            MiniAppViewer(
                chatId = chatUiState.chatId,
                botUserId = mediaViewerState.miniAppBotUserId,
                baseUrl = mediaViewerState.miniAppUrl,
                botName = chatUiState.chatTitle,
                botAvatarPath = chatUiState.chatAvatar,
                webAppRepository = component.repositoryMessage,
                onDismiss = { component.onDismissMiniApp() }
            )
        }
    }
}

@Composable
private fun WebViewOverlay(state: ChatComponent.MediaViewerState, component: ChatComponent) {
    AnimatedVisibility(
        visible = state.webViewUrl != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        state.webViewUrl?.let { url ->
            InternalWebView(
                url = url,
                onDismiss = { component.onDismissWebView() }
            )
        }
    }
}

@Composable
private fun ImagesOverlay(
    chatUiState: ChatComponent.ChatUiState,
    appearanceState: ChatComponent.AppearanceState,
    messagesState: ChatComponent.MessagesState,
    mediaViewerState: ChatComponent.MediaViewerState,
    component: ChatComponent,
    localClipboard: Clipboard
) {
    AnimatedVisibility(
        visible = mediaViewerState.fullScreenImages != null,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f)
    ) {
        mediaViewerState.fullScreenImages?.let { images ->
            val autoDownload = remember(
                appearanceState.autoDownloadWifi,
                appearanceState.autoDownloadRoaming,
                appearanceState.autoDownloadMobile
            ) {
                when {
                    component.downloadUtils.isWifiConnected() -> appearanceState.autoDownloadWifi
                    component.downloadUtils.isRoaming() -> appearanceState.autoDownloadRoaming
                    else -> appearanceState.autoDownloadMobile
                }
            }

            val viewerItems = remember(images, mediaViewerState.fullScreenImageMessageIds, messagesState.messages) {
                if (mediaViewerState.fullScreenImageMessageIds.size == images.size) {
                    mediaViewerState.fullScreenImageMessageIds.mapIndexed { index, messageId ->
                        val message = messagesState.messages.firstOrNull { it.id == messageId }
                        val resolvedPath = message?.displayMediaPathForViewer() ?: images[index]
                        ViewerMediaItem(messageId = messageId, path = resolvedPath)
                    }
                } else {
                    images.map { path ->
                        val message = messagesState.messages.firstOrNull { it.content.matchesDisplayPath(path) }
                        ViewerMediaItem(
                            messageId = message?.id ?: 0L,
                            path = message?.displayMediaPathForViewer() ?: path
                        )
                    }
                }
            }

            val viewerImages = remember(viewerItems) { viewerItems.map { it.path } }
            val imageMessageIds = remember(viewerItems) { viewerItems.map { it.messageId } }
            var currentImageIndex by remember(viewerImages, mediaViewerState.fullScreenStartIndex) {
                mutableIntStateOf(
                    mediaViewerState.fullScreenStartIndex.coerceIn(
                        0,
                        (viewerImages.lastIndex).coerceAtLeast(0)
                    )
                )
            }

            val currentViewerMessage = remember(currentImageIndex, imageMessageIds, messagesState.messages) {
                imageMessageIds.getOrNull(currentImageIndex)
                    ?.takeIf { it != 0L }
                    ?.let { id -> messagesState.messages.firstOrNull { it.id == id } }
            }

            val imageDownloadingStates = remember(imageMessageIds, messagesState.messages) {
                imageMessageIds.map { id ->
                    val content = messagesState.messages.firstOrNull { it.id == id }?.content
                    when (content) {
                        is MessageContent.Photo -> content.isDownloading
                        else -> false
                    }
                }
            }

            val imageDownloadProgressStates = remember(imageMessageIds, messagesState.messages) {
                imageMessageIds.map { id ->
                    val content = messagesState.messages.firstOrNull { it.id == id }?.content
                    when (content) {
                        is MessageContent.Photo -> content.downloadProgress
                        else -> 0f
                    }
                }
            }

            if (viewerImages.isNotEmpty()) {
                ImageViewer(
                    images = viewerImages,
                    startIndex = mediaViewerState.fullScreenStartIndex.coerceIn(0, viewerImages.lastIndex),
                    onDismiss = component::onDismissImages,
                    autoDownload = autoDownload,
                    onPageChanged = { index ->
                        currentImageIndex = index
                        imageMessageIds.getOrNull(index)?.takeIf { it != 0L }?.let(component::onDownloadHighRes)
                        imageMessageIds.getOrNull(index + 1)?.takeIf { it != 0L }?.let(component::onDownloadHighRes)
                    },
                    onForward = { path ->
                        val msg = currentViewerMessage ?: messagesState.messages.find { it.content.matchesDisplayPath(path) }
                        msg?.let { component.onForwardMessage(it) }
                    },
                    onDelete = { path ->
                        val msg = currentViewerMessage ?: messagesState.messages.find { it.content.matchesDisplayPath(path) }
                        if (msg?.isOutgoing == true) {
                            component.onDeleteMessage(msg, true)
                            component.onDismissImages()
                        }
                    },
                    onCopyLink = { path ->
                        val msg = currentViewerMessage ?: messagesState.messages.find { it.content.matchesDisplayPath(path) }
                        val link = if (msg != null) {
                            if (!chatUiState.isGroup && !chatUiState.isChannel) {
                                "tg://openmessage?user_id=${chatUiState.chatId}&message_id=${msg.id shr 20}"
                            } else {
                                "https://t.me/c/${chatUiState.chatId.toString().removePrefix("-100")}/${msg.id shr 20}"
                            }
                        } else {
                            path
                        }
                        localClipboard.nativeClipboard.setPrimaryClip(
                            ClipData.newPlainText("", AnnotatedString(link))
                        )
                    },
                    onCopyText = { path ->
                        val msg = messagesState.messages.find {
                            when (val content = it.content) {
                                is MessageContent.Photo -> content.path == path
                                is MessageContent.Video -> content.path == path
                                is MessageContent.Gif -> content.path == path
                                else -> false
                            }
                        }
                        val textToCopy = when (val content = msg?.content) {
                            is MessageContent.Photo -> content.caption
                            is MessageContent.Video -> content.caption
                            is MessageContent.Gif -> content.caption
                            else -> ""
                        }
                        if (textToCopy.isNotEmpty()) {
                            localClipboard.nativeClipboard.setPrimaryClip(
                                ClipData.newPlainText("", AnnotatedString(textToCopy))
                            )
                        }
                    },
                    onVideoClick = { path ->
                        val msg = currentViewerMessage ?: messagesState.messages.find { it.content.matchesDisplayPath(path) }
                        if (msg != null) {
                            val mediaPath = msg.displayMediaPathForViewer() ?: path
                            component.onOpenVideo(
                                path = mediaPath,
                                messageId = msg.id,
                                caption = when (val content = msg.content) {
                                    is MessageContent.Video -> content.caption
                                    is MessageContent.Gif -> content.caption
                                    else -> null
                                }
                            )
                        } else {
                            component.onOpenVideo(path = path, messageId = null, caption = null)
                        }
                    },
                    captions = mediaViewerState.fullScreenCaptions,
                    imageDownloadingStates = imageDownloadingStates,
                    imageDownloadProgressStates = imageDownloadProgressStates,
                    downloadUtils = component.downloadUtils
                )
            }
        }
    }
}

@Composable
private fun VideoOverlay(
    chatUiState: ChatComponent.ChatUiState,
    appearanceState: ChatComponent.AppearanceState,
    messagesState: ChatComponent.MessagesState,
    mediaViewerState: ChatComponent.MediaViewerState,
    component: ChatComponent,
    localClipboard: Clipboard
) {
    val videoVisible =
        (mediaViewerState.fullScreenVideoPath != null || mediaViewerState.fullScreenVideoMessageId != null) &&
                mediaViewerState.fullScreenImages == null

    AnimatedVisibility(
        visible = videoVisible,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f)
    ) {
        if (videoVisible) {
            val messageId = mediaViewerState.fullScreenVideoMessageId
            val path = mediaViewerState.fullScreenVideoPath

            val msg = remember(messageId, path, messagesState.messages) {
                messagesState.messages.find { it.id == messageId } ?: messagesState.messages.find {
                    it.content.matchesDisplayPath(path ?: "")
                }
            }

            val videoContent = msg?.content as? MessageContent.Video
            val gifContent = msg?.content as? MessageContent.Gif

            val fileId = videoContent?.fileId ?: gifContent?.fileId ?: 0
            val supportsStreaming = videoContent?.supportsStreaming ?: false
            val finalPath = path ?: videoContent?.path ?: gifContent?.path ?: ""

            if (finalPath.isNotBlank() || (supportsStreaming && fileId != 0)) {
                key(finalPath, fileId) {
                    Log.d("ChatContentViewers", "Rendering VideoViewer for $finalPath")
                    VideoViewer(
                        path = finalPath,
                        onDismiss = component::onDismissVideo,
                        isGesturesEnabled = appearanceState.isPlayerGesturesEnabled,
                        isDoubleTapSeekEnabled = appearanceState.isPlayerDoubleTapSeekEnabled,
                        seekDuration = appearanceState.playerSeekDuration,
                        isZoomEnabled = appearanceState.isPlayerZoomEnabled,
                        onForward = { videoPath ->
                            val forwardMsg = messagesState.messages.find {
                                it.content.matchesDisplayPath(videoPath)
                            }
                            forwardMsg?.let { component.onForwardMessage(it) }
                        },
                        onDelete = { videoPath ->
                            val deleteMsg = messagesState.messages.find {
                                it.content.matchesDisplayPath(videoPath)
                            }
                            if (deleteMsg?.isOutgoing == true) {
                                component.onDeleteMessage(deleteMsg, true)
                                component.onDismissVideo()
                            }
                        },
                        onCopyLink = { videoPath ->
                            val linkMsg = messagesState.messages.find {
                                it.content.matchesDisplayPath(videoPath)
                            }
                            val link = if (linkMsg != null) {
                                if (!chatUiState.isGroup && !chatUiState.isChannel) {
                                    "tg://openmessage?user_id=${chatUiState.chatId}&message_id=${linkMsg.id shr 20}"
                                } else {
                                    "https://t.me/c/${
                                        chatUiState.chatId.toString().removePrefix("-100")
                                    }/${linkMsg.id shr 20}"
                                }
                            } else {
                                videoPath
                            }
                            localClipboard.nativeClipboard.setPrimaryClip(
                                ClipData.newPlainText("", AnnotatedString(link))
                            )
                        },
                        onCopyText = { videoPath ->
                            val textMsg = messagesState.messages.find {
                                it.content.matchesDisplayPath(videoPath)
                            }
                            val textToCopy = when (val content = textMsg?.content) {
                                is MessageContent.Video -> content.caption
                                is MessageContent.Gif -> content.caption
                                else -> ""
                            }
                            if (textToCopy.isNotEmpty()) {
                                localClipboard.nativeClipboard.setPrimaryClip(
                                    ClipData.newPlainText("", AnnotatedString(textToCopy))
                                )
                            }
                        },
                        onSaveGif = if (messagesState.messages.any { (it.content as? MessageContent.Gif)?.path == finalPath }) {
                            { videoPath -> component.onAddToGifs(videoPath) }
                        } else null,
                        caption = mediaViewerState.fullScreenVideoCaption,
                        fileId = fileId,
                        supportsStreaming = supportsStreaming,
                        downloadUtils = component.downloadUtils
                    )
                }
            }
        }
    }
}

@Composable
private fun InvoiceOverlay(
    chatUiState: ChatComponent.ChatUiState,
    mediaViewerState: ChatComponent.MediaViewerState,
    component: ChatComponent
) {
    if (mediaViewerState.invoiceSlug != null || mediaViewerState.invoiceMessageId != null) {
        InvoiceDialog(
            slug = mediaViewerState.invoiceSlug,
            chatId = chatUiState.chatId,
            messageId = mediaViewerState.invoiceMessageId,
            paymentRepository = component.repositoryMessage,
            fileRepository = component.repositoryMessage,
            onDismiss = { status -> component.onDismissInvoice(status) }
        )
    }
}

@Composable
private fun MiniAppTOSOverlay(state: ChatComponent.MediaViewerState, component: ChatComponent) {
    MiniAppTOSBottomSheet(
        isVisible = state.showMiniAppTOS,
        onDismiss = { component.onDismissMiniAppTOS() },
        onAccept = { component.onAcceptMiniAppTOS() }
    )
}

private data class ViewerMediaItem(
    val messageId: Long,
    val path: String
)

private fun MessageModel.displayMediaPathForViewer(): String? {
    return when (val content = content) {
        is MessageContent.Photo -> content.path ?: content.thumbnailPath
        is MessageContent.Video -> content.path ?: content.thumbnailPath
        is MessageContent.Gif -> content.path
        else -> null
    }
}

private fun MessageContent.matchesDisplayPath(path: String): Boolean {
    return when (this) {
        is MessageContent.Photo -> (this.path ?: this.thumbnailPath) == path
        is MessageContent.Video -> this.path == path || this.thumbnailPath == path
        is MessageContent.Gif -> this.path == path
        else -> false
    }
}
