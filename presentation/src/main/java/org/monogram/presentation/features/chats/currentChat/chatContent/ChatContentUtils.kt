package org.monogram.presentation.features.chats.currentChat.chatContent

import org.monogram.domain.models.MessageModel
import java.text.SimpleDateFormat
import java.util.*

sealed class GroupedMessageItem {
    data class Single(val message: MessageModel) : GroupedMessageItem()
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

    for (msg in messages) {
        if (msg.mediaAlbumId != 0L) {
            if (currentAlbumId == msg.mediaAlbumId) {
                currentAlbumMessages.add(msg)
            } else {
                if (currentAlbumMessages.isNotEmpty()) {
                    result.add(GroupedMessageItem.Album(currentAlbumId, currentAlbumMessages.toList()))
                    currentAlbumMessages.clear()
                }
                currentAlbumId = msg.mediaAlbumId
                currentAlbumMessages.add(msg)
            }
        } else {
            if (currentAlbumMessages.isNotEmpty()) {
                result.add(GroupedMessageItem.Album(currentAlbumId, currentAlbumMessages.toList()))
                currentAlbumMessages.clear()
                currentAlbumId = 0L
            }
            result.add(GroupedMessageItem.Single(msg))
        }
    }
    if (currentAlbumMessages.isNotEmpty()) {
        result.add(GroupedMessageItem.Album(currentAlbumId, currentAlbumMessages.toList()))
    }
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
