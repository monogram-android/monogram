package org.monogram.presentation.features.chats.conversation.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.ConversationUpdate
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageReactionModel
import org.monogram.domain.models.MessageSendingState
import org.monogram.presentation.features.chats.conversation.ChatComponent
import org.monogram.presentation.features.chats.conversation.ConversationSessionState
import org.monogram.presentation.features.chats.conversation.ConversationWindowReducer
import org.monogram.presentation.features.chats.conversation.OutgoingMessageReducer

class ConversationSessionProjectionTest {
    @Test
    fun `conversation update bookkeeping remaps and cleans deleted ids`() {
        val remappedIds = mutableMapOf(-10L to 10L, -20L to 20L)
        val reactionSuppression = mutableMapOf(10L to 1L, 20L to 2L)

        applyConversationUpdateBookkeeping(
            update = ConversationUpdate.ReplaceTemporaryId(CHAT_ID, -30L, textMessage(30L)),
            remappedMessageIds = remappedIds,
            reactionUpdateSuppressedUntil = reactionSuppression
        )
        applyConversationUpdateBookkeeping(
            update = ConversationUpdate.Delete(CHAT_ID, setOf(10L, -20L)),
            remappedMessageIds = remappedIds,
            reactionUpdateSuppressedUntil = reactionSuppression
        )

        assertEquals(mapOf(-30L to 30L), remappedIds)
        assertEquals(mapOf(20L to 2L), reactionSuppression)
    }

    @Test
    fun `new session message owns window and preserves unread side effect`() {
        val existing = textMessage(10L)
        val incoming = textMessage(20L)
        val projected = ChatComponent.State(
            chatId = CHAT_ID,
            messages = listOf(existing),
            isAtBottom = false,
            lastReadInboxMessageId = 10L
        ).withConversationSessionUpdate(
            sessionState = ConversationSessionState(messages = listOf(incoming, existing)),
            update = ConversationUpdate.Upsert(CHAT_ID, incoming, isNew = true),
            rootChatId = CHAT_ID
        )

        assertEquals(listOf(20L, 10L), projected.messages.map(MessageModel::id))
        assertEquals(1, projected.unreadCount)
        assertEquals(1, projected.unreadSeparatorCount)
    }

    @Test
    fun `session edit preserves runtime media state and suppressed reaction`() {
        val localReaction = MessageReactionModel(emoji = "x", count = 1, isChosen = true)
        val remoteReaction = MessageReactionModel(emoji = "y", count = 2, isChosen = false)
        val current = photoMessage(
            caption = "before",
            path = "cached.jpg",
            isDownloading = true,
            progress = 0.5f,
            reactions = listOf(localReaction)
        )
        val edited = photoMessage(
            caption = "after",
            path = null,
            isDownloading = false,
            progress = 0f,
            reactions = listOf(remoteReaction)
        )

        val projected = ChatComponent.State(chatId = CHAT_ID, messages = listOf(current))
            .withConversationSessionUpdate(
                sessionState = ConversationSessionState(messages = listOf(edited)),
                update = ConversationUpdate.Upsert(CHAT_ID, edited, isNew = false),
                rootChatId = CHAT_ID,
                suppressReactionUpdate = true
            )

        val content = projected.messages.single().content as MessageContent.Photo
        assertEquals("after", content.caption)
        assertEquals("cached.jpg", content.path)
        assertTrue(content.isDownloading)
        assertEquals(0.5f, content.downloadProgress)
        assertEquals(listOf(localReaction), projected.messages.single().reactions)
    }

    @Test
    fun `session edit preserves optimistic content until update is confirmed`() {
        val current = textMessage(10L, "optimistic")
        val stale = textMessage(10L, "stale")

        val projected = ChatComponent.State(
            chatId = CHAT_ID,
            messages = listOf(current),
            pendingEditedMessageIds = setOf(10L)
        ).withConversationSessionUpdate(
            sessionState = ConversationSessionState(messages = listOf(stale)),
            update = ConversationUpdate.Upsert(CHAT_ID, stale, isNew = true),
            rootChatId = CHAT_ID
        )

        assertEquals(
            "optimistic",
            (projected.messages.single().content as MessageContent.Text).text
        )
        assertTrue(10L in projected.pendingEditedMessageIds)
    }

    @Test
    fun `confirmed edit and delete clear pending edit markers`() {
        val edited = textMessage(10L, "confirmed")
        val confirmed = ChatComponent.State(
            chatId = CHAT_ID,
            messages = listOf(textMessage(10L, "optimistic")),
            pendingEditedMessageIds = setOf(10L, 20L)
        ).withConversationSessionUpdate(
            sessionState = ConversationSessionState(messages = listOf(edited)),
            update = ConversationUpdate.Upsert(CHAT_ID, edited, isNew = false),
            rootChatId = CHAT_ID
        )
        val deleted = confirmed.withConversationSessionUpdate(
            sessionState = ConversationSessionState(messages = emptyList()),
            update = ConversationUpdate.Delete(CHAT_ID, setOf(20L)),
            rootChatId = CHAT_ID
        )

        assertFalse(10L in confirmed.pendingEditedMessageIds)
        assertEquals("confirmed", (confirmed.messages.single().content as MessageContent.Text).text)
        assertFalse(20L in deleted.pendingEditedMessageIds)
        assertTrue(deleted.messages.isEmpty())
    }

