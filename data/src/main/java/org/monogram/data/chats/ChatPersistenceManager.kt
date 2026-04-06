package org.monogram.data.chats

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.data.datasource.cache.ChatLocalDataSource
import org.monogram.data.db.model.ChatEntity
import org.monogram.data.mapper.ChatMapper
import org.monogram.domain.models.ChatModel
import java.util.concurrent.ConcurrentHashMap

class ChatPersistenceManager(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val cache: ChatCache,
    private val chatLocalDataSource: ChatLocalDataSource,
    private val chatMapper: ChatMapper,
    private val modelFactory: ChatModelFactory,
    private val listManager: ChatListManager,
    private val activeChatListProvider: () -> TdApi.ChatList
) {
    private val lastSavedEntities = ConcurrentHashMap<Long, ChatEntity>()
    private val pendingSaveJobs = ConcurrentHashMap<Long, Job>()
    private val mainChatList = TdApi.ChatListMain()

    fun rememberSavedEntity(entity: ChatEntity) {
        lastSavedEntities[entity.id] = entity
    }

    fun scheduleChatSave(chatId: Long) {
        val chat = cache.getChat(chatId) ?: return

        pendingSaveJobs[chatId]?.cancel()
        pendingSaveJobs[chatId] = scope.launch(dispatchers.io) {
            try {
                delay(SINGLE_CHAT_SAVE_DEBOUNCE_MS)
                val activeChatList = activeChatListProvider()
                val position = resolvePersistPosition(chat, activeChatList)
                val model = modelFactory.mapChatToModel(
                    chat = chat,
                    order = position?.order ?: 0L,
                    isPinned = position?.isPinned ?: false,
                    allowMediaDownloads = false
                )
                var entity = chatMapper.mapToEntity(chat, model)
                if (position != null && (position.order != entity.order || position.isPinned != entity.isPinned)) {
                    entity = entity.copy(order = position.order, isPinned = position.isPinned)
                }

                val last = lastSavedEntities[chatId]
                if (last == null || isEntityChanged(last, entity)) {
                    chatLocalDataSource.insertChat(entity)
                    lastSavedEntities[chatId] = entity
                }
            } finally {
                pendingSaveJobs.remove(chatId)
            }
        }
    }

    fun scheduleSavesBySupergroupId(supergroupId: Long) {
        cache.allChats.values
            .asSequence()
            .filter { (it.type as? TdApi.ChatTypeSupergroup)?.supergroupId == supergroupId }
            .map { it.id }
            .forEach { scheduleChatSave(it) }
    }

    fun scheduleSavesByBasicGroupId(basicGroupId: Long) {
        cache.allChats.values
            .asSequence()
            .filter { (it.type as? TdApi.ChatTypeBasicGroup)?.basicGroupId == basicGroupId }
            .map { it.id }
            .forEach { scheduleChatSave(it) }
    }

    suspend fun persistChatModels(models: List<ChatModel>, activeChatList: TdApi.ChatList) {
        val toSave = models
            .map { model -> mapModelToEntity(model, activeChatList) }
            .filter { entity ->
                val last = lastSavedEntities[entity.id]
                if (last == null || isEntityChanged(last, entity)) {
                    lastSavedEntities[entity.id] = entity
                    true
                } else {
                    false
                }
            }

        if (toSave.isNotEmpty()) {
            chatLocalDataSource.insertChats(toSave)
        }
    }

    fun clear() {
        pendingSaveJobs.values.forEach { it.cancel() }
        pendingSaveJobs.clear()
        lastSavedEntities.clear()
    }

    private fun mapModelToEntity(model: ChatModel, activeChatList: TdApi.ChatList): ChatEntity {
        val chat = cache.getChat(model.id)
        if (chat == null) {
            return chatMapper.mapToEntity(model)
        }

        val persistPosition = resolvePersistPosition(chat, activeChatList)
        val mapped = chatMapper.mapToEntity(chat, model)
        return if (persistPosition != null &&
            (persistPosition.order != mapped.order || persistPosition.isPinned != mapped.isPinned)
        ) {
            mapped.copy(order = persistPosition.order, isPinned = persistPosition.isPinned)
        } else {
            mapped
        }
    }

    private fun resolvePersistPosition(chat: TdApi.Chat, activeChatList: TdApi.ChatList): TdApi.ChatPosition? {
        return chat.positions.find { pos ->
            pos.order != 0L && listManager.isSameChatList(pos.list, mainChatList)
        }
            ?: chat.positions.find { pos ->
                pos.order != 0L && listManager.isSameChatList(pos.list, activeChatList)
            }
            ?: chat.positions.firstOrNull { it.order != 0L }
    }

    private fun isEntityChanged(old: ChatEntity, new: ChatEntity): Boolean {
        return old.withoutCreatedAt() != new.withoutCreatedAt()
    }

    private fun ChatEntity.withoutCreatedAt(): ChatEntity = copy(createdAt = 0L)

    companion object {
        private const val SINGLE_CHAT_SAVE_DEBOUNCE_MS = 2000L
    }
}