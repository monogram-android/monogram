package org.monogram.data.chats

import android.util.Log
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.drinkless.tdlib.TdApi
import org.monogram.data.db.dao.ChatFolderDao
import org.monogram.data.db.model.ChatFolderEntity
import org.monogram.data.gateway.TelegramGateway
import org.monogram.domain.models.FolderModel
import org.monogram.domain.repository.CacheProvider
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ChatFolderManager"

class ChatFolderManager(
    private val gateway: TelegramGateway,
    private val dispatchers: DispatcherProvider,
    scopeProvider: ScopeProvider,
    private val foldersFlow: MutableStateFlow<List<FolderModel>>,
    private val cacheProvider: CacheProvider,
    private val chatFolderDao: ChatFolderDao
) {
    private val scope = scopeProvider.appScope

    private val chatUnreadCounts = ConcurrentHashMap<Long, Int>()
    private val folderChatIds = ConcurrentHashMap<Int, List<Long>>()
    private val folderPinnedChatIds = ConcurrentHashMap<Int, List<Long>>()

    init {
        val cachedFolders = cacheProvider.chatFolders.value
        if (cachedFolders.isNotEmpty()) {
            foldersFlow.update { cachedFolders }
            cachedFolders.forEach { folder ->
                if (folder.id > 0) {
                    folderChatIds[folder.id] = folder.includedChatIds
                    folderPinnedChatIds[folder.id] = folder.pinnedChatIds
                }
            }
        }

        scope.launch {
            chatFolderDao.getChatFolders().collect { entities ->
                val folders = entities.mapNotNull {
                    try {
                        Json.decodeFromString<FolderModel>(it.data)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (folders.isNotEmpty()) {
                    foldersFlow.update { folders }
                    cacheProvider.setChatFolders(folders)
                    folders.forEach { folder ->
                        if (folder.id > 0) {
                            folderChatIds[folder.id] = folder.includedChatIds
                            folderPinnedChatIds[folder.id] = folder.pinnedChatIds
                        }
                    }
                }
            }
        }
    }

    fun handleChatFoldersUpdate(update: TdApi.UpdateChatFolders) {
        val folderInfos = update.chatFolders
        Log.d(TAG, "handleChatFoldersUpdate: ${folderInfos.size} folders")

        val currentFolders = foldersFlow.value
        val newFolders = listOf(FolderModel(-1, "Все", "All")) +
                folderInfos.map { info ->
                    val existing = currentFolders.find { it.id == info.id }
                    FolderModel(
                        id = info.id,
                        title = info.name.text.text,
                        iconName = info.icon?.name,
                        unreadCount = existing?.unreadCount ?: 0,
                        includedChatIds = existing?.includedChatIds ?: emptyList(),
                        pinnedChatIds = existing?.pinnedChatIds ?: emptyList()
                    )
                }

        foldersFlow.update { newFolders }
        cacheProvider.setChatFolders(newFolders)
        saveFoldersToDb(newFolders)

        folderInfos.forEach { info ->
            scope.launch(dispatchers.io) {
                runCatching {
                    val result = gateway.execute(TdApi.GetChatFolder(info.id))
                    val chatIds = result.includedChatIds.toList()
                    val pinnedIds = result.pinnedChatIds.toList()
                    folderChatIds[info.id] = chatIds
                    folderPinnedChatIds[info.id] = pinnedIds
                    Log.d(TAG, "Folder ${info.id} (${info.name.text.text}) has ${chatIds.size} chats, ${pinnedIds.size} pinned")

                    foldersFlow.update { current ->
                        val updated = current.map { folder ->
                            if (folder.id == info.id) folder.copy(
                                unreadCount = chatIds.sumOf { chatUnreadCounts[it] ?: 0 },
                                includedChatIds = chatIds,
                                pinnedChatIds = pinnedIds
                            ) else folder
                        }
                        cacheProvider.setChatFolders(updated)
                        saveFoldersToDb(updated)
                        updated
                    }
                }.onFailure { e ->
                    Log.e(TAG, "Failed to fetch folder ${info.id}", e)
                }
            }
        }
    }

    private fun saveFoldersToDb(folders: List<FolderModel>) {
        scope.launch(dispatchers.io) {
            val entities = folders.mapIndexed { index, folder ->
                ChatFolderEntity(folder.id, Json.encodeToString(folder), index)
            }
            chatFolderDao.clearAll()
            chatFolderDao.insertChatFolders(entities)
        }
    }

    fun handleUpdateChatUnreadCount(chatId: Long, unreadMentionCount: Int) {
        Log.d(TAG, "handleUpdateChatUnreadCount: chatId=$chatId, count=$unreadMentionCount")
        chatUnreadCounts[chatId] = unreadMentionCount
        folderChatIds.forEach { (folderId, chatIds) ->
            if (chatIds.contains(chatId)) refreshFolderUnreadCount(folderId)
        }
        refreshFolderUnreadCount(-1)
    }

    private fun refreshFolderUnreadCount(folderId: Int) {
        foldersFlow.update { current ->
            current.map { folder ->
                if (folder.id != folderId) return@map folder
                val count = if (folderId == -1) {
                    chatUnreadCounts.values.sum()
                } else {
                    folderChatIds[folderId]?.sumOf { chatUnreadCounts[it] ?: 0 } ?: 0
                }
                Log.d(TAG, "refreshFolderUnreadCount: folderId=$folderId, count=$count")
                folder.copy(unreadCount = count)
            }
        }
    }

    suspend fun createFolder(title: String, iconName: String?, includedChatIds: List<Long>) {
        Log.d(TAG, "createFolder: title=$title, chatsCount=${includedChatIds.size}")
        val folder = TdApi.ChatFolder().apply {
            name = TdApi.ChatFolderName().apply {
                text = TdApi.FormattedText().apply { text = title }
            }
            icon = if (!iconName.isNullOrEmpty()) TdApi.ChatFolderIcon(iconName) else null
            this.includedChatIds = includedChatIds.toLongArray()
            excludedChatIds = LongArray(0)
            pinnedChatIds = LongArray(0)
        }
        gateway.execute(TdApi.CreateChatFolder(folder))
    }

    suspend fun updateFolder(folderId: Int, title: String, iconName: String?, includedChatIds: List<Long>) {
        Log.d(TAG, "updateFolder: folderId=$folderId, title=$title")
        val result = gateway.execute(TdApi.GetChatFolder(folderId))
        result.name = TdApi.ChatFolderName().apply {
            text = TdApi.FormattedText().apply { text = title }
        }
        result.icon = if (!iconName.isNullOrEmpty()) TdApi.ChatFolderIcon(iconName) else null
        result.includedChatIds = includedChatIds.toLongArray()
        gateway.execute(TdApi.EditChatFolder(folderId, result))
    }

    suspend fun reorderFolders(folderIds: List<Int>) {
        Log.d(TAG, "reorderFolders: $folderIds")
        val userFolderIdsOnly = folderIds.filter { it > 0 }
        foldersFlow.update { current ->
            val system = current.filter { it.id < 0 }
            val user = current.filter { it.id > 0 }
            val sorted = userFolderIdsOnly.mapNotNull { id -> user.find { it.id == id } }
            val remaining = user.filter { it.id !in userFolderIdsOnly }
            val newList = system + sorted + remaining
            cacheProvider.setChatFolders(newList)
            saveFoldersToDb(newList)
            newList
        }
        gateway.execute(TdApi.ReorderChatFolders(userFolderIdsOnly.toIntArray(), 0))
    }

    fun getPinnedChatIds(folderId: Int): List<Long> {
        return folderPinnedChatIds[folderId] ?: emptyList()
    }

    fun getIncludedChatIds(folderId: Int): List<Long> {
        return folderChatIds[folderId] ?: emptyList()
    }
}
