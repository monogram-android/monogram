package org.monogram.core.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.monogram.core.database.entity.MetaEntity
import org.monogram.core.models.AuthSession
import org.monogram.core.models.Chat
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import java.io.File
import java.util.UUID

class AccountCacheIsolationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val firstId = UUID.randomUUID().mostSignificantBits.and(Long.MAX_VALUE).coerceAtLeast(2)
    private val secondId = firstId xor 1L
    private val peer = PeerId(7)
    private lateinit var first: OfflineWarmup
    private lateinit var second: OfflineWarmup

    @Before
    fun open() {
        first = OfflineWarmup(DatabaseProvider.get(context, firstId))
        second = OfflineWarmup(DatabaseProvider.get(context, secondId))
    }

    @After
    fun cleanup() {
        for (id in listOf(firstId, secondId)) {
            DatabaseProvider.close(id)
            context.deleteDatabase("monogram-account-$id.db")
            File(context.filesDir, "session-identity-$id.json").delete()
        }
    }

    private suspend fun seed(id: Long, cache: OfflineWarmup, label: String) {
        cache.upsertChats(listOf(Chat(peer, "chat-$label")))
        val message = Message(
            MessageId(peer, 41),
            peer,
            "message-$label",
            System.currentTimeMillis() / 1000,
            outgoing = false
        )
        cache.replaceMessages(peer, listOf(message))
        cache.recentHistory.remember(peer.value, listOf(message))
        cache.setDraft(peer, text = "draft-$label")
        val database = DatabaseProvider.get(context, id)
        SessionMetadataStore(database).upsertProfile(Profile(peer, "user", "user-$label"))
        database.metaDao().upsert(MetaEntity("update_cursor.primary", label))
    }

    private suspend fun assertCached(id: Long, cache: OfflineWarmup, label: String) {
        assertEquals("chat-$label", cache.chat(peer)?.title)
        assertEquals("message-$label", cache.messages(peer).single().text)
        assertEquals("draft-$label", cache.draft(peer))
        val database = DatabaseProvider.get(context, id)
        assertEquals("user-$label", SessionMetadataStore(database).readProfile(peer.value)?.title)
        assertEquals(label, database.metaDao().get("update_cursor.primary")?.value)
    }

    @Test
    fun concurrentAccountLookupsPublishOneDatabasePerAccount() = runBlocking {
        DatabaseProvider.close(firstId)
        DatabaseProvider.close(secondId)
        val start = CompletableDeferred<Unit>()
        val lookups = (0 until 16).map { index ->
            async(Dispatchers.IO) {
                start.await()
                val id = if (index % 2 == 0) firstId else secondId
                id to DatabaseProvider.get(context, id)
            }
        }
        start.complete(Unit)
        val results = lookups.awaitAll()
        for ((id, database) in results) {
            assertSame(DatabaseProvider.get(context, id), database)
        }
        assertNotSame(results[0].second, results[1].second)
    }

    @Test
    fun identicalIdsRemainIsolatedInMemoryAndAfterReopening() = runBlocking {
        assertNotSame(
            DatabaseProvider.get(context, firstId),
            DatabaseProvider.get(context, secondId)
        )
        assertNotSame(first.recentHistory, second.recentHistory)
        seed(firstId, first, "first")
        seed(secondId, second, "second")
        assertCached(firstId, first, "first")
        assertCached(secondId, second, "second")
        assertEquals("message-first", first.recentHistory.peek(peer.value)?.single()?.text)
        assertEquals("message-second", second.recentHistory.peek(peer.value)?.single()?.text)

        DatabaseProvider.close(firstId)
        DatabaseProvider.close(secondId)
        first = OfflineWarmup(DatabaseProvider.get(context, firstId))
        second = OfflineWarmup(DatabaseProvider.get(context, secondId))
        assertCached(firstId, first, "first")
        assertCached(secondId, second, "second")
    }

    @Test
    fun staleIdentityBackupCannotReplaceExistingAuthorization() = runBlocking {
        var store = SessionMetadataStore(DatabaseProvider.get(context, firstId))
        store.saveAuthorized(AuthSession(PeerId(firstId), 2))
        DatabaseProvider.close(firstId)
        val backup = File(context.filesDir, "session-identity-$firstId.json")
        val stale = """{"session.user_id":"42","session.dc_id":"4","session.authorized":"1"}"""
        backup.writeText(stale)

        store = SessionMetadataStore(DatabaseProvider.get(context, firstId))
        assertEquals(firstId, store.readAuthorizedUserId()?.value)
        assertEquals("2", store.readMeta(SessionMetadataStore.KEY_DC_ID))
        store.clearAuthorized()
        DatabaseProvider.close(firstId)
        backup.writeText(stale)
        store = SessionMetadataStore(DatabaseProvider.get(context, firstId))
        assertEquals(false, store.isAuthorized())
        assertEquals(null, store.readAuthorizedUserId())

        store.saveAuthorized(AuthSession(PeerId(firstId), 2))
        store.clearSession()
        DatabaseProvider.close(firstId)
        backup.writeText(stale)
        store = SessionMetadataStore(DatabaseProvider.get(context, firstId))
        assertEquals(false, store.isAuthorized())
        assertEquals(null, store.readAuthorizedUserId())
    }

    @Test
    fun unsupportedFutureSchemaDoesNotDeleteAccountData() {
        DatabaseProvider.close(firstId)
        val path = context.getDatabasePath("monogram-account-$firstId.db")
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE)
            .use { database ->
                database.execSQL("CREATE TABLE preserve_marker (value TEXT NOT NULL)")
                database.execSQL("INSERT INTO preserve_marker VALUES ('pending')")
                database.version = DatabaseProvider.SCHEMA_VERSION + 1
            }

        assertNotNull(runCatching { DatabaseProvider.get(context, firstId) }.exceptionOrNull())
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT value FROM preserve_marker", null).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("pending", cursor.getString(0))
            }
        }
    }

    @Test
    fun clearingOneAccountLeavesOtherAccountCachesIntact() = runBlocking {
        seed(firstId, first, "first")
        seed(secondId, second, "second")
        first.clearAccountCache()
        assertEquals(emptyList<Chat>(), first.chats())
        assertEquals(emptyList<Message>(), first.messages(peer))
        assertEquals(null, first.recentHistory.peek(peer.value))
        assertCached(secondId, second, "second")
        assertEquals("message-second", second.recentHistory.peek(peer.value)?.single()?.text)
    }
}
