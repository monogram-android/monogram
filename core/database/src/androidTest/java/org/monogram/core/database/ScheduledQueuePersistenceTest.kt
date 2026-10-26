package org.monogram.core.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.monogram.core.database.entity.MessageEntity
import org.monogram.core.database.entity.MetaEntity
import org.monogram.core.database.entity.ScheduledMessageEntity
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ScheduledQueuePersistenceTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var database: MonogramDatabase

    @Before
    fun open() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "queue-test-${UUID.randomUUID()}.db"
        reopen()
    }

    private fun reopen() {
        database = Room.databaseBuilder(context, MonogramDatabase::class.java, name)
            .addMigrations(DatabaseProvider.MIGRATION_9_10).build()
    }

    @After
    fun close() {
        database.close()
        context.deleteDatabase(name)
    }

    private fun message(id: Int = 37, text: String = "scheduled", chatId: Long = 7) = MessageEntity(
        chatId = chatId, id = id, senderId = 7, text = text, date = 100,
        outgoing = true, mediaCacheKey = "attachment", mediaKind = "photo",
        entitiesJson = """[{"kind":"bold","offset":0,"length":3}]""",
        replyToMsgId = 42, replyToTopId = 99, forumTopic = true,
    )

    @Test
    fun overlappingIdsRemainSeparateAndSurviveRestart() = runBlocking {
        val scheduled = ScheduledMessageEntity(message())
        database.metaDao().upsert(MetaEntity("fixture.identity", "7"))
        database.messageDao().upsertAll(listOf(message(text = "history")))
        val dao = database.scheduledMessageDao()
        assertTrue(dao.replaceIfCurrent(7, dao.revision(7), listOf(scheduled)))
        database.close()
        reopen()
        assertEquals(scheduled, database.scheduledMessageDao().forChat(7).single())
        assertEquals("history", database.messageDao().byIds(7, listOf(37)).single().text)
        assertEquals("7", database.metaDao().get("fixture.identity")?.value)
        database.scheduledMessageDao().remove(7, listOf(37))
        assertTrue(database.scheduledMessageDao().forChat(7).isEmpty())
        assertEquals("history", database.messageDao().byIds(7, listOf(37)).single().text)
    }

    @Test
    fun newerEventsDeletionsAndAccountClearingInvalidateInFlightSnapshots() = runBlocking {
        val dao = database.scheduledMessageDao()
        val beforeEvent = dao.revision(7)
        dao.applyMessages(7, listOf(ScheduledMessageEntity(message(text = "new event"))))
        assertFalse(dao.replaceIfCurrent(7, beforeEvent, listOf(ScheduledMessageEntity(message()))))
        val beforeDelete = dao.revision(7)
        dao.remove(7, listOf(37))
        assertFalse(
            dao.replaceIfCurrent(
                7,
                beforeDelete,
                listOf(ScheduledMessageEntity(message()))
            )
        )
        val beforeClear = dao.revision(8)
        OfflineWarmup(database).clearAccountCache()
        assertFalse(
            dao.replaceIfCurrent(
                8,
                beforeClear,
                listOf(ScheduledMessageEntity(message(chatId = 8)))
            )
        )
        assertTrue(dao.forChat(7).isEmpty())
        assertTrue(dao.forChat(8).isEmpty())
    }

    @Test
    fun eventRowsAndRevisionRollbackTogetherAndRejectForeignOrPendingIds() = runBlocking {
        val dao = database.scheduledMessageDao()
        val revision = dao.revision(7)
        try {
            database.withTransaction {
                dao.applyMessages(7, listOf(ScheduledMessageEntity(message())))
                error("synthetic rollback")
            }
        } catch (_: IllegalStateException) {
        }
        assertEquals(revision, dao.revision(7))
        assertTrue(dao.forChat(7).isEmpty())
        for (invalid in listOf(
            message(id = -1),
            message(chatId = 8),
            message().copy(pending = true)
        )) {
            try {
                dao.applyMessages(7, listOf(ScheduledMessageEntity(invalid)))
                fail("invalid queue identity accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(revision, dao.revision(7))
    }

    @Test
    fun durableQueueEventsAndReceiptsCommitTogetherWithoutTouchingHistory() = runBlocking {
        val warmup = OfflineWarmup(database)
        val dao = database.scheduledMessageDao()
        database.messageDao().upsertAll(listOf(message(text = "history")))
        val scheduled = org.monogram.core.models.ScheduledMessage(message().toModel())
        assertTrue(warmup.applyDurableBatch(5, 8, null, emptyMap()) {
            warmup.applyScheduledMessage(scheduled)
        })
        assertTrue(
            warmup.applyDurableBatch(
                5,
                8,
                null,
                emptyMap()
            ) { error("replay must not apply twice") })
        val revision = dao.revision(7)
        try {
            warmup.applyDurableBatch(5, 9, null, emptyMap()) {
                warmup.deleteScheduledMessages(org.monogram.core.models.PeerId(7), listOf(37))
                error("synthetic receipt rollback")
            }
        } catch (_: IllegalStateException) {
        }
        assertEquals(revision, dao.revision(7))
        assertEquals(37, dao.forChat(7).single().message.id)
        assertTrue(warmup.applyDurableBatch(5, 9, null, emptyMap()) {
            warmup.deleteScheduledMessages(org.monogram.core.models.PeerId(7), listOf(37))
        })
        assertTrue(dao.forChat(7).isEmpty())
        assertEquals("history", database.messageDao().byIds(7, listOf(37)).single().text)
    }

    @Test
    fun authoritativeOutgoingModesReconcileByRandomIdAcrossOverlappingNamespaces() = runBlocking {
        val warmup = OfflineWarmup(database)
        database.messageDao().upsertAll(
            listOf(
                message(text = "old history"),
                message(id = -37, text = "pending").copy(pending = true, randomId = 123),
            )
        )
        val queued = org.monogram.core.models.OutgoingMessageResult.Scheduled(
            org.monogram.core.models.ScheduledMessage(message(text = "queued").toModel()),
        )
        warmup.applyDurableBatch(5, 10, null, emptyMap()) {
            warmup.applyOutgoingResult(
                456,
                queued
            )
        }
        assertEquals(456L, database.scheduledMessageDao().forChat(7).single().message.randomId)
        assertEquals("old history", database.messageDao().byIds(7, listOf(37)).single().text)
        assertEquals(-37, database.messageDao().pendingByRandomId(7, 123).single().id)
        val sent =
            org.monogram.core.models.OutgoingMessageResult.Sent(message(text = "delivered").toModel())
        warmup.applyDurableBatch(5, 11, null, emptyMap()) { warmup.applyOutgoingResult(123, sent) }
        database.close()
        reopen()
        assertTrue(database.messageDao().pendingByRandomId(7, 123).isEmpty())
        assertEquals("delivered", database.messageDao().byIds(7, listOf(37)).single().text)
        assertEquals("queued", database.scheduledMessageDao().forChat(7).single().message.text)
        assertTrue(
            OfflineWarmup(database).applyDurableBatch(
                5,
                11,
                null,
                emptyMap()
            ) { error("receipt replay") })
    }

    private fun legacyDatabase(version: Int, populate: (SQLiteDatabase) -> Unit) {
        database.close()
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("org.monogram.core.database.MonogramDatabase/$version.json").bufferedReader()
            .use { JSONObject(it.readText()) }
            .getJSONObject("database")
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) old.execSQL(
                    indices.getJSONObject(
                        j
                    ).getString("createSql").replace("\${TABLE_NAME}", table)
                )
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            populate(old)
            old.version = version
        }
        reopen()
    }

    @Test
    fun unsupportedFlagSurvivesCacheReopenAndSnapshotReplacement() = runBlocking {
        val peer = org.monogram.core.models.PeerId(7)
        val known = org.monogram.core.models.ScheduledMessage(
            message().toModel(),
            hasUnsupportedContent = false
        )
        OfflineWarmup(database).applyScheduledMessage(known)
        database.close(); reopen()
        val warmup = OfflineWarmup(database)
        assertFalse(warmup.observeScheduledMessages(peer).first().single().hasUnsupportedContent)
        val epoch = warmup.outgoingAccountEpoch()
        val revision = warmup.scheduledQueueRevision(peer, epoch)
        assertTrue(
            warmup.replaceScheduledMessagesIfCurrent(
                peer, revision,
                listOf(known.copy(hasUnsupportedContent = true)), epoch
            )
        )
        assertTrue(warmup.observeScheduledMessages(peer).first().single().hasUnsupportedContent)
    }

    @Test
    fun versionNineMigrationPreservesIdentityHistoryAndPendingRandomIds() = runBlocking {
        legacyDatabase(9) { old ->
            old.execSQL(
                "INSERT INTO meta (`key`, value) VALUES (?, ?)",
                arrayOf(SessionMetadataStore.KEY_USER_ID, "7")
            )
            old.execSQL(
                "INSERT INTO meta (`key`, value) VALUES (?, ?)",
                arrayOf(SessionMetadataStore.KEY_AUTHORIZED, "1")
            )
            val schema = InstrumentationRegistry.getInstrumentation().context.assets
                .open("org.monogram.core.database.MonogramDatabase/9.json").bufferedReader()
                .use { JSONObject(it.readText()) }.getJSONObject("database")
                .getJSONArray("entities")
            val chat = (0 until schema.length()).map { schema.getJSONObject(it) }
                .first { it.getString("tableName") == "chats" }
            val fields = chat.getJSONArray("fields")
            val values = ContentValues()
            for (i in 0 until fields.length()) {
                val field = fields.getJSONObject(i)
                if (field.optBoolean("notNull", false)) {
                    val column = field.getString("columnName")
                    if (field.getString("affinity") == "INTEGER") values.put(column, 0)
                    else values.put(column, "")
                }
            }
            values.put("id", 7L)
            values.put("title", "Fixture")
            values.put("canSendPlain", 1)
            old.insertOrThrow("chats", null, values)
            old.insertOrThrow("messages", null, ContentValues().apply {
                put("chatId", 7L); put("id", -37); put("text", "pending fixture"); put("date", 100L)
                put("outgoing", 1); put("supportsStreaming", 0); put("repliesCount", 0)
                put("forumTopic", 0); put("pending", 1); put("randomId", 123456789L)
            })
        }
        assertEquals("7", database.metaDao().get(SessionMetadataStore.KEY_USER_ID)?.value)
        assertEquals("1", database.metaDao().get(SessionMetadataStore.KEY_AUTHORIZED)?.value)
        assertFalse(database.chatDao().get(7)!!.canSendPolls)
        assertTrue(database.chatDao().get(7)!!.canSendPlain)
        assertTrue(database.outgoingTextSendDao().all().isEmpty())
        assertTrue(database.outgoingMediaSendDao().all().isEmpty())
        val pending = database.messageDao().byIds(7, listOf(-37)).single()
        assertTrue(pending.pending)
        assertEquals(123456789L, pending.randomId)
        assertTrue(database.scheduledMessageDao().forChat(7).isEmpty())
        val migratedQueue = database.scheduledMessageDao()
        assertTrue(migratedQueue.replaceIfCurrent(7, 0, listOf(ScheduledMessageEntity(message()))))
        assertEquals(DatabaseProvider.SCHEMA_VERSION, database.openHelper.readableDatabase.version)
    }
}
