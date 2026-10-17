package org.monogram.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.monogram.core.database.entity.MessageHoleEntity
import org.monogram.core.database.entity.MessageTombstoneEntity
import org.monogram.core.database.entity.MetaEntity
import org.monogram.core.database.entity.PeerEntity
import org.monogram.core.database.entity.ProfileCommonEntity
import org.monogram.core.database.entity.ProfileMediaEntity
import org.monogram.core.database.entity.ProfileMemberEntity
import org.monogram.core.database.entity.ProfileTabsEntity
import org.monogram.core.database.entity.UpdateCursorEntity
import org.monogram.core.models.Chat
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId

@RunWith(AndroidJUnit4::class)
class CacheTtlRoomTest {
    @Test
    fun pruneKeepsTheRecentPageAndLeavesCursorsAlone() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MonogramDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val now = 1_800_000_000_000L
        val old = now / 1000 - CACHE_MESSAGE_TTL_SECONDS - 60
        val recent = now / 1000 - 3_600
        val warmup = OfflineWarmup(database)
        try {
            val kept = (1..121).map { id ->
                message(7, id, if (id == 121) recent else old)
            }
            val pending = message(7, -1, old, pending = true)
            val failed = message(7, 200, old, failed = true)
            val openChat = (1..30).map { id -> message(9, id, old) }
            run {
                database.messageDao()
                    .upsertAll((kept + pending + failed + openChat).map { it.toEntity() })
                database.messageHoleDao().insert(MessageHoleEntity(7L, 1, Int.MAX_VALUE))
                database.messageTombstoneDao().insert(
                    listOf(MessageTombstoneEntity(7L, 3), MessageTombstoneEntity(7L, 50)),
                )
                database.peerDao().upsertAll(
                    listOf(PeerEntity(7L, "user", "Kept"), PeerEntity(99L, "user", "Stray")),
                )
                repeat(503) { index ->
                    database.metaDao().upsert(MetaEntity("discussion_read:7:${index + 1}", "1"))
                }
                database.updateCursorDao().upsert(UpdateCursorEntity("updates", 4, 5, 6, 7, 0))
                database.updateCursorDao().upsert(
                    UpdateCursorEntity("$KEY_CHANNEL_CURSOR_PREFIX" + "1", 1, 0, 2, 0, 9),
                )
                database.profileTabsDao().upsert(ProfileTabsEntity(1L, "{}", 0L))
                database.profileTabsDao().upsert(ProfileTabsEntity(2L, "{}", now))
                database.profileMembersDao().upsertAll(
                    listOf(
                        ProfileMemberEntity(1L, 5L, 0, "Old", role = "member"),
                        ProfileMemberEntity(2L, 6L, 0, "New", role = "member"),
                    ),
                )
                database.profileMediaDao().upsertAll(
                    listOf(
                        ProfileMediaEntity(1L, "photo", 1, 0, 7L),
                        ProfileMediaEntity(2L, "photo", 1, 0, 7L),
                    ),
                )
                database.profileCommonDao().upsertAll(
                    listOf(
                        ProfileCommonEntity(1L, 3L, 0, "Old"),
                        ProfileCommonEntity(2L, 4L, 0, "New"),
                    ),
                )
            }
            warmup.upsertChats(listOf(Chat(id = PeerId(7), title = "Kept")))
            warmup.setOpenChat(9L)
            warmup.cacheHistoryPage(PeerId(7), listOf(message(7, 121, recent)))
            warmup.pruneExpiredCache(nowMillis = now, force = true)
            run {
                val dao = database.messageDao()
                assertNull(dao.byIds(7L, listOf(1)).singleOrNull())
                assertNotNull(dao.byIds(7L, listOf(22)).singleOrNull())
                assertNotNull(dao.byIds(7L, listOf(121)).singleOrNull())
                assertTrue(dao.byIds(7L, listOf(-1)).single().pending)
                assertTrue(dao.byIds(7L, listOf(200)).single().failed)
                assertEquals(30, dao.newestConfirmedIds(9L, 100).size)
                assertEquals("Kept", database.chatDao().get(7L)?.title)
                val account = database.updateCursorDao().get("updates")
                assertEquals(4, account?.pts)
                assertEquals(5, account?.qts)
                assertEquals(6, account?.date)
                assertEquals(7, account?.seq)
                val channel = database.updateCursorDao().get("$KEY_CHANNEL_CURSOR_PREFIX" + "1")
                assertEquals(9, channel?.channelPts)
                assertNull(database.profileTabsDao().get(1L))
                assertNotNull(database.profileTabsDao().get(2L))
                assertTrue(database.profileMembersDao().list(1L).isEmpty())
                assertEquals(1, database.profileMembersDao().list(2L).size)
                assertTrue(database.profileMediaDao().list(1L).isEmpty())
                assertEquals(1, database.profileMediaDao().list(2L).size)
                assertTrue(database.profileCommonDao().list(1L).isEmpty())
                assertEquals(1, database.profileCommonDao().list(2L).size)
                assertTrue(database.messageTombstoneDao().deletedIds(7L, listOf(3)).isEmpty())
                assertEquals(listOf(50), database.messageTombstoneDao().deletedIds(7L, listOf(50)))
                assertNotNull(database.peerDao().get(7L))
                assertNull(database.peerDao().get(99L))
                assertNull(database.metaDao().get("discussion_read:7:1"))
                assertNotNull(database.metaDao().get("discussion_read:7:503"))
                val holes = database.messageHoleDao().forChat(7L, MessageHoleEntity.SOURCE_HISTORY)
                assertTrue(holes.any { 1 in it.startId..it.endId })
                assertTrue(holes.none { hole -> (22..121).any { it in hole.startId..hole.endId } })
                assertFalse(holes.any { 121 in it.startId..it.endId })
            }
        } finally {
            database.close()
        }
    }

    private fun message(
        chatId: Long,
        id: Int,
        date: Long,
        pending: Boolean = false,
        failed: Boolean = false,
    ) = Message(
        id = MessageId(PeerId(chatId), id),
        senderId = null,
        text = null,
        date = date,
        outgoing = pending || failed,
        pending = pending,
        failed = failed,
        randomId = if (pending) 99L else null,
    )
}
