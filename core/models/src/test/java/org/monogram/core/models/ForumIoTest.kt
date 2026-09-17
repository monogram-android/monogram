package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForumIoTest {
    @Test
    fun generalTopicUsesHistoryNotReplies() {
        assertEquals(0, ForumIo.historyThreadId(0))
        assertEquals(0, ForumIo.historyThreadId(GENERAL_FORUM_TOPIC_ID))
        assertEquals(42, ForumIo.historyThreadId(42))
        assertFalse(ForumIo.usesDiscussionRead(GENERAL_FORUM_TOPIC_ID))
        assertTrue(ForumIo.usesDiscussionRead(42))
    }

    @Test
    fun sendToTopicUsesTopicAsReplyWithoutTop() {
        assertEquals(42 to 0, ForumIo.sendReplyIds(42, null))
        assertEquals(0 to 0, ForumIo.sendReplyIds(GENERAL_FORUM_TOPIC_ID, null))
        assertEquals(0 to 0, ForumIo.sendReplyIds(0, null))
    }

    @Test
    fun replyInsideTopicSetsTopMsgId() {
        assertEquals(9 to 42, ForumIo.sendReplyIds(42, 9))
        assertEquals(42 to 0, ForumIo.sendReplyIds(42, 42))
        assertEquals(9 to 0, ForumIo.sendReplyIds(GENERAL_FORUM_TOPIC_ID, 9))
    }

    @Test
    fun topicListOnlyWhenForumAndNotInsideTopic() {
        assertTrue(ForumIo.showTopicList(isForum = true, threadTopMsgId = 0))
        assertFalse(ForumIo.showTopicList(isForum = true, threadTopMsgId = 42))
        assertFalse(ForumIo.showTopicList(isForum = false, threadTopMsgId = 0))
    }

    @Test
    fun openingGeneralKeepsIdOne() {
        assertEquals(GENERAL_FORUM_TOPIC_ID, ForumIo.dialogThreadId(GENERAL_FORUM_TOPIC_ID))
        assertEquals(7, ForumIo.dialogThreadId(7))
        assertTrue(ForumIo.showTopicList(true, 0))
        assertFalse(ForumIo.showTopicList(true, GENERAL_FORUM_TOPIC_ID))
    }

    @Test
    fun shortAndDeletedTopicsKeepUnreadFields() {
        val short = ForumTopic(id = 3, title = "Bugs", short = true, unreadCount = 4)
        val deleted = ForumTopic(id = 9, title = "", deleted = true)
        assertEquals(4, short.unreadCount)
        assertTrue(deleted.deleted)
        assertFalse(short.isGeneral)
        assertTrue(ForumTopic(id = 1, title = "General").isGeneral)
    }

    @Test
    fun mergeAcceptsServerForumFlagInBothDirections() {
        val stored = Chat(id = PeerId(-1), title = "A", isForum = true)
        val incoming = Chat(id = PeerId(-1), title = "A", isForum = false)
        assertFalse(incoming.mergeLocalCache(stored).isForum)
        assertTrue(stored.mergeLocalCache(incoming).isForum)
    }
}
