package org.monogram.feature.chats.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.SearchPeer
import org.monogram.core.ui.components.FolderChipItem
import org.monogram.core.ui.components.FolderChipRow
import org.monogram.core.ui.components.FolderChips
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.core.ui.media.showsMiniPlayer
import org.monogram.core.ui.menu.AppMenuPopup
import org.monogram.core.ui.perf.RecompositionProbe
import org.monogram.feature.chats.recipientPaneIds
import org.monogram.feature.chats.shouldPageChats
import org.monogram.network.http.MediaRepository

@Composable
internal fun ChatsLazyList(
    chats: ChatListSnapshot,
    listState: LazyListState,
    archive: Boolean,
    searchOpen: Boolean,
    query: String,
    homeFolderId: Int?,
    chips: FolderChips,
    allChats: ChatListSnapshot,
    folders: List<Folder>,
    archivedTitles: String?,
    archivedUnmuted: Int,
    archivedMuted: Int,
    showArchiveRow: Boolean,
    loading: Boolean,
    error: TelegramError?,
    hasMore: Boolean,
    loadingMore: Boolean,
    selectedChatId: Long?,
    selfPeerId: PeerId?,
    showAvatar: Boolean,
    showReadStatus: Boolean,
    openAvatarsInProfile: Boolean,
    texts: ChatListTexts,
    mediaRepository: MediaRepository?,
    folderMenu: FolderChipItem?,
    rowMenuId: Long?,
    onSelectFolder: (Int?) -> Unit,
    onManageFolders: () -> Unit,
    onFolderLongPress: (FolderChipItem) -> Unit,
    onDismissFolderMenu: () -> Unit,
    onMarkFolderRead: (List<PeerId>) -> Unit,
    onEditFolders: () -> Unit,
    onOpenArchive: () -> Unit,
    onOpenChat: (PeerId) -> Unit,
    onOpenAvatar: (PeerId) -> Unit,
    onRowMenu: (Chat) -> Unit,
    onDismissRowMenu: () -> Unit,
    onMarkRead: (PeerId) -> Unit,
    onMarkUnread: (PeerId) -> Unit,
    onClearSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    markReadLabel: String,
    markUnreadLabel: String,
    manageFoldersLabel: String,
    foldersAtBottom: Boolean = false,
    inlineFolderChips: Boolean = true,
    selectingRecipient: Boolean = false,
    recipientIds: Set<Long> = emptySet(),
    recipientSelectionEnabled: Boolean = true,
    canSelectRecipient: (Chat) -> Boolean = { true },
    onToggleRecipient: (Long) -> Unit = {},
    searchPeople: List<SearchPeer> = emptyList(),
    searchChats: List<SearchPeer> = emptyList(),
    searchMessages: List<Message> = emptyList(),
    searchLoading: Boolean = false,
    searchLoadingMore: Boolean = false,
    searchHasMore: Boolean = false,
    searchError: TelegramError? = null,
    searchPeopleLabel: String = "",
    searchChatsLabel: String = "",
    searchMessagesLabel: String = "",
    searchRetryLabel: String = "",
    listedChats: List<Chat> = emptyList(),
    onOpenSearchMessage: (Message) -> Unit = {},
    onRetrySearch: () -> Unit = {},
) {
    RecompositionProbe("ChatsLazyList")
    val context = LocalContext.current
    val miniPlayerPad = if (MediaPlaybackHolder.session(context).showsMiniPlayer()) 72.dp else 0.dp
    val paneIds = recipientPaneIds(
        paneIds = chats.ids,
        chat = chats::chat,
        selectingRecipient = selectingRecipient,
        canSelectRecipient = canSelectRecipient,
    )
    val searching = query.isNotBlank()
    val searchExtra = if (searching) {
        globalSearchLeadingCount(
            people = searchPeople,
            foundChats = searchChats,
            messages = searchMessages,
            searchLoading = searchLoading,
            searchLoadingMore = searchLoadingMore,
            searchError = searchError,
            localCount = paneIds.size,
        )
    } else {
        0
    }
    LaunchedEffect(
        listState,
        paneIds.size,
        searchExtra,
        hasMore,
        loadingMore,
        searching,
        searchHasMore,
        searchLoadingMore,
        archive,
        searchOpen,
        showArchiveRow,
        foldersAtBottom,
        inlineFolderChips,
    ) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        }.collect { last ->
            val showInlineChips = inlineFolderChips && !archive && !searchOpen && !foldersAtBottom
            val leading = (if (showInlineChips) 1 else 0) +
                (if (showArchiveRow) 1 else 0)
            val pageHasMore = if (searching) searchHasMore else hasMore
            val pageLoading = if (searching) searchLoadingMore else loadingMore
            if (
                last != null &&
                shouldPageChats(
                    lastVisibleIndex = last,
                    size = paneIds.size + searchExtra,
                    hasMore = pageHasMore,
                    loadingMore = pageLoading,
                    leadingItems = leading,
                )
            ) {
                onLoadMore()
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            bottom = 8.dp + miniPlayerPad +
                (if (foldersAtBottom && !archive && !searchOpen) 72.dp else 0.dp) +
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
    ) {
        if (inlineFolderChips && !archive && !searchOpen && !foldersAtBottom) {
            item(key = "folder-chips") {
                RecompositionProbe("ChipItemScope")
                Box {
                    FolderChipRow(
                        chips = chips,
                        selectedId = homeFolderId,
                        onSelect = onSelectFolder,
                        onManage = if (selectingRecipient) null else onManageFolders,
                        manageContentDescription = if (selectingRecipient) null else manageFoldersLabel,
                        onLongPress = if (selectingRecipient) null else onFolderLongPress,
                    )
                    if (!selectingRecipient) {
                        AppMenuPopup(
                            expanded = folderMenu != null,
                            onDismiss = onDismissFolderMenu,
                        ) {
                            FolderChipMenu(
                                chats = allChats.items(),
                                folders = folders,
                                folderId = folderMenu?.id,
                                markReadLabel = markReadLabel,
                                editLabel = manageFoldersLabel,
                                onMarkRead = onMarkFolderRead,
                                onEditFolders = onEditFolders,
                            )
                        }
                    }
                }
            }
        }
        if (showArchiveRow) {
            item(key = "archive-row") {
                ArchiveRow(
                    preview = archivedTitles,
                    unmuted = archivedUnmuted,
                    mutedUnread = archivedMuted,
                    onClick = onOpenArchive,
                    modifier = Modifier,
                )
            }
        }
        val searchBusy = searching && (searchLoading || searchLoadingMore)
        val searchHasRows = searchPeople.isNotEmpty() || searchChats.isNotEmpty() ||
            searchMessages.isNotEmpty()
        if (!loading && paneIds.isEmpty() && error == null && !searchBusy && !searchHasRows &&
            (searchError == null || !searching)
        ) {
            item(key = "empty-state") {
                FolderEmptyState(
                    archive = archive,
                    filtered = homeFolderId != null && !archive,
                    query = query,
                    selectingRecipient = selectingRecipient,
                    onShowAll = { onSelectFolder(null) },
                    onClearSearch = onClearSearch,
                )
            }
        }
        items(
            items = paneIds,
            key = { it },
            contentType = { "chat-row" },
        ) { id ->
            val chat = chats.chat(id) ?: return@items
            ChatListItem(
                chat = chat,
                selected = id == selectedChatId,
                selectingRecipient = selectingRecipient,
                recipientSelected = id in recipientIds,
                recipientSelectionEnabled = recipientSelectionEnabled,
                canSelectRecipient = true,
                onToggleRecipient = onToggleRecipient,
                savedMessages = chat.id == selfPeerId,
                mediaRepository = mediaRepository,
                showAvatar = showAvatar,
                showReadStatus = showReadStatus,
                openAvatarsInProfile = openAvatarsInProfile,
                texts = texts,
                rowMenuOpen = rowMenuId == id,
                onOpenChat = onOpenChat,
                onOpenAvatar = onOpenAvatar,
                onRowMenu = onRowMenu,
                onDismissRowMenu = onDismissRowMenu,
                onMarkRead = onMarkRead,
                onMarkUnread = onMarkUnread,
                markReadLabel = markReadLabel,
                markUnreadLabel = markUnreadLabel,
                modifier = Modifier,
            )
        }
        globalSearchSections(
            query = query,
            localCount = paneIds.size,
            people = searchPeople,
            foundChats = searchChats,
            messages = searchMessages,
            listedChats = listedChats.ifEmpty { chats.items() },
            searchLoading = searchLoading,
            searchLoadingMore = searchLoadingMore,
            searchError = searchError,
            retryLabel = searchRetryLabel,
            peopleLabel = searchPeopleLabel,
            chatsLabel = searchChatsLabel,
            messagesLabel = searchMessagesLabel,
            selectedChatId = selectedChatId,
            selfPeerId = selfPeerId,
            selectingRecipient = selectingRecipient,
            recipientIds = recipientIds,
            recipientSelectionEnabled = recipientSelectionEnabled,
            canSelectRecipient = canSelectRecipient,
            onToggleRecipient = onToggleRecipient,
            showAvatar = showAvatar,
            showReadStatus = showReadStatus,
            openAvatarsInProfile = openAvatarsInProfile,
            texts = texts,
            mediaRepository = mediaRepository,
            onOpenChat = onOpenChat,
            onOpenAvatar = onOpenAvatar,
            onOpenMessage = onOpenSearchMessage,
            onRetrySearch = onRetrySearch,
        )
    }
}

