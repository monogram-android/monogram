package org.monogram.presentation.features.profile

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.R
import org.monogram.presentation.features.instantview.InstantViewer
import org.monogram.presentation.features.viewers.ImageViewer
import org.monogram.presentation.features.viewers.VideoViewer
import org.monogram.presentation.features.viewers.YouTubeViewer
import org.monogram.presentation.features.webapp.MiniAppViewer
import org.monogram.presentation.features.webapp.components.InvoiceDialog
import org.monogram.presentation.features.webapp.components.MiniAppTOSBottomSheet
import org.monogram.presentation.features.webview.InternalWebView

@Composable
fun ProfileViewers(
    state: ProfileComponent.State,
    component: ProfileComponent
) {
    val localClipboard = LocalClipboard.current

    InstantViewOverlay(state, component)
    YouTubeOverlay(state, component, localClipboard)
    MiniAppOverlay(state, component)
    WebViewOverlay(state, component)
    ImagesOverlay(state, component, localClipboard)
    VideoOverlay(state, component, localClipboard)
    InvoiceOverlay(state, component)
    MiniAppTOSOverlay(state, component)
}

@Composable
private fun InstantViewOverlay(state: ProfileComponent.State, component: ProfileComponent) {
    AnimatedVisibility(
        visible = state.instantViewUrl != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        state.instantViewUrl?.let { url ->
            InstantViewer(
                url = url,
                messageRepository = component.messageRepository,
                fileRepository = component.messageRepository,
                onDismiss = { component.onDismissInstantView() },
                onOpenWebView = { component.onOpenWebView(it) }
            )
        }
    }
}

