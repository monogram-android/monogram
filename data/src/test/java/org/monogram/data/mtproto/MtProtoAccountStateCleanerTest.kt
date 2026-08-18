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
        val pendingStore = FakePendingStore()
        val cloudStore = FakeCloudObjectStager()
        val cleaner = MtProtoAccountStateCleaner(
            MtProtoAuthKeyPersistence(authStore),
            cursorStore,
            pendingStore,
            cloudStore,
        )

        cleaner.deleteAccount("slot_a", MtProtoEnvironment.TEST)

        assertEquals(listOf("slot_a" to MtProtoEnvironment.TEST), authStore.deletedAccounts)
        assertEquals(listOf("slot_a" to MtProtoEnvironment.TEST), cursorStore.deletedAccounts)
        assertEquals(listOf("slot_a" to MtProtoEnvironment.TEST), pendingStore.deletedAccounts)
        assertEquals(listOf("slot_a" to MtProtoEnvironment.TEST), cloudStore.deletedAccounts)
    }

    @Test
    fun `attempts cursor cleanup when auth cleanup fails`() = runBlocking {
        val authFailure = IllegalStateException("auth delete failed")
        val authStore = FakeAuthKeyStore(deleteFailure = authFailure)
        val cursorStore = FakeCursorStore()
        val pendingStore = FakePendingStore()
        val cloudStore = FakeCloudObjectStager()
        val cleaner = MtProtoAccountStateCleaner(
            MtProtoAuthKeyPersistence(authStore),
            cursorStore,
            pendingStore,
            cloudStore,
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { cleaner.deleteAccount("slot_a", MtProtoEnvironment.PRODUCTION) }
        }

        assertSame(authFailure, thrown)
        assertEquals(1, cursorStore.deletedAccounts.size)
        assertEquals(1, pendingStore.deletedAccounts.size)
        assertEquals(1, cloudStore.deletedAccounts.size)
        Unit
    }

    @Test
    fun `retains all failures when all stores fail`() = runBlocking {
        val authFailure = IllegalStateException("auth delete failed")
        val cursorFailure = IllegalArgumentException("cursor delete failed")
        val pendingFailure = UnsupportedOperationException("pending delete failed")
        val cloudFailure = IllegalStateException("cloud delete failed")
        val cleaner = MtProtoAccountStateCleaner(
            MtProtoAuthKeyPersistence(FakeAuthKeyStore(deleteFailure = authFailure)),
            FakeCursorStore(deleteFailure = cursorFailure),
            FakePendingStore(deleteFailure = pendingFailure),
            FakeCloudObjectStager(deleteFailure = cloudFailure),
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { cleaner.deleteAccount("slot_a", MtProtoEnvironment.TEST) }
        }

        assertSame(authFailure, thrown)
        assertEquals(listOf(cursorFailure, pendingFailure, cloudFailure), thrown.suppressed.toList())
        Unit
    }

    @Test
    fun `propagates cancellation without continuing cleanup`() = runBlocking {
        val cancelled = CancellationException("cancelled")
        val cursorStore = FakeCursorStore()
        val cleaner = MtProtoAccountStateCleaner(
            MtProtoAuthKeyPersistence(FakeAuthKeyStore(deleteFailure = cancelled)),
            cursorStore,
            FakePendingStore(),
            FakeCloudObjectStager(),
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

    private class FakePendingStore(
        private val deleteFailure: Throwable? = null,
    ) : MtProtoPendingEnvelopeStore {
        val deletedAccounts = mutableListOf<Pair<String, MtProtoEnvironment>>()

        override suspend fun enqueue(
            scope: MtProtoAuthKeyScope,
            envelope: org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5,
        ): MtProtoPendingEnvelope.Decoded = error("not used")

        override suspend fun pending(scope: MtProtoAuthKeyScope): List<MtProtoPendingEnvelope> = error("not used")
        override suspend fun delete(sequenceId: Long) = error("not used")

        override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) {
            deletedAccounts += accountSlot to environment
            deleteFailure?.let { throw it }
        }
    }

    private class FakeCloudObjectStager(
        private val deleteFailure: Throwable? = null,
    ) : MtProtoCloudObjectStager {
        val deletedAccounts = mutableListOf<Pair<String, MtProtoEnvironment>>()

        override suspend fun stageLive(
            scope: MtProtoAuthKeyScope,
            envelope: org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5,
        ) = error("not used")

        override suspend fun stageDifference(
            scope: MtProtoAuthKeyScope,
            batch: org.monogram.mtproto.updates.MtProtoUpdateDifferenceBatch,
        ) = error("not used")

        override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) {
            deletedAccounts += accountSlot to environment
            deleteFailure?.let { throw it }
        }
    }
}
