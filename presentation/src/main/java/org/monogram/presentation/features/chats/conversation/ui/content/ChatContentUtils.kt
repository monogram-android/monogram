package org.monogram.presentation.features.chats.conversation.ui.content

import androidx.compose.runtime.Immutable
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.SponsoredMessageModel
import org.monogram.domain.models.SponsoredMessagesFeedModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    val lastMessageId: Long
        get() = when (this) {
            is Single -> message.id
            is Album -> messages.last().id
        }

    val lazyItemKey: String
        get() = when (this) {
            is Single -> "msg_${message.id}"
            is Album -> "album_${albumId}_${firstMessageId}_${lastMessageId}"
        }
}

@Immutable
sealed interface ConversationListItem {
    val lazyItemKey: String

    @Immutable
    data class Grouped(
        val groupedIndex: Int,
        val groupedMessageItem: GroupedMessageItem
    ) : ConversationListItem {
        override val lazyItemKey: String
            get() = groupedMessageItem.lazyItemKey
    }

    @Immutable
    data class Sponsored(
        val sponsoredIndex: Int,
        val sponsoredMessage: SponsoredMessageModel
    ) : ConversationListItem {
        override val lazyItemKey: String
            get() = "channel_sponsored_message_${sponsoredMessage.messageId}"
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

fun findFirstUnreadBoundary(
    messages: List<MessageModel>,
    groupedItems: List<GroupedMessageItem>,
    lastReadInboxMessageId: Long?
): GroupedMessageItem? {
    if (lastReadInboxMessageId == null) return null

    val messageIdToGroupMap = mutableMapOf<Long, GroupedMessageItem>()
    for (item in groupedItems) {
        when (item) {
            is GroupedMessageItem.Single -> messageIdToGroupMap[item.message.id] = item
            is GroupedMessageItem.Album -> item.messages.forEach { messageIdToGroupMap[it.id] = item }
        }
    }

    val firstUnreadMessage = messages
        .asSequence()
        .filter { !it.isOutgoing && it.id > lastReadInboxMessageId }
        .minByOrNull(MessageModel::id)
        ?: return null

    return messageIdToGroupMap[firstUnreadMessage.id]
}

fun shouldShowDate(current: MessageModel, older: MessageModel?): Boolean {
    val msgTimestamp = current.date.toLong() * 1000
    val fmt = SimpleDateFormat("yyyyDDD", Locale.US)

    if (older == null) return true
    return !fmt.format(Date(msgTimestamp)).equals(fmt.format(Date(older.date.toLong() * 1000)))
}

internal fun buildConversationListItems(
    groupedMessages: List<GroupedMessageItem>,
    sponsoredFeed: SponsoredMessagesFeedModel?
): List<ConversationListItem> {
    val sponsoredMessages = sponsoredFeed?.messages.orEmpty()
    if (groupedMessages.isEmpty() && sponsoredMessages.isEmpty()) return emptyList()
    if (sponsoredMessages.isEmpty()) {
        return groupedMessages.mapIndexed { index, item ->
            ConversationListItem.Grouped(
                groupedIndex = index,
                groupedMessageItem = item
            )
        }
    }

    val messagesBetween = sponsoredFeed?.messagesBetween ?: 0
    if (messagesBetween <= 0) {
        return buildList {
            sponsoredMessages.forEachIndexed { sponsoredIndex, sponsoredMessage ->
                add(
                    ConversationListItem.Sponsored(
                        sponsoredIndex = sponsoredIndex,
                        sponsoredMessage = sponsoredMessage
                    )
                )
            }
            groupedMessages.forEachIndexed { groupedIndex, item ->
                add(
                    ConversationListItem.Grouped(
                        groupedIndex = groupedIndex,
                        groupedMessageItem = item
                    )
                )
            }
        }
    }

    return buildList {
        add(
            ConversationListItem.Sponsored(
                sponsoredIndex = 0,
                sponsoredMessage = sponsoredMessages.first()
            )
        )

        var nextSponsoredIndex = 1
        var messagesSinceLastSponsored = 0

        groupedMessages.forEachIndexed { groupedIndex, item ->
            add(
                ConversationListItem.Grouped(
                    groupedIndex = groupedIndex,
                    groupedMessageItem = item
                )
            )
            messagesSinceLastSponsored += item.messageCount()

            if (
                nextSponsoredIndex < sponsoredMessages.size &&
                messagesSinceLastSponsored >= messagesBetween
            ) {
                add(
                    ConversationListItem.Sponsored(
                        sponsoredIndex = nextSponsoredIndex,
                        sponsoredMessage = sponsoredMessages[nextSponsoredIndex]
                    )
                )
                nextSponsoredIndex += 1
                messagesSinceLastSponsored = 0
            }
        }
    }
}

internal fun buildGroupedLazyIndexByFirstMessageId(
    conversationItems: List<ConversationListItem>,
    leadingItemsCount: Int
): Map<Long, Int> {
    return buildMap {
        conversationItems.forEachIndexed { conversationIndex, item ->
            if (item is ConversationListItem.Grouped) {
                put(
                    item.groupedMessageItem.firstMessageId,
                    conversationIndex + leadingItemsCount
                )
            }
        }
    }
}

internal fun GroupedMessageItem.messageCount(): Int {
    return when (this) {
        is GroupedMessageItem.Single -> 1
        is GroupedMessageItem.Album -> messages.size
    }
}

