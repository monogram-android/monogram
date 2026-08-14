package org.monogram.data.datasource.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.drinkless.tdlib.TdApi
import org.monogram.domain.repository.ConversationScope

class RemoteHistoryFetchModeTest {
    @Test
    fun `typed sources do not hide fallback requests`() {
        assertEquals(
            listOf(true),
            historyFetchAttempts(RemoteHistoryFetchMode.LocalOnly, ConversationScope.Main)
        )
        assertEquals(
            listOf(false),
            historyFetchAttempts(RemoteHistoryFetchMode.NetworkOnly, ConversationScope.Main)
        )
        assertEquals(
            listOf(true, false),
            historyFetchAttempts(RemoteHistoryFetchMode.LocalThenNetwork, ConversationScope.Main)
        )
    }

    @Test
    fun `scoped history keeps local reconciliation separate from network fetch`() {
        val thread = ConversationScope.MessageThread(42L)
        assertEquals(
            emptyList<Boolean>(),
            historyFetchAttempts(RemoteHistoryFetchMode.LocalOnly, thread)
        )
        assertEquals(
            listOf(false),
            historyFetchAttempts(RemoteHistoryFetchMode.NetworkOnly, thread)
        )
        assertEquals(
            listOf(false),
            historyFetchAttempts(RemoteHistoryFetchMode.LocalThenNetwork, thread)
        )
    }

    @Test
    fun `local history scope distinguishes forum topics from message threads`() {
        val forumTopic = TdApi.MessageTopicForum(42)
        val messageThread = TdApi.MessageTopicThread(42L)

        assertTrue(messageMatchesScope(forumTopic, ConversationScope.ForumTopic(42L)))
        assertFalse(messageMatchesScope(messageThread, ConversationScope.ForumTopic(42L)))
        assertTrue(messageMatchesScope(messageThread, ConversationScope.MessageThread(42L)))
        assertFalse(messageMatchesScope(forumTopic, ConversationScope.MessageThread(42L)))
    }

    @Test
    fun `main history accepts messages regardless of topic metadata`() {
        assertTrue(messageMatchesScope(null, ConversationScope.Main))
        assertTrue(messageMatchesScope(TdApi.MessageTopicForum(1), ConversationScope.Main))
        assertTrue(messageMatchesScope(TdApi.MessageTopicThread(1L), ConversationScope.Main))
    }

    @Test
    fun `typed history scope selects the matching TDLib API`() {
        val main = buildHistoryRequest(1L, 2L, -3, 4, ConversationScope.Main, onlyLocal = true)
        val forum =
            buildHistoryRequest(1L, 2L, -3, 4, ConversationScope.ForumTopic(5L), onlyLocal = false)
        val thread = buildHistoryRequest(
            1L,
            2L,
            -3,
            4,
            ConversationScope.MessageThread(5L),
            onlyLocal = false
        )

        assertTrue(main is TdApi.GetChatHistory && main.onlyLocal)
        assertTrue(forum is TdApi.GetForumTopicHistory && forum.forumTopicId == 5)
        assertTrue(thread is TdApi.GetMessageThreadHistory && thread.messageId == 5L)
        assertThrows(IllegalArgumentException::class.java) {
            buildHistoryRequest(1L, 0L, 0, 4, ConversationScope.ForumTopic(5L), onlyLocal = true)
        }
    }

    @Test
    fun `scoped local reconciliation uses only offline message requests`() {
        val requests = buildLocalMessageRequests(chatId = 7L, messageIds = listOf(11L, 12L))

        assertEquals(listOf(7L, 7L), requests.map { it.chatId })
        assertEquals(listOf(11L, 12L), requests.map { it.messageId })
    }
}
