package org.monogram.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.monogram.core.models.AuthSession
import org.monogram.core.models.PeerId

@RunWith(AndroidJUnit4::class)
class SessionMetadataStoreTest {
    @Test
    fun profileRecoveryNeverOverridesSignOutOrExistingIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MonogramDatabase::class.java).build()
        try {
            val store = SessionMetadataStore(database)
            val old = AuthSession(PeerId(11), 2)
            val newer = AuthSession(PeerId(22), 4)
            assertTrue(store.recoverAuthorizedIfAbsent(old))
            assertEquals(false, store.recoverAuthorizedIfAbsent(newer))
            assertEquals(11L, store.readAuthorizedUserId()?.value)
            store.clearSession()
            assertEquals(false, store.recoverAuthorizedIfAbsent(old))
            assertEquals(null, store.readAuthorizedUserId())
            assertEquals("0", store.readMeta(SessionMetadataStore.KEY_AUTHORIZED))
            store.saveAuthorized(newer)
            assertEquals(22L, store.readAuthorizedUserId()?.value)
        } finally {
            database.close()
        }
    }

    @Test
    fun authorizationChangesRollBackTogetherOnFailure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MonogramDatabase::class.java).build()
        try {
            val store = SessionMetadataStore(database)
            store.saveAuthorized(AuthSession(PeerId(11), 2))
            val sql = database.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER reject_authorized BEFORE INSERT ON meta WHEN NEW.`key` = 'session.authorized' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            assertTrue(runCatching { store.saveAuthorized(AuthSession(PeerId(22), 4)) }.isFailure)
            assertEquals(11L, store.readAuthorizedUserId()?.value)
            assertEquals("2", store.readMeta(SessionMetadataStore.KEY_DC_ID))
            assertTrue(store.isAuthorized())
            sql.execSQL("DROP TRIGGER reject_authorized")

            sql.execSQL("CREATE TRIGGER reject_dc BEFORE INSERT ON meta WHEN NEW.`key` = 'session.dc_id' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            assertTrue(runCatching { store.clearAuthorized() }.isFailure)
            assertEquals(11L, store.readAuthorizedUserId()?.value)
            assertEquals("2", store.readMeta(SessionMetadataStore.KEY_DC_ID))
            assertTrue(store.isAuthorized())
        } finally {
            database.close()
        }
    }
}
