package org.monogram.presentation.features.chats.currentChat.chatContent

import androidx.compose.runtime.Immutable
import org.monogram.domain.models.MessageModel
import java.text.SimpleDateFormat
import java.util.*

@Immutable
sealed class GroupedMessageItem {
    @Immutable
    data class Single(val message: MessageModel) : GroupedMessageItem()

    @Immutable
    data class Album(val albumId: Long, val messages: List<MessageModel>) : GroupedMessageItem()

    val firstMessageId: Long
        get() = when (this) {
            is Single -> message.id
            is Album -> messages.first().id
        }
}

fun groupMessagesByAlbum(messages: List<MessageModel>): List<GroupedMessageItem> {
    if (messages.isEmpty()) return emptyList()

    val result = mutableListOf<GroupedMessageItem>()
    var currentAlbumId: Long = 0L
    val currentAlbumMessages = mutableListOf<MessageModel>()
    fun flushCurrentAlbum() {
        if (currentAlbumMessages.isEmpty()) return

        if (currentAlbumMessages.size == 1) {
            result.add(GroupedMessageItem.Single(currentAlbumMessages.first()))
        } else {
            result.add(GroupedMessageItem.Album(currentAlbumId, currentAlbumMessages.toList()))
        }
        currentAlbumMessages.clear()
        currentAlbumId = 0L
    }

    for (msg in messages) {
        if (msg.mediaAlbumId != 0L) {
            if (currentAlbumId == msg.mediaAlbumId) {
                currentAlbumMessages.add(msg)
            } else {
                flushCurrentAlbum()
                currentAlbumId = msg.mediaAlbumId
                currentAlbumMessages.add(msg)
            }
        } else {
            flushCurrentAlbum()
            result.add(GroupedMessageItem.Single(msg))
        }
    }
    flushCurrentAlbum()
    return result
}

fun shouldShowDate(current: MessageModel, older: MessageModel?): Boolean {
    val currentTimestamp = System.currentTimeMillis()
    val msgTimestamp = current.date.toLong() * 1000
    val fmt = SimpleDateFormat("yyyyDDD", Locale.US)

    if (fmt.format(Date(currentTimestamp)) == fmt.format(Date(msgTimestamp))) {
        return false
    }

    if (older == null) return true
    return !fmt.format(Date(msgTimestamp)).equals(fmt.format(Date(older.date.toLong() * 1000)))
}