@Composable
internal fun ChatListItem(
    chat: Chat,
    selected: Boolean,
    selectingRecipient: Boolean = false,
    recipientSelected: Boolean = false,
    recipientSelectionEnabled: Boolean = true,
    canSelectRecipient: Boolean = true,
    onToggleRecipient: (Long) -> Unit = {},
    savedMessages: Boolean,
    mediaRepository: MediaRepository?,
    showAvatar: Boolean,
    showReadStatus: Boolean,
    openAvatarsInProfile: Boolean,
    texts: ChatListTexts,
    rowMenuOpen: Boolean,
    onOpenChat: (PeerId) -> Unit,
    onOpenAvatar: (PeerId) -> Unit,
    onRowMenu: (Chat) -> Unit,
    onDismissRowMenu: () -> Unit,
    onMarkRead: (PeerId) -> Unit,
    onMarkUnread: (PeerId) -> Unit,
    markReadLabel: String,
    markUnreadLabel: String,
    modifier: Modifier = Modifier,
) {
    RecompositionProbe("ChatListItem", chat.id.value)
    val latestChat = rememberUpdatedState(chat)
    val onOpenThisChat = remember(onOpenChat, chat.id) {
        { onOpenChat(latestChat.value.id) }
    }
    val onOpenThisAvatar = remember(onOpenAvatar, chat.id, openAvatarsInProfile) {
        avatarTapHandler(
            openProfileOnAvatarTap = openAvatarsInProfile,
            onOpenProfile = { onOpenAvatar(latestChat.value.id) },
        )
    }
    val onThisRowMenu = remember(onRowMenu, chat.id) {
        { onRowMenu(latestChat.value) }
    }
    val onToggleThisRecipient = remember(onToggleRecipient, chat.id) {
        { onToggleRecipient(latestChat.value.id.value) }
    }
    val recipientSelectable = recipientSelectionEnabled && canSelectRecipient
    Box {
        ChatRow(
            chat = chat,
            selected = if (selectingRecipient) recipientSelected else selected,
            selectingRecipient = selectingRecipient,
            recipientSelectable = recipientSelectable,
            savedMessages = savedMessages,
            mediaRepository = mediaRepository,
            showAvatar = showAvatar,
            showReadStatus = showReadStatus,
            texts = texts,
            onClick = if (selectingRecipient) onToggleThisRecipient else onOpenThisChat,
            onAvatarClick = if (selectingRecipient) null else onOpenThisAvatar,
            modifier = modifier,
            onLongClick = if (selectingRecipient) null else onThisRowMenu,
        )
        if (!selectingRecipient) {
            AppMenuPopup(
                expanded = rowMenuOpen,
                onDismiss = onDismissRowMenu,
                scrim = true,
            ) {
                ChatRowMenu(
                    unread = chat.unreadCount > 0 || chat.unreadMark,
                    markReadLabel = markReadLabel,
                    markUnreadLabel = markUnreadLabel,
                    onMarkRead = {
                        onDismissRowMenu()
                        onMarkRead(chat.id)
                    },
                    onMarkUnread = {
                        onDismissRowMenu()
                        onMarkUnread(chat.id)
                    },
                )
            }
        }
    }
}
