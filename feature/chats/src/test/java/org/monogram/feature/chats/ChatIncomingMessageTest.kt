package org.monogram.feature.chats

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.core.database.dao.ChatReadState
import org.monogram.core.models.Chat
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.displayPreview
import org.monogram.feature.chats.ui.showsMentionBadge
import org.monogram.feature.chats.ui.showsReactionBadge

class ChatIncomingMessageTest {
    @Test
    fun staleDialogSnapshotCannotRestoreUnreadBadge() {
        val read = Chat(PeerId(42), "Test", unreadCount = 0, readInboxMaxId = 20)
        val stale = read.copy(title = "Renamed", unreadCount = 5, readInboxMaxId = 10)
        val merged = mergeChats(listOf(read), listOf(stale)).single()
        assertEquals(20, merged.readInboxMaxId)
        assertEquals(0, merged.unreadCount)
        assertEquals("Renamed", merged.title)
        val fresh = stale.copy(readInboxMaxId = 20, unreadCount = 1, lastMessageId = 21)
        assertEquals(1, mergeChats(listOf(merged), listOf(fresh)).single().unreadCount)
    }

    private fun chat(
        id: Long,
        unread: Int = 0,
        date: Long = 1,
        preview: String? = "old",
        readInbox: Int = 0,
        isGroup: Boolean = false,
        pinned: Boolean = false,
        pinnedOrder: Int = Int.MAX_VALUE,
    ) = Chat(
        id = PeerId(id),
        title = "c$id",
        isGroup = isGroup,
        unreadCount = unread,
        lastMessagePreview = preview,
        lastMessageDate = date,
        pinned = pinned,
        pinnedOrder = pinnedOrder,
        readInboxMaxId = readInbox,
    )

    private fun message(
        chatId: Long,
        id: Int,
        text: String?,
        date: Long,
        outgoing: Boolean = false,
        mediaKind: String? = null,
        senderName: String? = "Mike",
    ) = Message(
        id = MessageId(PeerId(chatId), id),
        senderId = PeerId(9),
        text = text,
        date = date,
        outgoing = outgoing,
        mediaKind = mediaKind,
        senderName = senderName,
    )

    @Test
    fun mentionBadgeIsDistinctFromUnreadCount() {
        val mentions = chat(1, unread = 0).copy(unreadMentionsCount = 2)
        val unread = chat(1, unread = 3)
        val marked = chat(1, unread = 0).copy(unreadMark = true)
        assertEquals(true, showsMentionBadge(mentions))
        assertEquals(false, showsMentionBadge(unread))
        assertEquals(false, showsMentionBadge(marked))
        val next = applyUnreadMentions(listOf(mentions), PeerId(1), 0)
        assertEquals(false, showsMentionBadge(next.single()))
        assertEquals(0, next.single().unreadMentionsCount)
    }

    @Test
    fun reactionBadgeIsDistinctFromUnreadCountAndUnreadMark() {
        val reactions = chat(1, unread = 0).copy(unreadReactionsCount = 2)
        val unread = chat(1, unread = 3)
        val marked = chat(1, unread = 0).copy(unreadMark = true)
        assertEquals(true, showsReactionBadge(reactions))
        assertEquals(false, showsReactionBadge(unread))
        assertEquals(false, showsReactionBadge(marked))
    }

    @Test
    fun roomCounterProjectionUpdatesSpecialBadgesWithoutAReadCursorChange() {
        val listed = chat(1, unread = 0, readInbox = 20).copy(
            unreadMentionsCount = 2,
            unreadReactionsCount = 3,
        )
        val next = applyReadStates(
            listOf(listed),
            mapOf(
                1L to ChatReadState(
                    id = 1,
                    readInboxMaxId = 20,
                    unreadCount = 0,
                    unreadMentionsCount = 0,
                    unreadReactionsCount = 0,
                ),
            ),
        ).single()
        assertEquals(20, next.readInboxMaxId)
        assertEquals(0, next.unreadMentionsCount)
        assertEquals(0, next.unreadReactionsCount)
    }

    @Test
    fun syntheticReadStateKeepsSpecialBadgeCounts() {
        val listed = chat(1, unread = 2, readInbox = 10).copy(
            unreadMentionsCount = 2,
            unreadReactionsCount = 3,
        )
        val next = applyReadStates(
            listOf(listed),
            mapOf(1L to ChatReadState(id = 1, readInboxMaxId = 20, unreadCount = 0)),
        ).single()
        assertEquals(20, next.readInboxMaxId)
        assertEquals(0, next.unreadCount)
        assertEquals(2, next.unreadMentionsCount)
        assertEquals(3, next.unreadReactionsCount)
    }

    @Test
    fun incomingTextBumpsUnreadAndPreview() {
        val next = applyIncomingMessage(
            listOf(chat(1, unread = 2, date = 10)),
            message(chatId = 1, id = 8, text = "hi", date = 99),
        )
        assertEquals(1, next.size)
        assertEquals("hi", next.single().lastMessagePreview)
        assertEquals(99L, next.single().lastMessageDate)
        assertEquals(3, next.single().unreadCount)
    }

    @Test
    fun outgoingDoesNotBumpUnread() {
        val next = applyIncomingMessage(
            listOf(chat(1, unread = 2)),
            message(chatId = 1, id = 8, text = "sent", date = 50, outgoing = true),
        )
        assertEquals(2, next.single().unreadCount)
        assertEquals("sent", next.single().lastMessagePreview)
    }

    @Test
    fun alreadyReadDoesNotBumpUnread() {
        val next = applyIncomingMessage(
            listOf(chat(1, unread = 0, readInbox = 10)),
            message(chatId = 1, id = 9, text = "old", date = 50),
        )
        assertEquals(0, next.single().unreadCount)
    }

