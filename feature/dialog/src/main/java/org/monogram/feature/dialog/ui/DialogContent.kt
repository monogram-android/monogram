package org.monogram.feature.dialog.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.feature.dialog.DialogComponent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogContent(component: DialogComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsStateWithLifecycle()
    var mediaViewerSeenBy by remember { mutableStateOf<Message?>(null) }
    val receiptHolder = remember { ReadReceiptHolder() }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    CompositionLocalProvider(LocalMarkupParser provides component.markup, LocalReadReceiptHolder provides receiptHolder) {
        MessageMediaViewerScope(
            repository = component.mediaRepository,
            onCaptionUrl = { url -> absoluteLink(url)?.let { runCatching { uriHandler.openUri(it) } } },
            onCaptionMention = { target -> runCatching { uriHandler.openUri(target) } },
            chatCanForward = state.canForward,
            onForward = component::onForwardMessages,
            onDelete = { messages, revoke ->
                messages.forEach { component.onDelete(it.id.id, revoke) }
            },
            deleteOffer = { messages ->
                deleteOffer(state, messages, System.currentTimeMillis() / 1000)
            },
            onShowInChat = { message -> component.onJumpToMessage(message.id.id) },
            onReply = if (state.canSendPlain || state.canSendPhotos) {
                { message ->
                    if (messageMenuActions(state, message).canReply) {
                        component.onReply(message, focusComposer = true)
                    }
                }
            } else null,
            onEnsureReceipts = { message -> component.onLoadReadReceipts(message.id.id) },
            onOpenSeenBy = { message -> mediaViewerSeenBy = message },
            knownAlbumMessages = { message ->
                state.messages.filter {
                    it.id.chatId == message.id.chatId && it.groupedId == message.groupedId
                }
            },
        ) {
            DialogScreen(component, modifier)
        }
        val sheetMessage = mediaViewerSeenBy
        if (sheetMessage != null) {
            AppModalSheet(onDismissRequest = { mediaViewerSeenBy = null }) {
                MessageSeenByRow(
                    viewers = receiptHolder.viewers?.invoke(sheetMessage),
                    viewerAvatar = { viewer -> receiptHolder.avatar?.invoke(viewer) },
                    mediaRepository = component.mediaRepository,
                    onOpenProfile = { id ->
                        mediaViewerSeenBy = null
                        component.onOpenPeer(PeerId(id))
                    },
                    startExpanded = true,
                )
            }
        }
    }
}