    @Test
    fun `session outgoing lifecycle replaces legacy projection`() {
        val key = OutgoingMessageReducer.Key(CHAT_ID, -10L)
        val projected = ChatComponent.State(
            chatId = CHAT_ID,
            outgoingMessageStates = mapOf(key to OutgoingMessageReducer.State.PendingLocal)
        ).withConversationSessionUpdate(
            sessionState = ConversationSessionState(
                outgoingMessageStates = mapOf(key to OutgoingMessageReducer.State.Acknowledged)
            ),
            update = ConversationUpdate.SendAcknowledged(CHAT_ID, -10L),
            rootChatId = CHAT_ID
        )

        assertEquals(
            OutgoingMessageReducer.State.Acknowledged,
            projected.outgoingMessageStates[key]
        )
    }

    @Test
    fun `outgoing message is projected away from latest boundary`() {
        val existing = textMessage(10L)
        val outgoing = textMessage(20L).copy(
            isOutgoing = true,
            sendingState = MessageSendingState.Pending
        )
        val sessionState = ConversationWindowReducer.applyUpdate(
            state = ConversationSessionState(messages = listOf(existing)),
            update = ConversationUpdate.Upsert(CHAT_ID, outgoing, isNew = true),
            canInsertNewMessage = true
        )

        val projected = ChatComponent.State(
            chatId = CHAT_ID,
            messages = listOf(existing),
            isAtBottom = false,
            isLatestLoaded = false
        ).withConversationSessionUpdate(
            sessionState = sessionState,
            update = ConversationUpdate.Upsert(CHAT_ID, outgoing, isNew = true),
            rootChatId = CHAT_ID
        )

        assertEquals(listOf(20L, 10L), projected.messages.map(MessageModel::id))
        assertTrue(projected.isLatestLoaded)
    }

    @Test
    fun `history merge preserves pending outgoing reactions and optimistic edit`() {
        val reaction = MessageReactionModel(emoji = "x", count = 1, isChosen = true)
        val pending = textMessage(-1L, "pending").copy(
            isOutgoing = true,
            sendingState = MessageSendingState.Pending
        )
        val edited = textMessage(10L, "optimistic").copy(reactions = listOf(reaction))
        val merged = ChatComponent.State(
            chatId = CHAT_ID,
            messages = listOf(edited, pending),
            pendingEditedMessageIds = setOf(10L)
        ).mergeHistoryMessages(
            filteredNewMessages = listOf(textMessage(10L, "stale"), textMessage(20L)),
            replace = true
        )

        assertEquals(listOf(20L, 10L, -1L), merged.map(MessageModel::id))
        assertEquals("optimistic", (merged[1].content as MessageContent.Text).text)
        assertEquals(listOf(reaction), merged[1].reactions)
        assertEquals(MessageSendingState.Pending, merged[2].sendingState)
    }

    private fun textMessage(id: Long, text: String = "message") = MessageModel(
        id = id,
        date = id.toInt(),
        isOutgoing = false,
        senderName = "sender",
        chatId = CHAT_ID,
        content = MessageContent.Text(text)
    )

    private fun photoMessage(
        caption: String,
        path: String?,
        isDownloading: Boolean,
        progress: Float,
        reactions: List<MessageReactionModel>
    ) = MessageModel(
        id = 10L,
        date = 10,
        isOutgoing = false,
        senderName = "sender",
        chatId = CHAT_ID,
        content = MessageContent.Photo(
            path = path,
            width = 100,
            height = 100,
            caption = caption,
            isDownloading = isDownloading,
            downloadProgress = progress,
            fileId = 7
        ),
        reactions = reactions,
        sendingState = MessageSendingState.Pending
    )

    private fun pollMessage(voterCount: Int) = MessageModel(
        id = 10L,
        date = 10,
        isOutgoing = false,
        senderName = "sender",
        chatId = CHAT_ID,
        content = MessageContent.Poll(
            id = 100L,
            question = "question",
            options = emptyList(),
            totalVoterCount = voterCount,
            isClosed = false,
            isAnonymous = true,
            type = org.monogram.domain.models.PollType.Regular(allowMultipleAnswers = false),
            openPeriod = 0,
            closeDate = 0
        )
    )

    private companion object {
        const val CHAT_ID = 1L
    }
}