    @Test
    fun mediaKindPreviewWhenNoText() {
        val next = applyIncomingMessage(
            listOf(chat(1)),
            message(chatId = 1, id = 2, text = null, date = 7, mediaKind = "photo"),
        )
        assertEquals(null, next.single().lastMessagePreview)
        assertEquals("photo", next.single().lastMessageMediaKind)
        assertEquals("Photo", next.single().displayPreview())
    }

    @Test
    fun groupPreviewPrefixesSenderAndCaption() {
        val next = applyIncomingMessage(
            listOf(chat(1, isGroup = true)),
            message(chatId = 1, id = 2, text = "hello", date = 7, mediaKind = "photo"),
        )
        assertEquals("hello", next.single().lastMessagePreview)
        assertEquals("Mike: Photo, hello", next.single().displayPreview())
    }

    @Test
    fun groupOutgoingUsesYou() {
        val next = applyIncomingMessage(
            listOf(chat(1, isGroup = true)),
            message(chatId = 1, id = 2, text = "sent", date = 7, outgoing = true),
        )
        assertEquals("sent", next.single().lastMessagePreview)
        assertEquals("You: sent", next.single().displayPreview())
    }

    @Test
    fun dialogsPageRestoresPinOrderOverCachedDateOrder() {
        val cached = listOf(
            chat(1, pinned = true, date = 30),
            chat(2, pinned = true, date = 20),
            chat(3, pinned = false, date = 40),
        )
        val page = listOf(
            chat(2, pinned = true, pinnedOrder = 0, date = 20),
            chat(1, pinned = true, pinnedOrder = 1, date = 30),
            chat(3, pinned = false, date = 40),
        )
        val next = withNetworkPinOrder(mergeChats(cached, page), page)
        assertEquals(listOf(2L, 1L, 3L), next.map { it.id.value })
        assertEquals(0, next[0].pinnedOrder)
        assertEquals(1, next[1].pinnedOrder)
    }

    @Test
    fun pinnedStayInPinOrderWhenMiddleGetsNewerMessage() {
        val chats = listOf(
            chat(1, pinned = true, pinnedOrder = 0, date = 10),
            chat(2, pinned = true, pinnedOrder = 1, date = 20),
            chat(3, pinned = true, pinnedOrder = 2, date = 30),
            chat(4, pinned = false, date = 40),
        )
        val next = applyIncomingMessage(
            chats,
            message(chatId = 2, id = 9, text = "new", date = 100),
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), next.map { it.id.value })
        assertEquals("new", next[1].lastMessagePreview)
        assertEquals(100L, next[1].lastMessageDate)
    }

    @Test
    fun editedLatestUpdatesPreview() {
        val chats = listOf(chat(1, preview = "old").copy(lastMessageId = 8))
        val edited = applyEditedMessage(
            chats,
            message(chatId = 1, id = 8, text = "edited", date = 11),
        )
        assertEquals("edited", edited.single().lastMessagePreview)
    }

    @Test
    fun deletedLatestFallsBackToOlderMessage() {
        val chats = listOf(chat(1, preview = "gone").copy(lastMessageId = 9))
        val next = applyLatestReplacement(
            chats,
            PeerId(1),
            message(chatId = 1, id = 8, text = "older", date = 5),
        )
        assertEquals("older", next.single().lastMessagePreview)
        assertEquals(8, next.single().lastMessageId)
    }

    @Test
    fun mergeKeepsRealTitleOverPlaceholder() {
        val current = listOf(chat(1).copy(title = "News"))
        val next = mergeChats(current, listOf(chat(1).copy(title = "Channel 1")))
        assertEquals("News", next.single().title)
    }

    @Test
    fun mergeKeepsOutgoingTicksWhenDialogsOmitThem() {
        val current = listOf(
            chat(1).copy(lastMessageOutgoing = true, lastMessageId = 8, readOutboxMaxId = 8),
        )
        val next = mergeChats(
            current,
            listOf(chat(1).copy(lastMessageId = 8, readOutboxMaxId = 8)),
        )
        assertEquals(true, next.single().lastMessageOutgoing)
    }

    @Test
    fun incomingUnknownChatDoesNotMintARow() {
        val listed = listOf(chat(1))
        val next = applyIncomingMessage(
            listed,
            message(chatId = 99, id = 1, text = "x", date = 9),
        )
        assertEquals(listOf(1L), next.map { it.id.value })
    }

    @Test
    fun placeholderIncomingKeepsArchivedFlag() {
        val archived = chat(8).copy(archived = true, title = "Old")
        val stub = Chat(PeerId(8), title = "")
        val next = mergeChats(listOf(archived), listOf(stub))
        assertEquals(true, next.single().archived)
        assertEquals("Old", next.single().title)
    }

    @Test
    fun incomingDoesNotWipeTitle() {
        val chats = listOf(chat(1).copy(title = "News"))
        val next = applyIncomingMessage(
            chats,
            message(chatId = 1, id = 4, text = "hi", date = 9),
        )
        assertEquals("News", next.single().title)
    }

    @Test
    fun unpinnedStillSortByDateBelowPins() {
        val chats = listOf(
            chat(1, pinned = true, pinnedOrder = 0, date = 1),
            chat(5, pinned = false, date = 50),
            chat(6, pinned = false, date = 10),
        )
        val next = applyIncomingMessage(
            chats,
            message(chatId = 6, id = 3, text = "hi", date = 80),
        )
        assertEquals(listOf(1L, 6L, 5L), next.map { it.id.value })
    }
}
