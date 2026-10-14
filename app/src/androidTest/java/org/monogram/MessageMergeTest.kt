package org.monogram

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.monogram.core.database.DatabaseProvider
import org.monogram.core.database.MonogramDatabase
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.database.toModel
import org.monogram.core.models.Chat
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MessageMergeTest {
    @get:Rule
    val migration = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MonogramDatabase::class.java
    )

    @Test
    fun versionFiveMigrationPreservesIdentity() {
        val name = "merge-migration-regression.db"
        migration.createDatabase(name, 5).use { db ->
            db.execSQL("INSERT INTO meta (`key`, value) VALUES ('${SessionMetadataStore.KEY_USER_ID}', '42')")
        }
        migration.runMigrationsAndValidate(
            name,
            7,
            true,
            DatabaseProvider.MIGRATION_5_6,
            DatabaseProvider.MIGRATION_6_7,
        ).use { db ->
            db.query("SELECT value FROM meta WHERE `key` = '${SessionMetadataStore.KEY_USER_ID}'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("42", cursor.getString(0))
                }
        }
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name)
    }

    @Test
    fun startupRemovesFailedAndUntrackedRowsButKeepsRetryableSends() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "startup-message-cleanup.db"
        context.deleteDatabase(name)
        var database = Room.databaseBuilder(context, MonogramDatabase::class.java, name).build()
        val chatId = PeerId(42)
        val pending = Message(
            MessageId(chatId, -1),
            senderId = null,
            text = "fixture",
            date = 1L,
            outgoing = true,
            pending = true,
            randomId = 99L
        )
        try {
            val warmup = OfflineWarmup(database)
            warmup.upsertMessages(
                listOf(
                    pending,
                    pending.copy(id = MessageId(chatId, -2), randomId = 100L),
                    pending.copy(id = MessageId(chatId, -3), randomId = null),
                )
            )
            warmup.markMessageFailed(chatId, -2)
            assertTrue(
                database.messageDao().byIds(chatId.value, listOf(-2)).single().toModel().failed
            )
            database.close()
            database = Room.databaseBuilder(context, MonogramDatabase::class.java, name).build()
            val restored = OfflineWarmup(database).messages(chatId)
            assertEquals(listOf(-1), restored.map { it.id.id })
            assertEquals(99L, restored.single().randomId)
            assertTrue(restored.single().pending)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun globalDeletionBlocksNonChannelHistoryButPreservesChannelIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MonogramDatabase::class.java).build()
        val user = Message(
            MessageId(PeerId(42), 7),
            senderId = null,
            text = "fixture",
            date = 1L,
            outgoing = false
        )
        val channel = user.copy(id = MessageId(PeerId(-1000000000042), 7))
        try {
            val warmup = OfflineWarmup(database)
            warmup.upsertMessages(listOf(user, channel))
            warmup.deleteMessages(null, listOf(7))
            warmup.cacheHistoryPage(user.id.chatId, listOf(user))
            warmup.cacheHistoryPage(channel.id.chatId, listOf(channel))
            assertTrue(warmup.messages(user.id.chatId).isEmpty())
            assertEquals(listOf(channel), warmup.messages(channel.id.chatId))
        } finally {
            database.close()
        }
    }

    @Test
    fun localIdCanBeReusedAfterPendingReconciliation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MonogramDatabase::class.java).build()
        val pending = Message(
            MessageId(PeerId(42), -1),
            senderId = null,
            text = "fixture",
            date = 1L,
            outgoing = true,
            pending = true,
            randomId = 99L
        )
        try {
            val warmup = OfflineWarmup(database)
            warmup.upsertMessages(listOf(pending))
            warmup.deleteMessage(pending.id.chatId, pending.id.id)
            val next = pending.copy(randomId = 100L)
            warmup.upsertMessages(listOf(next))
            assertEquals(listOf(next), warmup.messages(pending.id.chatId))
        } finally {
            database.close()
        }
    }

    @Test
    fun reconciliationRollsBackOnWriteFailureAndDoesNotDeleteAReusedLocalId() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MonogramDatabase::class.java).build()
        val pending = Message(
            MessageId(PeerId(42), -1),
            senderId = null,
            text = "fixture",
            date = 1L,
            outgoing = true,
            pending = true,
            randomId = 99L
        )
        val confirmed = pending.copy(id = MessageId(pending.id.chatId, 7), pending = false)
        try {
            val warmup = OfflineWarmup(database)
            warmup.upsertMessages(listOf(pending))
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_confirmation BEFORE INSERT ON messages WHEN NEW.id = 7 BEGIN SELECT RAISE(ABORT, 'fixture failure'); END"
            )
            var failed = false
            try {
                warmup.reconcileOutgoing(-1, confirmed, 99L)
            } catch (_: android.database.sqlite.SQLiteException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(listOf(pending), warmup.messages(pending.id.chatId))
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_confirmation")
            warmup.reconcileOutgoing(-1, confirmed, 99L)
            assertEquals(listOf(confirmed), warmup.messages(pending.id.chatId))
            val next = pending.copy(randomId = 100L)
            warmup.upsertMessages(listOf(next))
            warmup.reconcileOutgoing(-1, confirmed, 99L)
            assertEquals(100L, warmup.messages(pending.id.chatId).single { it.pending }.randomId)
        } finally {
            database.close()
        }
    }

    @Test
    fun staleHistoryCannotReplaceEditOrResurrectDeletion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "message-merge-regression.db"
        context.deleteDatabase(name)
        var database = Room.databaseBuilder(context, MonogramDatabase::class.java, name).build()
        val message = Message(
            MessageId(PeerId(42), 7),
            senderId = null,
            text = "original",
            date = 1L,
            outgoing = false
        )
        try {
            var warmup = OfflineWarmup(database)
            warmup.applyIncomingMessage(message)
            warmup.applyIncomingMessage(message)
            assertEquals(1, warmup.chats().single().unreadCount)
            warmup.applyIncomingMessage(
                message.copy(
                    id = MessageId(message.id.chatId, 6),
                    text = "older"
                )
            )
            assertEquals(2, warmup.chats().single().unreadCount)
            assertEquals(7, warmup.chats().single().lastMessageId)
            warmup.deleteMessages(message.id.chatId, listOf(6))
            warmup.applyMessageEdit(message.copy(text = "edited", editDate = 3L))
            warmup.upsertMessages(listOf(message))
            assertEquals("edited", warmup.messages(message.id.chatId).single().text)
            warmup.deleteMessages(message.id.chatId, listOf(message.id.id))
            database.close()
            database = Room.databaseBuilder(context, MonogramDatabase::class.java, name).build()
            warmup = OfflineWarmup(database)
            warmup.upsertMessages(listOf(message))
            warmup.applyIncomingMessage(message)
            assertTrue(warmup.messages(message.id.chatId).isEmpty())
            assertEquals(null, warmup.chats().single().lastMessagePreview)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionSevenMigrationAddsReceiptsAndPreservesIdentity() {
        val name = "receipt-migration-regression.db"
        migration.createDatabase(name, 7).use { db ->
            db.execSQL("INSERT INTO meta (`key`, value) VALUES ('${SessionMetadataStore.KEY_USER_ID}', '42')")
        }
        migration.runMigrationsAndValidate(name, 8, true, DatabaseProvider.MIGRATION_7_8)
            .use { db ->
                db.query("SELECT value FROM meta WHERE `key` = '${SessionMetadataStore.KEY_USER_ID}'")
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("42", cursor.getString(0))
                    }
                db.execSQL("INSERT INTO update_batch_receipts VALUES (1, 1, 1)")
            }
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name)
    }

    private fun durableMessage(id: Int) = Message(
        MessageId(PeerId(42), id),
        senderId = null,
        text = "fixture",
        date = id.toLong(),
        outgoing = false,
    )

    private fun cursor(pts: Int) = org.monogram.core.database.entity.UpdateCursorEntity(
        SessionMetadataStore.KEY_PRIMARY_CURSOR, pts, 0, 1, 1,
    )

    @Test
    fun durableBatchCommitsMessagesCursorAndReceiptTogether() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MonogramDatabase::class.java).build()
        try {
            val warmup = OfflineWarmup(database)
            val committed = warmup.applyDurableBatch(7L, 1L, cursor(5), mapOf(9L to 3)) {
                warmup.applyIncomingMessage(durableMessage(5))
            }
            assertTrue(committed)
            assertEquals(listOf(5), warmup.messages(PeerId(42)).map { it.id.id })
            assertEquals(
                5,
                database.updateCursorDao().get(SessionMetadataStore.KEY_PRIMARY_CURSOR)?.pts
            )
            assertEquals(3, database.updateCursorDao().get("updates.channel:9")?.channelPts)
            assertTrue(database.updateCursorDao().isBatchApplied(7L, 1L))
        } finally {
            database.close()
        }
    }

    @Test
    fun failureBeforeTheCommitLeavesNoReceiptCursorOrPartialRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MonogramDatabase::class.java).build()
        try {
            val warmup = OfflineWarmup(database)
            val failure = runCatching {
                warmup.applyDurableBatch(7L, 1L, cursor(5), emptyMap()) {
                    warmup.applyIncomingMessage(durableMessage(5))
                    error("process died before the commit")
                }
            }
            assertTrue(failure.isFailure)
            assertTrue(warmup.messages(PeerId(42)).isEmpty())
            assertEquals(
                null,
                database.updateCursorDao().get(SessionMetadataStore.KEY_PRIMARY_CURSOR)
            )
            assertTrue(!database.updateCursorDao().isBatchApplied(7L, 1L))

            var applied = 0
            val retried = warmup.applyDurableBatch(7L, 1L, cursor(5), emptyMap()) {
                applied++
                warmup.applyIncomingMessage(durableMessage(5))
            }
            assertTrue(retried)
            assertEquals(1, applied)
            assertEquals(1, warmup.chats().single().unreadCount)
        } finally {
            database.close()
        }
    }

    @Test
    fun duplicateDeliveryAfterCommitButBeforeAcknowledgementChangesNothing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MonogramDatabase::class.java).build()
        try {
            val warmup = OfflineWarmup(database)
            repeat(3) {
                assertTrue(
                    warmup.applyDurableBatch(7L, 1L, cursor(5), emptyMap()) {
                        warmup.applyIncomingMessage(durableMessage(5))
                    }
                )
            }
            assertEquals(1, warmup.chats().single().unreadCount)
            assertEquals(1, warmup.messages(PeerId(42)).size)
        } finally {
            database.close()
        }
    }

    @Test
    fun receiptsOfEarlierAccountsAreDroppedAndChannelCursorsNeverMoveBack() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MonogramDatabase::class.java).build()
        try {
            val warmup = OfflineWarmup(database)
            warmup.applyDurableBatch(1L, 1L, null, mapOf(9L to 10)) {}
            warmup.applyDurableBatch(2L, 1L, null, mapOf(9L to 4)) {}
            assertTrue(!database.updateCursorDao().isBatchApplied(1L, 1L))
            assertTrue(database.updateCursorDao().isBatchApplied(2L, 1L))
            assertEquals(10, database.updateCursorDao().get("updates.channel:9")?.channelPts)
        } finally {
            database.close()
        }
    }

    @Test
    fun durableBatchAndChatUpsertDoNotDeadlockTheRoomWriter() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val warmup = OfflineWarmup(DatabaseProvider.get(context))
        val chatId = PeerId(910_001)
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        Thread {
            try {
                runBlocking {
                    withContext(Dispatchers.Default) {
                        assertTrue(
                            warmup.applyDurableBatch(8L, 91L, cursor(91), emptyMap()) {
                                warmup.applyIncomingMessage(
                                    durableMessage(91).copy(id = MessageId(chatId, 91)),
                                )
                            },
                        )
                        warmup.upsertChats(listOf(Chat(id = chatId, title = "room-writer")))
                    }
                }
                done.countDown()
            } catch (thrown: Throwable) {
                error.set(thrown)
                done.countDown()
            }
        }.apply { isDaemon = true }.start()
        assertTrue(done.await(8, TimeUnit.SECONDS))
        assertNull(error.get())
        runBlocking { warmup.deleteMessages(chatId, listOf(91)) }
    }
}
