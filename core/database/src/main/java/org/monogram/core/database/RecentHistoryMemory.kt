package org.monogram.core.database

import org.monogram.core.models.Message

const val RECENT_HISTORY_CHATS = 8
const val RECENT_HISTORY_MESSAGES = 30

/** Process-lifetime recent messages for the chats most likely to be opened. */
class RecentHistoryMemory {
    private val windows = LinkedHashMap<Long, List<Message>>()

    @Synchronized
    fun remember(chatId: Long, messages: List<Message>) {
        if (messages.isEmpty()) return
        windows.remove(chatId)
        windows[chatId] = messages.take(RECENT_HISTORY_MESSAGES)
        while (windows.size > RECENT_HISTORY_CHATS) {
            windows.remove(windows.keys.first())
        }
    }

    @Synchronized
    fun invalidate(chatId: Long) {
        windows.remove(chatId)
    }

    @Synchronized
    fun clear() {
        windows.clear()
    }

    @Synchronized
    fun peek(chatId: Long): List<Message>? = windows[chatId]

    @Synchronized
    fun thumbKeys(chatId: Long): List<String> =
        windows[chatId].orEmpty().mapNotNull { it.thumbCacheKey }.distinct()

    @Synchronized
    fun warmedMessages(): List<Message> = windows.values.flatten()
}
