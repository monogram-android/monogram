package org.monogram.data.datasource.cache

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.monogram.data.db.MonogramDatabase
import org.monogram.data.db.model.MessageEntity
import org.monogram.data.db.model.MessageWindowEntity
import org.monogram.domain.repository.ConversationKey

@RunWith(AndroidJUnit4::class)
class RoomMessageCacheIntegrationTest {
    private lateinit var database: MonogramDatabase
    private lateinit var source: RoomChatLocalDataSource

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MonogramDatabase::class.java
        ).allowMainThreadQueries().build()
        source = RoomChatLocalDataSource(
            database = database,
            chatDao = database.chatDao(),
            messageDao = database.messageDao(),
            chatFullInfoDao = database.chatFullInfoDao(),
            topicDao = database.topicDao(),
            messageWindowDao = database.messageWindowDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun historyPageAndCoverageAreCommittedTogether() = runBlocking {
        val window = window(scopeType = "forum_topic", scopeId = 42L)
        source.applyMessageCacheMutations(
            listOf(
                MessageCacheMutation.PersistHistoryBatch(
                    writes = listOf(
                        MessageCacheMutation.HistoryWrite(message(1L, threadId = 42L), null),
                        MessageCacheMutation.HistoryWrite(message(2L, threadId = 42L), null)
                    ),
                    window = window
                )
            )
        )

        assertEquals(
            listOf(2L, 1L),
            source.getLatestMessages(CHAT_ID, 10, 42L).map(MessageEntity::id)
        )
        assertEquals(window, source.getMessageWindow(CHAT_ID, "forum_topic", 42L))
        assertNull(source.getMessageWindow(CHAT_ID, "message_thread", 42L))
    }

    @Test
    fun replaceDeleteAndReadMutationsRemainOrdered() = runBlocking {
        val temporary = message(-1L, isOutgoing = true)
        val inbox = message(10L)
        source.applyMessageCacheMutations(
            listOf(
                MessageCacheMutation.Persist(temporary, ConversationKey(CHAT_ID)),
                MessageCacheMutation.Persist(inbox, ConversationKey(CHAT_ID)),
                MessageCacheMutation.MarkRead(CHAT_ID, 10L),
                MessageCacheMutation.ReplaceId(
                    CHAT_ID,
                    -1L,
                    message(20L, isOutgoing = true),
                    ConversationKey(CHAT_ID)
                ),
                MessageCacheMutation.DeleteMessages(CHAT_ID, listOf(10L))
            )
        )

        val rows = source.getLatestMessages(CHAT_ID, 10)
        assertEquals(listOf(20L), rows.map(MessageEntity::id))
        assertTrue(rows.single().isOutgoing)
    }

    @Test
    fun readAndContentUpdatesDoNotOverwriteEachOther() = runBlocking {
        source.insertMessage(message(10L, content = "before"))

        val read = async(Dispatchers.IO) { source.markAsRead(CHAT_ID, 10L) }
        val edit = async(Dispatchers.IO) {
            source.updateMessageContent(
                chatId = CHAT_ID,
                messageId = 10L,
                content = "after",
                contentType = "text",
                contentMeta = null,
                mediaFileId = 0,
                mediaPath = null,
                editDate = 2
            )
        }
        read.await()
        edit.await()

        val row = source.getMessagesByIds(CHAT_ID, listOf(10L)).single()
        assertTrue(row.isRead)
        assertEquals("after", row.content)
        assertEquals(2, row.editDate)
    }

    @Test
    fun cleanupInvalidatesCoverageAndKeepsProtectedAndPendingRows() = runBlocking {
        val protectedId = 1L
        val pendingId = -1L
        val writes = buildList {
            add(MessageCacheMutation.HistoryWrite(message(pendingId, isOutgoing = true), null))
            (1L..502L).forEach { id -> add(MessageCacheMutation.HistoryWrite(message(id), null)) }
        }
        source.applyMessageCacheMutations(
            listOf(
                MessageCacheMutation.PersistHistoryBatch(
                    writes = writes,
                    window = window(protectedMessageId = protectedId)
                )
            )
        )

        val remaining = source.getLatestMessages(CHAT_ID, 600)
        val coverage = source.getMessageWindow(CHAT_ID, "main", 0L)
        assertTrue(remaining.any { it.id == protectedId })
        assertTrue(remaining.any { it.id == pendingId })
        assertEquals(502, remaining.size)
        assertNull(coverage?.oldestMessageId)
        assertNull(coverage?.newestMessageId)
        assertFalse(coverage?.olderBoundaryReached ?: true)
        assertFalse(coverage?.newerBoundaryReached ?: true)
        assertEquals(protectedId, coverage?.protectedMessageId)
    }

    @Test
    fun historyQueriesUseChatScopeIndices() {
        val plans = listOf(
            explain("SELECT * FROM messages WHERE chatId = 1 AND threadId IS NULL ORDER BY date DESC, id DESC LIMIT 50"),
            explain("SELECT * FROM messages WHERE chatId = 1 AND threadId IS NULL AND id < 100 ORDER BY date DESC, id DESC LIMIT 50"),
            explain("SELECT * FROM messages WHERE chatId = 1 AND threadId IS NULL AND id > 100 ORDER BY date ASC, id ASC LIMIT 50"),
            explain("SELECT * FROM messages WHERE chatId = 1 AND threadId = 42 ORDER BY date DESC, id DESC LIMIT 50"),
            explain("SELECT * FROM messages WHERE chatId = 1 AND threadId = 42 AND id < 100 ORDER BY date DESC, id DESC LIMIT 50"),
            explain("SELECT * FROM messages WHERE chatId = 1 AND threadId = 42 AND id > 100 ORDER BY date ASC, id ASC LIMIT 50")
        )

        plans.forEach { plan ->
            assertNotNull(plan)
            assertTrue(plan, plan.contains("index_messages_chatId_threadId_"))
        }
    }

    private fun explain(sql: String): String = buildString {
        database.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            val detailIndex = cursor.getColumnIndexOrThrow("detail")
            while (cursor.moveToNext()) appendLine(cursor.getString(detailIndex))
        }
    }

    private fun message(
        id: Long,
        threadId: Long? = null,
        content: String = "message-$id",
        isOutgoing: Boolean = false
    ) = MessageEntity(
        id = id,
        chatId = CHAT_ID,
        senderId = 1L,
        content = content,
        date = id.toInt(),
        isOutgoing = isOutgoing,
        isRead = false,
        threadId = threadId
    )

    private fun window(
        scopeType: String = "main",
        scopeId: Long = 0L,
        protectedMessageId: Long? = null
    ) = MessageWindowEntity(
        chatId = CHAT_ID,
        scopeType = scopeType,
        scopeId = scopeId,
        oldestMessageId = 1L,
        newestMessageId = 501L,
        olderBoundaryReached = true,
        newerBoundaryReached = true,
        lastTdlibSyncAt = 1L,
        generation = 1L,
        protectedMessageId = protectedMessageId
    )

    private companion object {
        const val CHAT_ID = 1L
    }
}
