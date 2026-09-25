package org.monogram.feature.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.ForumIo
import org.monogram.core.models.ForumTopic
import org.monogram.core.models.GENERAL_FORUM_TOPIC_ID
import org.monogram.core.models.PeerId
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.feature.dialog.store.filterThreadMessages

class DialogForumStateTest {
    @Test
    fun forumChatWithoutThreadShowsTopicList() {
        val state = DialogStore.State(chatId = PeerId(-1_000_000_000_005), isForum = true)
        assertTrue(state.showTopicList)
        assertFalse(state.isCommentThread)
    }

    @Test
    fun commentThreadIsNotAForumTopic() {
        val state = DialogStore.State(chatId = PeerId(1), isForum = false, threadTopId = 42)
        assertTrue(state.isCommentThread)
        assertFalse(state.inForumTopic)
        assertFalse(state.showTopicList)
    }

    @Test
    fun generalTopicUsesHistoryNotTopicList() {
        val state = DialogStore.State(
            chatId = PeerId(1),
            isForum = true,
            threadTopId = GENERAL_FORUM_TOPIC_ID,
        )
        assertFalse(state.showTopicList)
        assertEquals(0, ForumIo.historyThreadId(state.threadTopId))
    }

    @Test
    fun namedTopicUsesRepliesAndDiscussionRead() {
        val state = DialogStore.State(chatId = PeerId(1), isForum = true, threadTopId = 42)
        assertFalse(state.showTopicList)
        assertEquals(42, ForumIo.historyThreadId(state.threadTopId))
        assertTrue(ForumIo.usesDiscussionRead(state.threadTopId))
        assertEquals(9 to 42, ForumIo.sendReplyIds(state.threadTopId, 9))
    }

    @Test
    fun threadFilterRejectsUnrelatedParentAndKeepsNestedReplies() {
        val root = Message(MessageId(PeerId(1), 42), null, "root", 1, outgoing = false)
        val nestedParent = Message(
            MessageId(PeerId(1), 43), null, "reply", 2, outgoing = false, replyToMsgId = 42,
        )
        val nested = Message(
            MessageId(PeerId(1), 44), null, "nested", 3, outgoing = false, replyToMsgId = 43,
        )
        val unrelatedParent = Message(MessageId(PeerId(1), 90), null, "other", 4, outgoing = false)
        val falseReply = Message(
            MessageId(PeerId(1), 91), null, "false", 5, outgoing = false, replyToMsgId = 90,
        )
        val kept = filterThreadMessages(listOf(root, nestedParent, nested, unrelatedParent, falseReply), 42)
        assertEquals(listOf(42, 43, 44), kept.map { it.id.id })
    }

    @Test
    fun generalHistoryDropsOtherTopicMessagesIncludingOutgoing() {
        val general = Message(
            MessageId(PeerId(1), 10), null, "gen", 1, outgoing = false,
        )
        val flood = Message(
            MessageId(PeerId(1), 11), null, "там вроде не бета", 2, outgoing = true,
            replyToMsgId = 9,
            replyToTopId = 42,
            forumTopic = true,
        )
        val pending = Message(
            MessageId(PeerId(1), -3), null, "pending flood", 3, outgoing = true,
            pending = true,
            replyToMsgId = 42,
            forumTopic = true,
        )
        val kept = filterThreadMessages(
            listOf(general, flood, pending),
            GENERAL_FORUM_TOPIC_ID,
            isForum = true,
        )
        assertEquals(listOf(10), kept.map { it.id.id })
    }

    @Test
    fun sendInNamedTopicThenOpenGeneralDropsTheOutgoingRow() {
        val (replyId, topId) = ForumIo.sendReplyIds(42, 9)
        val pending = Message(
            MessageId(PeerId(1), -7), null, "там вроде не бета", 21, outgoing = true,
            pending = true,
            replyToMsgId = replyId.takeIf { it > 0 },
            replyToTopId = topId.takeIf { it > 0 },
            forumTopic = ForumIo.historyThreadId(42) > 0,
        )
        val confirmed = pending.copy(
            id = MessageId(PeerId(1), 11),
            pending = false,
            forumTopic = true,
            replyToMsgId = 9,
            replyToTopId = 42,
        )
        val inFlood = filterThreadMessages(listOf(pending, confirmed), 42, isForum = true)
        val inGeneral = filterThreadMessages(
            listOf(pending, confirmed),
            GENERAL_FORUM_TOPIC_ID,
            isForum = true,
        )
        assertEquals(listOf(-7, 11), inFlood.map { it.id.id })
        assertTrue(inGeneral.isEmpty())
    }

    @Test
    fun rejectedHideRestoresTopicListMemory() {
        val chat = 99L
        val shown = listOf(ForumTopic(id = 1, title = "General", hidden = false))
        val hidden = listOf(ForumTopic(id = 1, title = "General", hidden = true, closed = true))
        TopicListMemory.put(chat, shown)
        TopicListMemory.put(chat, hidden)
        TopicListMemory.put(chat, shown)
        assertEquals(false, TopicListMemory.get(chat).single().hidden)
        TopicListMemory.clear()
    }
}
