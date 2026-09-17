package org.monogram.feature.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DialogStateKeyTest {
    private val chatId = -1002833685415L

    @Test
    fun forumTopicDoesNotCollideWithItsTopicList() {
        // Reported crash: opening a topic of t.me/koinoniwachat made two live dialogs
        // share the chat-id key ("Key -1002833685415 was used multiple times").
        val topicList = dialogStateKey(chatId, threadTopMsgId = 0, jumpToMessageId = 0)
        val topic = dialogStateKey(chatId, threadTopMsgId = 1, jumpToMessageId = 0)
        val otherTopic = dialogStateKey(chatId, threadTopMsgId = 49, jumpToMessageId = 0)
        assertNotEquals(topicList, topic)
        assertNotEquals(topic, otherTopic)
        assertNotEquals(topicList, otherTopic)
    }

    @Test
    fun jumpTargetsAreDistinctEntries() {
        val plain = dialogStateKey(chatId, threadTopMsgId = 0, jumpToMessageId = 0)
        val jumped = dialogStateKey(chatId, threadTopMsgId = 0, jumpToMessageId = 12345)
        assertNotEquals(plain, jumped)
    }

    @Test
    fun sameEntryKeepsTheSameKeyForRestoration() {
        assertEquals(
            dialogStateKey(chatId, threadTopMsgId = 15, jumpToMessageId = 7),
            dialogStateKey(chatId, threadTopMsgId = 15, jumpToMessageId = 7),
        )
    }
}
