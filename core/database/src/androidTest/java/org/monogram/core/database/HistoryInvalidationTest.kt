package org.monogram.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.monogram.core.database.entity.MessageEntity
import org.monogram.core.database.entity.MessageHoleEntity
import org.monogram.core.database.entity.MetaEntity
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HistoryInvalidationTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var database: MonogramDatabase

    @Before
    fun open() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "history-invalidation-${UUID.randomUUID()}.db"
        database = Room.databaseBuilder(context, MonogramDatabase::class.java, name).build()
    }

    @After
    fun close() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test
    fun invalidationIsScopedToTheAffectedChat() = runBlocking {
        database.messageDao().upsertAll(
            listOf(
                MessageEntity(
                    7,
                    41,
                    null,
                    "stale",
                    100,
                    outgoing = false,
                    mediaCacheKey = null,
                    mediaKind = null
                ),
                MessageEntity(
                    8,
                    42,
                    null,
                    "unaffected",
                    100,
                    outgoing = false,
                    mediaCacheKey = null,
                    mediaKind = null
                ),
            ),
        )
        database.messageHoleDao().insert(MessageHoleEntity(7, 1, 20))
        database.messageHoleDao().insert(MessageHoleEntity(8, 1, 20))
        database.metaDao().upsert(MetaEntity("history_coverage:7", "1"))
        database.metaDao().upsert(MetaEntity("history_coverage:8", "1"))

        OfflineWarmup(database).invalidateCachedHistory(7)

        assertEquals(emptyList<Int>(), database.messageDao().latestForChat(7, 10).map { it.id })
        assertEquals(listOf(42), database.messageDao().latestForChat(8, 10).map { it.id })
        assertEquals(null, database.metaDao().get("history_coverage:7"))
        assertEquals("1", database.metaDao().get("history_coverage:8")?.value)
        assertEquals(
            emptyList<MessageHoleEntity>(),
            database.messageHoleDao().forChat(7, MessageHoleEntity.SOURCE_HISTORY)
        )
        assertEquals(1, database.messageHoleDao().forChat(8, MessageHoleEntity.SOURCE_HISTORY).size)
    }

    @Test
    fun invalidationDropsConfirmedRowsAndCoverageButKeepsPendingSends() = runBlocking {
        val messages = database.messageDao()
        messages.upsertAll(
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
        database.metaDao().upsert(MetaEntity("update_cursor.primary", "1"))
        database.metaDao().upsert(MetaEntity("historyXcoverage:7", "keep"))

        OfflineWarmup(database).invalidateCachedHistories()

        assertEquals(listOf(-9), messages.pendingForChat(7).map { it.id })
        assertEquals(emptyList<Int>(), messages.latestForChat(7, 10).map { it.id })
        assertEquals(
            emptyList<MessageHoleEntity>(),
            database.messageHoleDao().forChat(7, MessageHoleEntity.SOURCE_HISTORY)
        )
        assertEquals(null, database.metaDao().get("history_coverage:7"))
        assertEquals("1", database.metaDao().get("update_cursor.primary")?.value)
        assertEquals("keep", database.metaDao().get("historyXcoverage:7")?.value)
    }
}
