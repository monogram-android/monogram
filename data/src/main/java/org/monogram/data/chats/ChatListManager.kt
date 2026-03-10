package org.monogram.data.chats

import org.drinkless.tdlib.TdApi

class ChatListManager(
    private val cache: ChatCache,
    private val onChatNeeded: (Long) -> Unit
) {
    fun rebuildChatList(
        limit: Int = Int.MAX_VALUE,
        excludedChatIds: List<Long> = emptyList(),
        mapChat: (TdApi.Chat, Long, Boolean) -> org.monogram.domain.models.ChatModel?
    ): List<org.monogram.domain.models.ChatModel> {
        return cache.activeListPositions.entries
            .filter { it.key !in excludedChatIds }
            .sortedWith(compareByDescending<MutableMap.MutableEntry<Long, TdApi.ChatPosition>> { it.value.isPinned }
                .thenByDescending { it.value.order })
            .take(limit)
            .mapNotNull { (chatId, position) ->
                cache.allChats[chatId]?.let { chat ->
                    mapChat(chat, position.order, position.isPinned)
                }
            }
    }

    fun updateChatPositionInCache(
        chatId: Long,
        newPosition: TdApi.ChatPosition,
        activeChatList: TdApi.ChatList
    ): Boolean {
        val isForActiveList = isSameChatList(newPosition.list, activeChatList)
        val chat = cache.allChats[chatId]
        var activeListChanged = false

        if (chat != null) {
            synchronized(chat) {
                val oldPositions = chat.positions
                val index = oldPositions.indexOfFirst { isSameChatList(it.list, newPosition.list) }

                if (newPosition.order == 0L) {
                    if (index != -1) {
                        chat.positions = oldPositions.filterIndexed { i, _ -> i != index }.toTypedArray()
                    }
                } else {
                    if (index != -1) {
                        val oldPos = oldPositions[index]
                        if (oldPos.order != newPosition.order || oldPos.isPinned != newPosition.isPinned) {
                            val nextPositions = oldPositions.copyOf()
                            nextPositions[index] = newPosition
                            chat.positions = nextPositions
                        }
                    } else {
                        chat.positions = oldPositions + newPosition
                    }
                }
            }
        } else if (newPosition.order != 0L && isForActiveList) {
            onChatNeeded(chatId)
        }

        if (isForActiveList) {
            if (newPosition.order == 0L) {
                if (cache.activeListPositions.remove(chatId) != null) activeListChanged = true
            } else {
                val oldPos = cache.activeListPositions.put(chatId, newPosition)
                if (oldPos == null || oldPos.order != newPosition.order || oldPos.isPinned != newPosition.isPinned) {
                    activeListChanged = true
                }
            }
        }

        return activeListChanged
    }

    fun updateActiveListPositions(
        chatId: Long,
        positions: Array<TdApi.ChatPosition>,
        activeChatList: TdApi.ChatList
    ): Boolean {
        val newPos = positions.find { isSameChatList(it.list, activeChatList) }
        return if (newPos != null && newPos.order != 0L) {
            val oldPos = cache.activeListPositions.put(chatId, newPos)
            oldPos == null || oldPos.order != newPos.order || oldPos.isPinned != newPos.isPinned
        } else {
            cache.activeListPositions.remove(chatId) != null
        }
    }

    fun isSameChatList(a: TdApi.ChatList?, b: TdApi.ChatList?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        if (a.constructor != b.constructor) return false
        if (a is TdApi.ChatListFolder && b is TdApi.ChatListFolder) {
            return a.chatFolderId == b.chatFolderId
        }
        return true
    }
}
