package org.monogram.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.*

data class SearchMessagesResult(
    val messages: List<MessageModel>,
    val nextOffset: String
)

interface ChatsListRepository {
    val chatListFlow: StateFlow<List<ChatModel>>
    val foldersFlow: StateFlow<List<FolderModel>>
    val isLoadingFlow: StateFlow<Boolean>
    val connectionStateFlow: StateFlow<ConnectionStatus>
    val isArchivePinned: StateFlow<Boolean>
    val isArchiveAlwaysVisible: StateFlow<Boolean>
    val searchHistory: Flow<List<ChatModel>>
    val forumTopicsFlow: Flow<Pair<Long, List<TopicModel>>>

    fun loadNextChunk(limit: Int)
    fun selectFolder(folderId: Int)
    fun refresh()
    suspend fun getChatById(chatId: Long): ChatModel?

    suspend fun searchChats(query: String): List<ChatModel>
    suspend fun searchPublicChats(query: String): List<ChatModel>
    suspend fun searchMessages(query: String, offset: String = "", limit: Int = 50): SearchMessagesResult
    fun toggleMuteChats(chatIds: Set<Long>, mute: Boolean)
    fun toggleArchiveChats(chatIds: Set<Long>, archive: Boolean)
    fun deleteChats(chatIds: Set<Long>)
    fun leaveChat(chatId: Long)
    fun setArchivePinned(pinned: Boolean)

    fun retryConnection()

    suspend fun createFolder(title: String, iconName: String?, includedChatIds: List<Long>)
    suspend fun deleteFolder(folderId: Int)
    suspend fun updateFolder(folderId: Int, title: String, iconName: String?, includedChatIds: List<Long>)
    suspend fun reorderFolders(folderIds: List<Int>)

    suspend fun getForumTopics(
        chatId: Long,
        query: String = "",
        offsetDate: Int = 0,
        offsetMessageId: Long = 0,
        offsetForumTopicId: Int = 0,
        limit: Int = 20
    ): List<TopicModel>

    fun clearChatHistory(chatId: Long, revoke: Boolean)
    suspend fun getChatLink(chatId: Long): String?
    fun reportChat(chatId: Long, reason: String, messageIds: List<Long> = emptyList())

    fun addSearchChatId(chatId: Long)
    fun removeSearchChatId(chatId: Long)
    fun clearSearchHistory()

    suspend fun createGroup(title: String, userIds: List<Long>, messageAutoDeleteTime: Int = 0): Long
    suspend fun createChannel(
        title: String,
        description: String,
        isMegagroup: Boolean = false,
        messageAutoDeleteTime: Int = 0
    ): Long

    suspend fun setChatPhoto(chatId: Long, photoPath: String)
    suspend fun setChatTitle(chatId: Long, title: String)
    suspend fun setChatDescription(chatId: Long, description: String)
    suspend fun setChatUsername(chatId: Long, username: String)
    suspend fun setChatPermissions(chatId: Long, permissions: ChatPermissionsModel)
    suspend fun setChatSlowModeDelay(chatId: Long, slowModeDelay: Int)
    suspend fun toggleChatIsForum(chatId: Long, isForum: Boolean)
    suspend fun toggleChatIsTranslatable(chatId: Long, isTranslatable: Boolean)

    fun getDatabaseSize(): Long
    fun clearDatabase()
}

sealed class ConnectionStatus {
    data object Connected : ConnectionStatus()
    data object Connecting : ConnectionStatus()
    data object Updating : ConnectionStatus()
    data object WaitingForNetwork : ConnectionStatus()
    data object ConnectingToProxy : ConnectionStatus()
}
