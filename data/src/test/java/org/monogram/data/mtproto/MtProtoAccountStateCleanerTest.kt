package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.updates.MtProtoUpdateCursor

class MtProtoAccountStateCleanerTest {
    @Test
    fun `deletes auth keys and cursors for the same account scope`() = runBlocking {
        val authStore = FakeAuthKeyStore()
        val cursorStore = FakeCursorStore()
        val cleaner = MtProtoAccountStateCleaner(MtProtoAuthKeyPersistence(authStore), cursorStore)

        cleaner.deleteAccount("slot_a", MtProtoEnvironment.TEST)

        assertEquals(listOf("slot_a" to MtProtoEnvironment.TEST), authStore.deletedAccounts)
        assertEquals(listOf("slot_a" to MtProtoEnvironment.TEST), cursorStore.deletedAccounts)
    }

    @Test
    fun `attempts cursor cleanup when auth cleanup fails`() = runBlocking {
        val authFailure = IllegalStateException("auth delete failed")
        val authStore = FakeAuthKeyStore(deleteFailure = authFailure)
        val cursorStore = FakeCursorStore()
        val cleaner = MtProtoAccountStateCleaner(MtProtoAuthKeyPersistence(authStore), cursorStore)

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { cleaner.deleteAccount("slot_a", MtProtoEnvironment.PRODUCTION) }
        }

        assertSame(authFailure, thrown)
        assertEquals(1, cursorStore.deletedAccounts.size)
        Unit
    }

    @Test
    fun `retains both failures when both stores fail`() = runBlocking {
        val authFailure = IllegalStateException("auth delete failed")
        val cursorFailure = IllegalArgumentException("cursor delete failed")
        val cleaner = MtProtoAccountStateCleaner(
            MtProtoAuthKeyPersistence(FakeAuthKeyStore(deleteFailure = authFailure)),
            FakeCursorStore(deleteFailure = cursorFailure),
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { cleaner.deleteAccount("slot_a", MtProtoEnvironment.TEST) }
        }

        assertSame(authFailure, thrown)
        assertEquals(listOf(cursorFailure), thrown.suppressed.toList())
        Unit
    }

    @Test
    fun `propagates cancellation without continuing cleanup`() = runBlocking {
        val cancelled = CancellationException("cancelled")
        val cursorStore = FakeCursorStore()
        val cleaner = MtProtoAccountStateCleaner(
            MtProtoAuthKeyPersistence(FakeAuthKeyStore(deleteFailure = cancelled)),
            cursorStore,
        )

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { cleaner.deleteAccount("slot_a", MtProtoEnvironment.TEST) }
        }

        assertSame(cancelled, thrown)
        assertEquals(0, cursorStore.deletedAccounts.size)
        Unit
    }

    private class FakeAuthKeyStore(
        private val deleteFailure: Throwable? = null,
    ) : MtProtoAuthKeyStore {
        val deletedAccounts = mutableListOf<Pair<String, MtProtoEnvironment>>()

        override suspend fun load(scope: MtProtoAuthKeyScope) = MtProtoAuthKeyLoadResult.Missing
        override suspend fun save(scope: MtProtoAuthKeyScope, authKey: StoredMtProtoAuthKey) = Unit
        override suspend fun delete(scope: MtProtoAuthKeyScope) = Unit
        override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) {
            deletedAccounts += accountSlot to environment
            deleteFailure?.let { throw it }
        }
    }

    private class FakeCursorStore(
        private val deleteFailure: Throwable? = null,
    ) : MtProtoUpdateCursorStore {
        val deletedAccounts = mutableListOf<Pair<String, MtProtoEnvironment>>()

        override suspend fun load(scope: MtProtoAuthKeyScope) = MtProtoUpdateCursorLoadResult.Missing
        override suspend fun save(scope: MtProtoAuthKeyScope, cursor: MtProtoUpdateCursor) = Unit
        override suspend fun delete(scope: MtProtoAuthKeyScope) = Unit
        override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) {
            deletedAccounts += accountSlot to environment
            deleteFailure?.let { throw it }
        }
    }
}
