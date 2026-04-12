package org.monogram.presentation.features.chats

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.*
import org.monogram.domain.repository.ConnectionStatus
import org.monogram.presentation.core.util.AppPreferences

interface ChatListComponent {
    val uiState: StateFlow<UiState>
    val foldersState: StateFlow<FoldersState>
    val chatsState: StateFlow<ChatsState>
    val selectionState: StateFlow<SelectionState>
    val searchState: StateFlow<SearchState>

    val appPreferences: AppPreferences

    fun onChatClicked(id: Long)
    fun onProfileClicked(id: Long)
    fun onMessageClicked(chatId: Long, messageId: Long)
    fun onSettingsClicked()
    fun onFolderClicked(id: Int)
    fun loadMore(folderId: Int? = null)
    fun loadMoreMessages()
    fun onChatLongClicked(id: Long)
    fun clearSelection()
    fun retryConnection()
    fun onSearchToggle()
    fun onSearchQueryChange(query: String)
    fun onSetEmojiStatus(customEmojiId: Long, statusPath: String?)
    fun onClearSearchHistory()
    fun onRemoveSearchHistoryItem(chatId: Long)
    fun onMuteSelected(mute: Boolean)
    fun onArchiveSelected(archive: Boolean)
    fun onPinSelected()
    fun onToggleReadSelected()
    fun onDeleteSelected()
    fun onArchivePinToggle()
    fun onConfirmForwarding()
    fun onNewChatClicked()
    fun onProxySettingsClicked()
    fun onEditFoldersClicked()
    fun onDeleteFolder(folderId: Int)
    fun onEditFolder(folderId: Int)

    fun onOpenInstantView(url: String)
    fun onDismissInstantView()

    fun onOpenWebApp(url: String, botUserId: Long, botName: String)
    fun onDismissWebApp()

    fun onOpenWebView(url: String)
    fun onDismissWebView()

    fun onUpdateClicked()

    fun handleBack(): Boolean

    fun updateScrollPosition(folderId: Int, index: Int, offset: Int)

    @Immutable
    data class UiState(
        val currentUser: UserModel? = null,
        val connectionStatus: ConnectionStatus = ConnectionStatus.Connected,
        val isArchivePinned: Boolean = true,
        val isArchiveAlwaysVisible: Boolean = false,
        val isForwarding: Boolean = false,
        val instantViewUrl: String? = null,
        val isProxyEnabled: Boolean = false,
        val attachMenuBots: List<AttachMenuBotModel> = emptyList(),
        val botWebAppUrl: String? = null,
        val botWebAppName: String? = null,
        val webAppUrl: String? = null,
        val webAppBotId: Long? = null,
        val webAppBotName: String? = null,
        val webViewUrl: String? = null,
        val updateState: UpdateState = UpdateState.Idle
    )

    @Immutable
    data class FoldersState(
        val chatsByFolder: Map<Int, List<ChatModel>> = emptyMap(),
        val folders: List<FolderModel> = emptyList(),
        val selectedFolderId: Int = -1,
        val isLoadingByFolder: Map<Int, Boolean> = emptyMap(),
        val scrollPositions: Map<Int, Pair<Int, Int>> = emptyMap()
    )

    data class ChatsState(
        val chats: List<ChatModel> = emptyList(),
        val isLoading: Boolean = false
    )

    @Immutable
    data class SelectionState(
        val selectedChatIds: Set<Long> = emptySet(),
        val activeChatId: Long? = null
    )

    @Immutable
    data class SearchState(
        val isSearchActive: Boolean = false,
        val searchQuery: String = "",
        val searchResults: List<ChatModel> = emptyList(),
        val globalSearchResults: List<ChatModel> = emptyList(),
        val messageSearchResults: List<MessageModel> = emptyList(),
        val recentUsers: List<ChatModel> = emptyList(),
        val recentOthers: List<ChatModel> = emptyList(),
        val canLoadMoreMessages: Boolean = false
    )
}
