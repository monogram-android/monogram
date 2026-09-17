package org.monogram.feature.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.ForumIo
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
}
