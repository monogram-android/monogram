package org.monogram.presentation.features.chats

import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.*
import org.monogram.domain.repository.ConnectionStatus
import org.monogram.presentation.core.util.AppPreferences

interface ChatListComponent {
    val state: StateFlow<State>
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

    data class State(
        val chatsByFolder: Map<Int, List<ChatModel>> = emptyMap(),
        val folders: List<FolderModel> = emptyList(),
        val selectedFolderId: Int = -1,
        val currentUser: UserModel? = null,
        val isLoadingByFolder: Map<Int, Boolean> = emptyMap(),
        val selectedChatIds: Set<Long> = emptySet(),
        val isSearchActive: Boolean = false,
        val searchQuery: String = "",
        val searchResults: List<ChatModel> = emptyList(),
        val globalSearchResults: List<ChatModel> = emptyList(),
        val messageSearchResults: List<MessageModel> = emptyList(),
        val searchHistory: List<ChatModel> = emptyList(),
        val connectionStatus: ConnectionStatus = ConnectionStatus.Connected,
        val isArchivePinned: Boolean = true,
        val isArchiveAlwaysVisible: Boolean = false,
        val isForwarding: Boolean = false,
        val canLoadMoreMessages: Boolean = false,
        val instantViewUrl: String? = null,
        val activeChatId: Long? = null,
        val isProxyEnabled: Boolean = false,
        val attachMenuBots: List<AttachMenuBotModel> = emptyList(),
        val botWebAppUrl: String? = null,
        val botWebAppName: String? = null,
        val webAppUrl: String? = null,
        val webAppBotId: Long? = null,
        val webAppBotName: String? = null,
        val webViewUrl: String? = null,
        val updateState: UpdateState = UpdateState.Idle,
        val scrollPositions: Map<Int, Pair<Int, Int>> = emptyMap()
    ) {
        val chats: List<ChatModel> get() = chatsByFolder[selectedFolderId] ?: emptyList()
        val isLoading: Boolean get() = isLoadingByFolder[selectedFolderId] ?: false
    }
}
