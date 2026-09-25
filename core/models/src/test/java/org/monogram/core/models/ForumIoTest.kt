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
    fun generalHidesOtherTopicMessages() {
        val general = Message(
            MessageId(PeerId(1), 10), null, "gen", 1, outgoing = false,
        )
        val topicReply = Message(
            MessageId(PeerId(1), 11), null, "flood", 2, outgoing = true,
            replyToMsgId = 9,
            replyToTopId = 42,
            forumTopic = true,
        )
        val sendToTopic = Message(
            MessageId(PeerId(1), 12), null, "plain", 3, outgoing = true,
            replyToMsgId = 42,
            forumTopic = true,
        )
        val topWithoutFlag = Message(
            MessageId(PeerId(1), 13), null, "legacy", 4, outgoing = true,
            replyToTopId = 42,
        )
        assertEquals(GENERAL_FORUM_TOPIC_ID, ForumIo.topicId(general, true))
        assertEquals(42, ForumIo.topicId(topicReply, true))
        assertEquals(42, ForumIo.topicId(sendToTopic, true))
        assertEquals(42, ForumIo.topicId(topWithoutFlag, true))
        assertTrue(ForumIo.belongsToOpenTopic(general, GENERAL_FORUM_TOPIC_ID, true))
        assertFalse(ForumIo.belongsToOpenTopic(topicReply, GENERAL_FORUM_TOPIC_ID, true))
        assertFalse(ForumIo.belongsToOpenTopic(sendToTopic, GENERAL_FORUM_TOPIC_ID, true))
        assertTrue(ForumIo.belongsToOpenTopic(topicReply, 42, true))
    }

    @Test
    fun hiddenGeneralSortsAbovePinnedTopics() {
        val general = ForumTopic(id = 1, title = "General", hidden = true, date = 1)
        val pinned = ForumTopic(id = 2, title = "Bugs", pinned = true, date = 9)
        val late = ForumTopic(id = 3, title = "Flood", date = 8)
        val sorted = ForumIo.sortForumTopics(listOf(late, pinned, general))
        assertEquals(listOf(1, 2, 3), sorted.map { it.id })
        assertEquals(1, ForumIo.hiddenTopicCount(sorted))
    }

    @Test
    fun outgoingTopicStampUsesTopicAsReply() {
        val (reply, top) = ForumIo.sendReplyIds(42, 9)
        assertEquals(9, reply)
        assertEquals(42, top)
        val root = ForumIo.sendReplyIds(42, null)
        assertEquals(42 to 0, root)
    }

    @Test
    fun hiddenGeneralStaysOutOfTheMainListUntilRevealed() {
        val general = ForumTopic(id = 1, title = "General", hidden = true, date = 1)
        val flood = ForumTopic(id = 42, title = "Flood", date = 8)
        val collapsed = ForumIo.displayedForumTopics(listOf(flood, general), archiveRevealed = false)
        val opened = ForumIo.displayedForumTopics(listOf(flood, general), archiveRevealed = true)
        assertEquals(listOf(42), collapsed.map { it.id })
        assertEquals(listOf(1, 42), opened.map { it.id })
        assertTrue(ForumIo.canHideGeneral(true))
        assertFalse(ForumIo.canHideGeneral(false))
    }

    @Test
    fun archivePullRevealsAfterSnapOnlyAtTop() {
        assertFalse(
            ForumIo.archivePullShouldReveal(
                atTop = true, hiddenCount = 1, revealed = false, pulledPx = 20f, snapPx = 48f,
            ),
        )
        assertTrue(
            ForumIo.archivePullShouldReveal(
                atTop = true, hiddenCount = 1, revealed = false, pulledPx = 48f, snapPx = 48f,
            ),
        )
        assertFalse(
            ForumIo.archivePullShouldReveal(
                atTop = false, hiddenCount = 1, revealed = false, pulledPx = 80f, snapPx = 48f,
            ),
        )
        assertFalse(ForumIo.canHideGeneral(false))
        assertTrue(ForumIo.canHideGeneral(true))
        var pull = ForumIo.ArchivePullState()
        pull = ForumIo.archivePullOnScroll(pull, true, 1, 20f, 48f)
        assertEquals(20f, pull.pulledPx, 0.01f)
        pull = ForumIo.archivePullOnRelease(pull)
        assertEquals(0f, pull.pulledPx, 0.01f)
        assertFalse(pull.revealed)
        pull = ForumIo.archivePullOnScroll(pull, true, 1, 20f, 48f)
        assertFalse(pull.revealed)
        pull = ForumIo.archivePullOnScroll(pull, true, 1, -8f, 48f)
        assertEquals(12f, pull.pulledPx, 0.01f)
        pull = ForumIo.archivePullOnScroll(pull, true, 1, 40f, 48f)
        assertTrue(pull.revealed)
        assertEquals(0f, pull.pulledPx, 0.01f)
        assertEquals(0f, ForumIo.archivePeekPx(1, revealed = false, pulledPx = 0f, rowHeightPx = 72f), 0.01f)
        assertEquals(24f, ForumIo.archivePeekPx(1, revealed = false, pulledPx = 24f, rowHeightPx = 72f), 0.01f)
        assertEquals(72f, ForumIo.archivePeekPx(1, revealed = true, pulledPx = 0f, rowHeightPx = 72f), 0.01f)
        assertTrue(ForumIo.archivePullFollowsFinger(ForumIo.ArchivePullState(pulledPx = 12f)))
        assertFalse(ForumIo.archivePullFollowsFinger(ForumIo.ArchivePullState(revealed = true, pulledPx = 12f)))
    }

    @Test
    fun mergeAcceptsServerForumFlagInBothDirections() {
        val stored = Chat(id = PeerId(-1), title = "A", isForum = true)
        val incoming = Chat(id = PeerId(-1), title = "A", isForum = false)
        assertFalse(incoming.mergeLocalCache(stored).isForum)
        assertTrue(stored.mergeLocalCache(incoming).isForum)
    }
}