@Composable
private fun YouTubeOverlay(
    state: ProfileComponent.State,
    component: ProfileComponent,
    localClipboard: Clipboard
) {
    AnimatedVisibility(
        visible = state.youtubeUrl != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        state.youtubeUrl?.let { url ->
            YouTubeViewer(
                videoUrl = url,
                onDismiss = { component.onDismissYouTube() },
                onForward = {
                    val msg = state.mediaMessages.find {
                        (it.content as? MessageContent.Text)?.text?.contains(url) == true
                    }
                    if (msg != null) component.onForwardMessage(msg)
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
                isPipEnabled = !state.isInstalledFromGooglePlay
            )
        }
    }
}

@Composable
private fun MiniAppOverlay(state: ProfileComponent.State, component: ProfileComponent) {
    AnimatedVisibility(
        visible = state.miniAppUrl != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        if (state.miniAppUrl != null && state.miniAppName != null) {
            val title = state.chat?.title ?: listOfNotNull(state.user?.firstName, state.user?.lastName)
                .joinToString(" ")
                .ifBlank { stringResource(R.string.unknown_user) }

            MiniAppViewer(
                chatId = state.chatId,
                botUserId = state.user?.id ?: 0L,
                baseUrl = state.miniAppUrl,
                botName = title,
                botAvatarPath = state.chat?.avatarPath ?: state.user?.avatarPath,
                webAppRepository = component.messageRepository,
                onDismiss = { component.onDismissMiniApp() }
            )
        }
    }
}

@Composable
private fun WebViewOverlay(state: ProfileComponent.State, component: ProfileComponent) {
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
    state: ProfileComponent.State,
    component: ProfileComponent,
    localClipboard: Clipboard
) {
    AnimatedVisibility(
        visible = state.fullScreenImages != null,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f)
    ) {
        state.fullScreenImages?.let { images ->
            val autoDownload = remember(state.autoDownloadWifi, state.autoDownloadRoaming, state.autoDownloadMobile) {
                when {
                    component.downloadUtils.isWifiConnected() -> state.autoDownloadWifi
                    component.downloadUtils.isRoaming() -> state.autoDownloadRoaming
                    else -> state.autoDownloadMobile
                }
            }

            val viewerItems = remember(images, state.fullScreenImageMessageIds, state.mediaMessages) {
                if (state.fullScreenImageMessageIds.size == images.size) {
                    state.fullScreenImageMessageIds.mapIndexed { index, messageId ->
                        val message = state.mediaMessages.firstOrNull { it.id == messageId }
                        val resolvedPath = message?.displayMediaPathForViewer() ?: images[index]
                        ViewerMediaItem(messageId = messageId, path = resolvedPath)
                    }
                } else {
                    images.map { path ->
                        val message = state.mediaMessages.find { it.content.matchesDisplayPath(path) }
                        ViewerMediaItem(
                            messageId = message?.id ?: 0L,
                            path = message?.displayMediaPathForViewer() ?: path
                        )
                    }
                }
            }

            val viewerImages = remember(viewerItems) { viewerItems.map { it.path } }
            val imageMessageIds = remember(viewerItems) { viewerItems.map { it.messageId } }
            var currentImageIndex by remember(viewerImages, state.fullScreenStartIndex) {
                mutableIntStateOf(
                    state.fullScreenStartIndex.coerceIn(
                        0,
                        (viewerImages.lastIndex).coerceAtLeast(0)
                    )
                )
            }

            val currentViewerMessage = remember(currentImageIndex, imageMessageIds, state.mediaMessages) {
                imageMessageIds.getOrNull(currentImageIndex)
                    ?.takeIf { it != 0L }
                    ?.let { id -> state.mediaMessages.firstOrNull { it.id == id } }
            }

            val imageDownloadingStates = remember(imageMessageIds, state.mediaMessages) {
                imageMessageIds.map { id ->
                    val content = state.mediaMessages.firstOrNull { it.id == id }?.content
                    when (content) {
                        is MessageContent.Photo -> content.isDownloading
                        else -> false
                    }
                }
            }

            val imageDownloadProgressStates = remember(imageMessageIds, state.mediaMessages) {
                imageMessageIds.map { id ->
                    val content = state.mediaMessages.firstOrNull { it.id == id }?.content
                    when (content) {
                        is MessageContent.Photo -> content.downloadProgress
                        else -> 0f
                    }
                }
            }

            if (viewerImages.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    ImageViewer(
                        images = viewerImages,
                        startIndex = state.fullScreenStartIndex.coerceIn(0, viewerImages.lastIndex),
                        onDismiss = component::onDismissImages,
                        autoDownload = autoDownload,
                        onPageChanged = { index ->
                            currentImageIndex = index
                            if (!state.isViewingProfilePhotos && state.canLoadMoreMedia && !state.isLoadingMoreMedia &&
                                index >= viewerImages.size - 5
                            ) {
                                component.onLoadMoreMedia()
                            }

                            if (!state.isViewingProfilePhotos) {
                                imageMessageIds.getOrNull(index)?.takeIf { it != 0L }?.let(component::onDownloadHighRes)
                                imageMessageIds.getOrNull(index + 1)?.takeIf { it != 0L }?.let(component::onDownloadHighRes)
                            }
                        },
                        onForward = { path ->
                            val msg = currentViewerMessage ?: state.mediaMessages.find { it.content.matchesDisplayPath(path) }
                            msg?.let { component.onForwardMessage(it) }
                        },
                        onDelete = { path ->
                            val msg = currentViewerMessage ?: state.mediaMessages.find { it.content.matchesDisplayPath(path) }
                            if (msg?.isOutgoing == true) {
                                component.onDeleteMessage(msg, true)
                                component.onDismissImages()
                            }
                        },
                        onCopyLink = { path ->
                            val msg = currentViewerMessage ?: state.mediaMessages.find { it.content.matchesDisplayPath(path) }
                            val link = if (msg != null) {
                                "https://t.me/c/${state.chatId.toString().removePrefix("-100")}/${msg.id shr 20}"
                            } else {
                                path
                            }
                            localClipboard.nativeClipboard.setPrimaryClip(
                                ClipData.newPlainText("", AnnotatedString(link))
                            )
                        },
                        onCopyText = { path ->
                            val msg = currentViewerMessage ?: state.mediaMessages.find { it.content.matchesDisplayPath(path) }
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
                            val msg = currentViewerMessage ?: state.mediaMessages.find { it.content.matchesDisplayPath(path) }
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
                        captions = state.fullScreenCaptions.filterNotNull(),
                        imageDownloadingStates = imageDownloadingStates,
                        imageDownloadProgressStates = imageDownloadProgressStates,
                        downloadUtils = component.downloadUtils,
                        showImageNumber = false
                    )

                    if (state.isViewingProfilePhotos && state.isProfilePhotoHdLoading) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 56.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(
                                    text = "Loading HD",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoOverlay(
    state: ProfileComponent.State,
    component: ProfileComponent,
    localClipboard: Clipboard
) {
    val videoVisible =
        (state.fullScreenVideoPath != null || state.fullScreenVideoMessageId != null) && state.fullScreenImages == null

    AnimatedVisibility(
        visible = videoVisible,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f)
    ) {
        if (videoVisible) {
            val messageId = state.fullScreenVideoMessageId
            val path = state.fullScreenVideoPath

            val msg = remember(messageId, path, state.mediaMessages) {
                state.mediaMessages.find { it.id == messageId } ?: state.mediaMessages.find {
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
                    VideoViewer(
                        path = finalPath,
                        onDismiss = component::onDismissVideo,
                        isGesturesEnabled = state.isPlayerGesturesEnabled,
                        isDoubleTapSeekEnabled = state.isPlayerDoubleTapSeekEnabled,
                        seekDuration = state.playerSeekDuration,
                        isZoomEnabled = state.isPlayerZoomEnabled,
                        onForward = { videoPath ->
                            val forwardMsg = state.mediaMessages.find {
                                it.content.matchesDisplayPath(videoPath)
                            }
                            forwardMsg?.let { component.onForwardMessage(it) }
                        },
                        onDelete = { videoPath ->
                            val deleteMsg = state.mediaMessages.find {
                                it.content.matchesDisplayPath(videoPath)
                            }
                            if (deleteMsg?.isOutgoing == true) {
                                component.onDeleteMessage(deleteMsg, true)
                                component.onDismissVideo()
                            }
                        },
                        onCopyLink = { videoPath ->
                            val linkMsg = state.mediaMessages.find {
                                it.content.matchesDisplayPath(videoPath)
                            }
                            val link = if (linkMsg != null) {
                                "https://t.me/c/${state.chatId.toString().removePrefix("-100")}/${linkMsg.id shr 20}"
                            } else {
                                videoPath
                            }
                            localClipboard.nativeClipboard.setPrimaryClip(
                                ClipData.newPlainText("", AnnotatedString(link))
                            )
                        },
                        onCopyText = { videoPath ->
                            val textMsg = state.mediaMessages.find {
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
                        onSaveGif = if (state.mediaMessages.any { (it.content as? MessageContent.Gif)?.path == finalPath }) {
                            { videoPath -> component.onAddToGifs(videoPath) }
                        } else null,
                        caption = state.fullScreenVideoCaption,
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
private fun InvoiceOverlay(state: ProfileComponent.State, component: ProfileComponent) {
    if (state.invoiceSlug != null || state.invoiceMessageId != null) {
        InvoiceDialog(
            slug = state.invoiceSlug,
            chatId = state.chatId,
            messageId = state.invoiceMessageId,
            paymentRepository = component.messageRepository,
            fileRepository = component.messageRepository,
            onDismiss = { status -> component.onDismissInvoice(status) }
        )
    }
}

@Composable
private fun MiniAppTOSOverlay(state: ProfileComponent.State, component: ProfileComponent) {
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
