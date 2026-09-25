package org.monogram.feature.chats.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.components.toUiMessage
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.Chat
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.SearchPeer
import org.monogram.core.ui.components.ChatRowMetrics
import org.monogram.core.ui.components.SectionHeader
import org.monogram.feature.chats.titleForSearchMessage
import org.monogram.feature.chats.toChat
import org.monogram.feature.chats.toSearchChat
import org.monogram.network.http.MediaRepository

internal fun LazyListScope.globalSearchSections(
    query: String,
    localCount: Int,
    people: List<SearchPeer>,
    foundChats: List<SearchPeer>,
    messages: List<Message>,
    listedChats: List<Chat>,
    searchLoading: Boolean,
    searchLoadingMore: Boolean,
    searchError: TelegramError?,
    retryLabel: String,
    peopleLabel: String,
    chatsLabel: String,
    messagesLabel: String,
    selectedChatId: Long?,
    selfPeerId: PeerId?,
    selectingRecipient: Boolean,
    recipientIds: Set<Long>,
    recipientSelectionEnabled: Boolean,
    canSelectRecipient: (Chat) -> Boolean,
    onToggleRecipient: (Long) -> Unit,
    showAvatar: Boolean,
    showReadStatus: Boolean,
    openAvatarsInProfile: Boolean,
    texts: ChatListTexts,
    mediaRepository: MediaRepository?,
    onOpenChat: (PeerId) -> Unit,
    onOpenAvatar: (PeerId) -> Unit,
    onOpenMessage: (Message) -> Unit,
    onRetrySearch: () -> Unit,
) {
    if (query.isBlank()) return
    if (people.isNotEmpty()) {
        item(key = "search-people-header", contentType = "search-header") {
            SearchSectionHeader(peopleLabel, showAvatar)
        }
        items(
            items = people,
            key = { "search-people-${it.id.value}" },
            contentType = { "search-peer" },
        ) { peer ->
            val chat = peer.toChat()
            SearchResultRow(
                chat = chat,
                selected = chat.id.value == selectedChatId,
                selectingRecipient = selectingRecipient,
                recipientIds = recipientIds,
                recipientSelectionEnabled = recipientSelectionEnabled,
                canSelectRecipient = canSelectRecipient,
                onToggleRecipient = onToggleRecipient,
                savedMessages = chat.id == selfPeerId,
                mediaRepository = mediaRepository,
                showAvatar = showAvatar,
                showReadStatus = false,
                openAvatarsInProfile = openAvatarsInProfile,
                texts = texts,
                onOpenChat = onOpenChat,
                onOpenAvatar = onOpenAvatar,
            )
        }
    }
    if (foundChats.isNotEmpty()) {
        item(key = "search-chats-header", contentType = "search-header") {
            SearchSectionHeader(chatsLabel, showAvatar)
        }
        items(
            items = foundChats,
            key = { "search-chats-${it.id.value}" },
            contentType = { "search-peer" },
        ) { peer ->
            val chat = peer.toChat()
            SearchResultRow(
                chat = chat,
                selected = chat.id.value == selectedChatId,
                selectingRecipient = selectingRecipient,
                recipientIds = recipientIds,
                recipientSelectionEnabled = recipientSelectionEnabled,
                canSelectRecipient = canSelectRecipient,
                onToggleRecipient = onToggleRecipient,
                savedMessages = chat.id == selfPeerId,
                mediaRepository = mediaRepository,
                showAvatar = showAvatar,
                showReadStatus = false,
                openAvatarsInProfile = openAvatarsInProfile,
                texts = texts,
                onOpenChat = onOpenChat,
                onOpenAvatar = onOpenAvatar,
            )
        }
    }
    if (messages.isNotEmpty()) {
        item(key = "search-messages-header", contentType = "search-header") {
            SearchSectionHeader(messagesLabel, showAvatar)
        }
        items(
            items = messages,
            key = { "search-msg-${it.id.chatId.value}-${it.id.id}" },
            contentType = { "search-message" },
        ) { message ->
            val title = titleForSearchMessage(message, listedChats, people, foundChats)
            val chat = message.toSearchChat(title)
            SearchResultRow(
                chat = chat,
                selected = false,
                selectingRecipient = selectingRecipient,
                recipientIds = recipientIds,
                recipientSelectionEnabled = recipientSelectionEnabled,
                canSelectRecipient = canSelectRecipient,
                onToggleRecipient = onToggleRecipient,
                savedMessages = chat.id == selfPeerId,
                mediaRepository = mediaRepository,
                showAvatar = showAvatar,
                showReadStatus = showReadStatus,
                openAvatarsInProfile = openAvatarsInProfile,
                texts = texts,
                onOpenChat = { onOpenMessage(message) },
                onOpenAvatar = onOpenAvatar,
            )
        }
    }
    if (searchLoading && localCount == 0 && people.isEmpty() && foundChats.isEmpty() && messages.isEmpty()) {
        item(key = "search-loading", contentType = "search-status") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
    if (searchLoadingMore) {
        item(key = "search-loading-more", contentType = "search-status") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
    if (searchError != null && people.isEmpty() && foundChats.isEmpty() && messages.isEmpty() && localCount == 0) {
        item(key = "search-error", contentType = "search-status") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = searchError.toUiMessage(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onRetrySearch) {
                    Text(retryLabel)
                }
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(text: String, showAvatar: Boolean) {
    val titleStart = ChatRowMetrics.SideInset + ChatRowMetrics.ContentPaddingH +
        if (showAvatar) ChatRowMetrics.AvatarSize + ChatRowMetrics.AvatarGap else 0.dp
    SectionHeader(
        text = text,
        modifier = Modifier.padding(start = titleStart - 4.dp),
    )
}

@Composable
private fun SearchResultRow(
    chat: Chat,
    selected: Boolean,
    selectingRecipient: Boolean,
    recipientIds: Set<Long>,
    recipientSelectionEnabled: Boolean,
    canSelectRecipient: (Chat) -> Boolean,
    onToggleRecipient: (Long) -> Unit,
    savedMessages: Boolean,
    mediaRepository: MediaRepository?,
    showAvatar: Boolean,
    showReadStatus: Boolean,
    openAvatarsInProfile: Boolean,
    texts: ChatListTexts,
    onOpenChat: (PeerId) -> Unit,
    onOpenAvatar: (PeerId) -> Unit,
) {
    ChatListItem(
        chat = chat,
        selected = selected,
        selectingRecipient = selectingRecipient,
        recipientSelected = chat.id.value in recipientIds,
        recipientSelectionEnabled = recipientSelectionEnabled,
        canSelectRecipient = canSelectRecipient(chat),
        onToggleRecipient = onToggleRecipient,
        savedMessages = savedMessages,
        mediaRepository = mediaRepository,
        showAvatar = showAvatar,
        showReadStatus = showReadStatus,
        openAvatarsInProfile = openAvatarsInProfile,
        texts = texts,
        rowMenuOpen = false,
        onOpenChat = onOpenChat,
        onOpenAvatar = onOpenAvatar,
        onRowMenu = {},
        onDismissRowMenu = {},
        onMarkRead = {},
        onMarkUnread = {},
        markReadLabel = "",
        markUnreadLabel = "",
    )
}

internal fun globalSearchLeadingCount(
    people: List<SearchPeer>,
    foundChats: List<SearchPeer>,
    messages: List<Message>,
    searchLoading: Boolean,
    searchLoadingMore: Boolean,
    searchError: TelegramError?,
    localCount: Int,
): Int {
    var count = 0
    if (people.isNotEmpty()) count += 1 + people.size
    if (foundChats.isNotEmpty()) count += 1 + foundChats.size
    if (messages.isNotEmpty()) count += 1 + messages.size
    if (searchLoading && localCount == 0 && people.isEmpty() && foundChats.isEmpty() && messages.isEmpty()) {
        count += 1
    }
    if (searchLoadingMore) count += 1
    if (searchError != null && people.isEmpty() && foundChats.isEmpty() && messages.isEmpty() && localCount == 0) {
        count += 1
    }
    return count
}
