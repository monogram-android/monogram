package org.monogram.feature.chats.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.monogram.core.models.Chat

@Stable
internal class ChatListSnapshot {
    var ids by mutableStateOf(emptyList<Long>())
        private set
    var unmutedUnread by mutableIntStateOf(0)
        private set
    private val rows = mutableStateMapOf<Long, Chat>()

    val size: Int get() = ids.size
    val isEmpty: Boolean get() = ids.isEmpty()

    fun chat(id: Long): Chat? = rows[id]

    fun items(): List<Chat> {
        if (ids.isEmpty()) return emptyList()
        return ids.mapNotNull(rows::get)
    }

    fun replace(chats: List<Chat>) {
        val nextIds = if (chats.isEmpty()) emptyList() else chats.map { it.id.value }
        if (rows.isNotEmpty()) {
            val keep = if (nextIds.isEmpty()) emptySet() else nextIds.toHashSet()
            val stale = rows.keys.filter { it !in keep }
            stale.forEach { rows.remove(it) }
        }
        for (chat in chats) {
            val id = chat.id.value
            if (rows[id] != chat) rows[id] = chat
        }
        if (ids != nextIds) ids = nextIds
        val nextUnread = chats.count { it.unreadCount > 0 && !it.muted }
        if (unmutedUnread != nextUnread) unmutedUnread = nextUnread
    }
}
