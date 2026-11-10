package org.monogram.root

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.monogram.core.database.MonogramDatabase
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.entity.MessageEntity
import org.monogram.core.database.entity.MessageHoleEntity
import org.monogram.core.database.entity.MetaEntity
import org.monogram.network.bridge.DurableUpdateBatch
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.bridge.UpdatesCursor
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AccountDurableUpdateSinkTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var database: MonogramDatabase

    @Before
    fun open() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "account-sink-${UUID.randomUUID()}.db"
        database = Room.databaseBuilder(context, MonogramDatabase::class.java, name).build()
    }

    @After
    fun close() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test
    fun channelTooLongInvalidatesOnlyItsChatAndCommitsChannelCursor() = runBlocking {
        database.messageDao().upsertAll(
            listOf(
                MessageEntity(
                    -10042,
                    41,
                    null,
                    "stale",
                    100,
                    outgoing = false,
                    mediaCacheKey = null,
                    mediaKind = null
                ),
                MessageEntity(
                    -10043,
                    42,
                    null,
                    "fresh",
                    100,
                    outgoing = false,
                    mediaCacheKey = null,
                    mediaKind = null
                ),
            ),
        )
        database.metaDao().upsert(MetaEntity("history_coverage:-10042", "1"))
        database.metaDao().upsert(MetaEntity("history_coverage:-10043", "1"))
        val batch = DurableUpdateBatch(
            accountGeneration = 4,
            batchId = 10,
            updates = listOf(MtprotoUpdate.Ignored("channel_difference_too_long:-10042")),
            cursor = null,
            channelCursors = mapOf(-10042L to 40),
        )

        assertTrue(AccountDurableUpdateSink(OfflineWarmup(database)).commit(batch))

        assertEquals(
            emptyList<Int>(),
            database.messageDao().latestForChat(-10042, 10).map { it.id })
        assertEquals(listOf(42), database.messageDao().latestForChat(-10043, 10).map { it.id })
        assertEquals(null, database.metaDao().get("history_coverage:-10042"))
        assertEquals("1", database.metaDao().get("history_coverage:-10043")?.value)
        assertEquals(40, database.updateCursorDao().get("updates.channel:-10042")?.channelPts)
    }

    @Test
    fun malformedChannelRecoveryMarkerRollsBackBatch() = runBlocking {
        database.messageDao().upsertAll(
            listOf(
                MessageEntity(
                    -10042,
                    41,
                    null,
                    "cached",
                    100,
                    outgoing = false,
                    mediaCacheKey = null,
                    mediaKind = null
                )
            ),
        )
        val batch = DurableUpdateBatch(
            accountGeneration = 4,
            batchId = 11,
            updates = listOf(
                MtprotoUpdate.Ignored("channel_difference_too_long:-10042"),
                MtprotoUpdate.Ignored("channel_difference_too_long:invalid"),
            ),
            cursor = null,
            channelCursors = mapOf(-10042L to 40),
        )

        val failure =
            runCatching { AccountDurableUpdateSink(OfflineWarmup(database)).commit(batch) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(listOf(41), database.messageDao().latestForChat(-10042, 10).map { it.id })
        assertEquals(null, database.updateCursorDao().get("updates.channel:-10042"))
        assertTrue(!database.updateCursorDao().isBatchApplied(4, 11))
    }

    @Test
    fun differenceTooLongCommitsCursorAndInvalidatesHistoryIdempotently() = runBlocking {
        database.messageDao().upsertAll(
            listOf(
                MessageEntity(
                    7,
                    41,
                    null,
                    "cached",
                    100,
                    outgoing = false,
                    mediaCacheKey = null,
                    mediaKind = null
                ),
                MessageEntity(
                    7,
                    -9,
                    7,
                    "pending",
                    101,
                    outgoing = true,
                    mediaCacheKey = null,
                    mediaKind = null,
                    pending = true,
                    randomId = 90
                ),
            ),
        )
        database.messageHoleDao().insert(MessageHoleEntity(7, 1, 20))
        database.metaDao().upsert(MetaEntity("history_coverage:7", "1"))
        val batch = DurableUpdateBatch(
            accountGeneration = 4,
            batchId = 9,
            updates = listOf(MtprotoUpdate.Ignored("difference_too_long")),
            cursor = UpdatesCursor(pts = 12, qts = 3, date = 100, seq = 8),
            channelCursors = emptyMap(),
        )

        val sink = AccountDurableUpdateSink(OfflineWarmup(database))
        assertTrue(sink.commit(batch))
        assertTrue(sink.commit(batch))

        assertEquals(listOf(-9), database.messageDao().pendingForChat(7).map { it.id })
        assertEquals(emptyList<Int>(), database.messageDao().latestForChat(7, 10).map { it.id })
        assertEquals(
            emptyList<MessageHoleEntity>(),
            database.messageHoleDao().forChat(7, MessageHoleEntity.SOURCE_HISTORY)
        )
        assertEquals(null, database.metaDao().get("history_coverage:7"))
        val cursor = database.updateCursorDao().get("updates.primary")
        assertEquals(12, cursor?.pts)
        assertEquals(3, cursor?.qts)
        assertEquals(100, cursor?.date)
        assertEquals(8, cursor?.seq)
    }
}
