package org.monogram.data.chats

import android.util.Log
import org.drinkless.tdlib.TdApi

class ChatListManager(
    private val cache: ChatCache,
    private val onChatNeeded: (Long) -> Unit
) {
    private val tag = "ChatListDiag"

    fun rebuildChatList(
        limit: Int = Int.MAX_VALUE,
        excludedChatIds: List<Long> = emptyList(),
        mapChat: (TdApi.Chat, Long, Boolean) -> org.monogram.domain.models.ChatModel?
    ): List<org.monogram.domain.models.ChatModel> {
        val excludedSet = if (excludedChatIds.isEmpty()) emptySet() else excludedChatIds.toHashSet()
        val pinnedEntries = ArrayList<Pair<Long, TdApi.ChatPosition>>()
        val otherEntries = ArrayList<Pair<Long, TdApi.ChatPosition>>()

        cache.activeListPositions.forEach { (chatId, position) ->
            if (chatId in excludedSet) return@forEach
            val entry = chatId to position
            if (position.isPinned) {
                pinnedEntries.add(entry)
            } else {
                otherEntries.add(entry)
            }
        }

        pinnedEntries.sortWith(
            compareByDescending<Pair<Long, TdApi.ChatPosition>> { it.second.order }
                .thenByDescending { it.first }
        )
        val otherLastMessageDates = HashMap<Long, Long>(otherEntries.size)
        otherEntries.forEach { (chatId, _) ->
            otherLastMessageDates[chatId] = cache.allChats[chatId]?.lastMessage?.date?.toLong() ?: 0L
        }
        otherEntries.sortWith(
            compareByDescending<Pair<Long, TdApi.ChatPosition>> { (chatId, _) ->
                otherLastMessageDates[chatId] ?: 0L
            }
                .thenByDescending { it.second.order }
                .thenByDescending { it.first }
        )

        fun mapEntry(chatId: Long, position: TdApi.ChatPosition): org.monogram.domain.models.ChatModel? {
            val chat = cache.allChats[chatId]
            if (chat == null) {
                Log.w(
                    tag,
                    "rebuild missing chat chatId=$chatId order=${position.order} pinned=${position.isPinned} active=${cache.activeListPositions.size} chats=${cache.allChats.size}"
                )
                if (position.order != 0L) onChatNeeded(chatId)
                return null
            }
            return mapChat(chat, position.order, position.isPinned)
        }

        val result = ArrayList<org.monogram.domain.models.ChatModel>()
        var pinnedMapped = 0
        val missingPinnedIds = ArrayList<Long>()
        pinnedEntries.forEach { (chatId, position) ->
            val mapped = mapEntry(chatId, position)
            if (mapped != null) {
                result.add(mapped)
                pinnedMapped += 1
            } else {
                missingPinnedIds.add(chatId)
            }
        }

        if (missingPinnedIds.isNotEmpty()) {
            Log.w(
                tag,
                "rebuild pinned missing: total=${pinnedEntries.size} mapped=$pinnedMapped missing=${missingPinnedIds.size} missingIds=${
                    missingPinnedIds.take(
                        10
                    )
                } active=${cache.activeListPositions.size} chats=${cache.allChats.size}"
            )
        }

        val othersLimit = (limit - result.size).coerceAtLeast(0)
        if (othersLimit == 0) return result

        var loadedOthers = 0
        for ((chatId, position) in otherEntries) {
            val model = mapEntry(chatId, position) ?: continue
            result.add(model)
            loadedOthers += 1
            if (loadedOthers >= othersLimit) break
        }

        return result
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
            val currentPos = cache.activeListPositions[chatId]
            if (newPosition.order == 0L) {
                val shouldProtectPinned = currentPos?.isPinned == true && cache.protectedPinnedChatIds.contains(chatId)
                if (currentPos?.isPinned == true || cache.protectedPinnedChatIds.contains(chatId)) {
                    Log.w(
                        tag,
                        "updatePosition order=0 for pinned-like chatId=$chatId currentPinned=${currentPos?.isPinned} protected=${
                            cache.protectedPinnedChatIds.contains(
                                chatId
                            )
                        } authoritative=${cache.authoritativeActiveListChatIds.contains(chatId)}"
                    )
                }
                if (!shouldProtectPinned) {
                    val removed = cache.activeListPositions.remove(chatId) != null
                    if (removed) {
                        Log.w(
                            tag,
                            "position removed updateChatPosition chatId=$chatId reason=order0 currentPinned=${currentPos?.isPinned} protected=${
                                cache.protectedPinnedChatIds.contains(
                                    chatId
                                )
                            } authoritative=${cache.authoritativeActiveListChatIds.contains(chatId)} active=${cache.activeListPositions.size}"
                        )
                        activeListChanged = true
                    }
                    cache.authoritativeActiveListChatIds.remove(chatId)
                    cache.protectedPinnedChatIds.remove(chatId)
                }
            } else {
                val oldPos = cache.activeListPositions.put(chatId, newPosition)
                cache.authoritativeActiveListChatIds.add(chatId)
                if (newPosition.isPinned) {
                    Log.d(
                        tag,
                        "updatePosition pinned set chatId=$chatId order=${newPosition.order} oldPinned=${oldPos?.isPinned}"
                    )
                    cache.protectedPinnedChatIds.add(chatId)
                } else {
                    cache.protectedPinnedChatIds.remove(chatId)
                }
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
            if (newPos.isPinned) {
                cache.protectedPinnedChatIds.add(chatId)
            } else {
                cache.protectedPinnedChatIds.remove(chatId)
            }
            oldPos == null || oldPos.order != newPos.order || oldPos.isPinned != newPos.isPinned
        } else {
            false
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
