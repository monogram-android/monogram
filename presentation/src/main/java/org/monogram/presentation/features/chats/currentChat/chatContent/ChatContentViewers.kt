package org.monogram.presentation.features.chats.currentChat.chatContent

import android.content.ClipData
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import org.monogram.domain.models.MessageContent
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
    state: ChatComponent.State,
    component: ChatComponent,
    localClipboard: Clipboard
) {
    AnimatedVisibility(
        visible = state.instantViewUrl != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        state.instantViewUrl?.let { url ->
            InstantViewer(
                url = url,
                messageRepository = component.repositoryMessage,
                onDismiss = { component.onDismissInstantView() },
                onOpenWebView = { component.onOpenWebView(it) },
                videoPlayerPool = component.videoPlayerPool
            )
        }
    }

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
                    component.onForwardMessage(state.messages.find {
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
                isPipEnabled = !state.isInstalledFromGooglePlay
            )
        }
    }

    AnimatedVisibility(
        visible = state.miniAppUrl != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        if (state.miniAppUrl != null && state.miniAppName != null) {
            MiniAppViewer(
                chatId = state.chatId,
                botUserId = state.miniAppBotUserId,
                baseUrl = state.miniAppUrl,
                botName = state.chatTitle,
                botAvatarPath = state.chatAvatar,
                messageRepository = component.repositoryMessage,
                onDismiss = { component.onDismissMiniApp() }
            )
        }
    }

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

            val imageMessageIds = remember(images, state.fullScreenImageMessageIds, state.messages) {
                if (state.fullScreenImageMessageIds.size == images.size) {
                    state.fullScreenImageMessageIds
                } else {
                    images.map { path ->
                        state.messages.firstOrNull {
                            when (val content = it.content) {
                                is MessageContent.Photo -> content.path == path
                                is MessageContent.Video -> content.path == path
                                is MessageContent.Gif -> content.path == path
                                else -> false
                            }
                        }?.id ?: 0L
                    }
                }
            }

            val imageDownloadingStates = remember(imageMessageIds, state.messages) {
                imageMessageIds.map { id ->
                    val content = state.messages.firstOrNull { it.id == id }?.content
                    when (content) {
                        is MessageContent.Photo -> content.isDownloading
                        else -> false
                    }
                }
            }

            val imageDownloadProgressStates = remember(imageMessageIds, state.messages) {
                imageMessageIds.map { id ->
                    val content = state.messages.firstOrNull { it.id == id }?.content
                    when (content) {
                        is MessageContent.Photo -> content.downloadProgress
                        else -> 0f
                    }
                }
            }

            ImageViewer(
                images = images,
                startIndex = state.fullScreenStartIndex,
                onDismiss = component::onDismissImages,
                autoDownload = autoDownload,
                onPageChanged = { index ->
                    imageMessageIds.getOrNull(index)?.takeIf { it != 0L }?.let(component::onDownloadHighRes)
                    imageMessageIds.getOrNull(index + 1)?.takeIf { it != 0L }?.let(component::onDownloadHighRes)
                },
                onForward = { path ->
                    val msg = state.messages.find {
                        when (val content = it.content) {
                            is MessageContent.Photo -> content.path == path
                            is MessageContent.Video -> content.path == path
                            is MessageContent.Gif -> content.path == path
                            else -> false
                        }
                    }
                    msg?.let { component.onForwardMessage(it) }
                },
                onDelete = { path ->
                    val msg = state.messages.find {
                        when (val content = it.content) {
                            is MessageContent.Photo -> content.path == path
                            is MessageContent.Video -> content.path == path
                            is MessageContent.Gif -> content.path == path
                            else -> false
                        }
                    }
                    if (msg?.isOutgoing == true) {
                        component.onDeleteMessage(msg)
                        component.onDismissImages()
                    }
                },
                onCopyLink = { path ->
                    val msg = state.messages.find {
                        when (val content = it.content) {
                            is MessageContent.Photo -> content.path == path
                            is MessageContent.Video -> content.path == path
                            is MessageContent.Gif -> content.path == path
                            else -> false
                        }
                    }
                    val link = if (msg != null) {
                        if (!state.isGroup && !state.isChannel) {
                            "tg://openmessage?user_id=${state.chatId}&message_id=${msg.id shr 20}"
                        } else {
                            "https://t.me/c/${state.chatId.toString().removePrefix("-100")}/${msg.id shr 20}"
                        }
                    } else {
                        path
                    }

                    localClipboard.nativeClipboard.setPrimaryClip(
                        ClipData.newPlainText("", AnnotatedString(link))
                    )
                },
                onCopyText = { path ->
                    val msg = state.messages.find {
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
                    val msg = state.messages.find {
                        when (val content = it.content) {
                            is MessageContent.Video -> content.path == path
                            is MessageContent.Gif -> content.path == path
                            else -> false
                        }
                    }
                    if (msg != null) {
                        component.onOpenVideo(
                            path = path, messageId = msg.id, caption = when (val content = msg.content) {
                                is MessageContent.Video -> content.caption
                                is MessageContent.Gif -> content.caption
                                else -> null
                            }
                        )
                    } else {
                        component.onOpenVideo(path = path)
                    }
                },
                captions = state.fullScreenCaptions.filterNotNull(),
                imageDownloadingStates = imageDownloadingStates,
                imageDownloadProgressStates = imageDownloadProgressStates,
                downloadUtils = component.downloadUtils
            )
        }
    }

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

            val msg = remember(messageId, path, state.messages) {
                state.messages.find { it.id == messageId } ?: state.messages.find {
                    when (val content = it.content) {
                        is MessageContent.Video -> content.path == path
                        is MessageContent.Gif -> content.path == path
                        else -> false
                    }
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
                        isGesturesEnabled = state.isPlayerGesturesEnabled,
                        isDoubleTapSeekEnabled = state.isPlayerDoubleTapSeekEnabled,
                        seekDuration = state.playerSeekDuration,
                        isZoomEnabled = state.isPlayerZoomEnabled,
                        onForward = { videoPath ->
                            val forwardMsg = state.messages.find {
                                when (val content = it.content) {
                                    is MessageContent.Video -> content.path == videoPath
                                    is MessageContent.Gif -> content.path == videoPath
                                    else -> false
                                }
                            }
                            forwardMsg?.let { component.onForwardMessage(it) }
                        },
                        onDelete = { videoPath ->
                            val deleteMsg = state.messages.find {
                                when (val content = it.content) {
                                    is MessageContent.Video -> content.path == videoPath
                                    is MessageContent.Gif -> content.path == videoPath
                                    else -> false
                                }
                            }
                            if (deleteMsg?.isOutgoing == true) {
                                component.onDeleteMessage(deleteMsg)
                                component.onDismissVideo()
                            }
                        },
                        onCopyLink = { videoPath ->
                            val linkMsg = state.messages.find {
                                when (val content = it.content) {
                                    is MessageContent.Video -> content.path == videoPath
                                    is MessageContent.Gif -> content.path == videoPath
                                    else -> false
                                }
                            }
                            val link = if (linkMsg != null) {
                                if (!state.isGroup && !state.isChannel) {
                                    "tg://openmessage?user_id=${state.chatId}&message_id=${linkMsg.id shr 20}"
                                } else {
                                    "https://t.me/c/${
                                        state.chatId.toString().removePrefix("-100")
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
                            val textMsg = state.messages.find {
                                when (val content = it.content) {
                                    is MessageContent.Video -> content.path == videoPath
                                    is MessageContent.Gif -> content.path == videoPath
                                    else -> false
                                }
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
                        onSaveGif = if (state.messages.any { (it.content as? MessageContent.Gif)?.path == finalPath }) {
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

    if (state.invoiceSlug != null || state.invoiceMessageId != null) {
        InvoiceDialog(
            slug = state.invoiceSlug,
            chatId = state.chatId,
            messageId = state.invoiceMessageId,
            messageRepository = component.repositoryMessage,
            onDismiss = { status -> component.onDismissInvoice(status) }
        )
    }

    MiniAppTOSBottomSheet(
        isVisible = state.showMiniAppTOS,
        onDismiss = { component.onDismissMiniAppTOS() },
        onAccept = { component.onAcceptMiniAppTOS() }
    )
}
