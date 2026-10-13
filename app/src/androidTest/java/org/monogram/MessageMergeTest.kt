package org.monogram

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.monogram.core.database.DatabaseProvider
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.database.MonogramDatabase
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.toModel
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId

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
}
