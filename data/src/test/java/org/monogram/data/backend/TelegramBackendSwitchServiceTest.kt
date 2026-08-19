package org.monogram.data.backend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.data.mtproto.MtProtoAccountStateResetter
import org.monogram.data.mtproto.MtProtoAuthSessionResetter
import org.monogram.data.mtproto.MtProtoEnvironment

class TelegramBackendSwitchServiceTest {
    @Test
    fun `cleans legacy lifecycle before selecting MTProto`() = runBlocking {
        val events = mutableListOf<String>()
        val binding = LegacyActiveAccountBinding("account_1")
        val selection = FakeSelectionStore(TelegramBackendKind.LEGACY, events) {
            assertEquals(null, binding.accountId.value)
        }
        val service = TelegramBackendSwitchService(selection, binding, RecordingMtProtoResetter(events))

        service.switch("account_1", TelegramBackendKind.KOTLIN_MTPROTO)

        assertEquals(listOf("select-KOTLIN_MTPROTO"), events)
        assertEquals(TelegramBackendKind.KOTLIN_MTPROTO, selection.backend)
        assertEquals(null, binding.accountId.value)
    }

    @Test
    fun `keeps MTProto selected when its cleanup fails`() = runBlocking {
        val cleanupFailure = IllegalStateException("cleanup failed")
        val selection = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO)
        val binding = LegacyActiveAccountBinding("account_1")
        val service = TelegramBackendSwitchService(
            selection,
            binding,
            RecordingMtProtoResetter(deleteFailure = cleanupFailure),
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { service.switch("account_1", TelegramBackendKind.LEGACY) }
        }

        assertSame(cleanupFailure, thrown)
        assertEquals(TelegramBackendKind.KOTLIN_MTPROTO, selection.backend)
        assertEquals("account_1", binding.accountId.value)
    }

    @Test
    fun `resets MTProto auth before deleting its account state`() = runBlocking {
        val events = mutableListOf<String>()
        val service = TelegramBackendSwitchService(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO, events),
            legacyActiveAccountBinding = LegacyActiveAccountBinding("account_1"),
            mtProtoAccountStateResetter = RecordingMtProtoResetter(events),
            mtProtoAuthSessionResetter = MtProtoAuthSessionResetter { events += "mtproto-auth-reset" },
        )

        service.switch("account_1", TelegramBackendKind.LEGACY)

        assertEquals(
            listOf("mtproto-auth-reset", "mtproto-reset", "select-LEGACY"),
            events,
        )
    }

    @Test
    fun `selects legacy and binds lifecycle after MTProto cleanup`() = runBlocking {
        val events = mutableListOf<String>()
        val binding = LegacyActiveAccountBinding("other_account")
        val selection = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO, events) {
            assertEquals("other_account", binding.accountId.value)
        }
        val resetter = RecordingMtProtoResetter(events)
        val service = TelegramBackendSwitchService(selection, binding, resetter)

        service.switch("account_1", TelegramBackendKind.LEGACY)

        assertEquals(listOf("mtproto-reset", "select-LEGACY"), events)
        assertEquals(TelegramBackendKind.LEGACY, selection.backend)
        assertEquals("account_1", binding.accountId.value)
        assertEquals(listOf("account_1" to MtProtoEnvironment.PRODUCTION), resetter.deletedAccounts)
    }

    @Test
    fun `restores legacy lifecycle when selection persistence fails`() = runBlocking {
        val selectionFailure = IllegalStateException("selection failed")
        val selection = FakeSelectionStore(
            backend = TelegramBackendKind.LEGACY,
            selectFailure = selectionFailure,
        )
        val binding = LegacyActiveAccountBinding("account_1")
        val service = TelegramBackendSwitchService(
            selection,
            binding,
            RecordingMtProtoResetter(),
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { service.switch("account_1", TelegramBackendKind.KOTLIN_MTPROTO) }
        }

        assertSame(selectionFailure, thrown)
        assertEquals(TelegramBackendKind.LEGACY, selection.backend)
        assertEquals("account_1", binding.accountId.value)
    }

    @Test
    fun `serializes overlapping switches`() = runBlocking {
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val selection = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO, events)
        val binding = LegacyActiveAccountBinding("account_1")
        val resetter = object : MtProtoAccountStateResetter {
            var cleanupCount = 0

            override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) {
                cleanupCount += 1
                events += "mtproto-reset-$cleanupCount"
                if (cleanupCount == 1) {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                }
            }
        }
        val service = TelegramBackendSwitchService(selection, binding, resetter)

        coroutineScope {
            val firstSwitch = launch { service.switch("account_1", TelegramBackendKind.LEGACY) }
            cleanupStarted.await()
            val secondSwitch = launch { service.switch("account_1", TelegramBackendKind.KOTLIN_MTPROTO) }
            yield()

            assertEquals(TelegramBackendKind.KOTLIN_MTPROTO, selection.backend)
            assertEquals(listOf("mtproto-reset-1"), events)

            releaseCleanup.complete(Unit)
            firstSwitch.join()
            secondSwitch.join()
        }

        assertEquals(
            listOf("mtproto-reset-1", "select-LEGACY", "select-KOTLIN_MTPROTO"),
            events,
        )
        assertEquals(TelegramBackendKind.KOTLIN_MTPROTO, selection.backend)
        assertEquals(null, binding.accountId.value)
    }

    @Test
    fun `does nothing when account already uses requested backend`() = runBlocking {
        val events = mutableListOf<String>()
        val selection = FakeSelectionStore(TelegramBackendKind.LEGACY, events)
        val binding = LegacyActiveAccountBinding("account_1")
        val service = TelegramBackendSwitchService(selection, binding, RecordingMtProtoResetter(events))

        service.switch("account_1", TelegramBackendKind.LEGACY)

        assertEquals(emptyList<String>(), events)
        assertEquals("account_1", binding.accountId.value)
    }

    private class FakeSelectionStore(
        var backend: TelegramBackendKind,
        private val events: MutableList<String> = mutableListOf(),
        private val selectFailure: Throwable? = null,
        private val beforeSelect: () -> Unit = {},
    ) : TelegramBackendSelectionStore {
        override suspend fun get(accountId: String) = backend
        override fun observe(accountId: String): Flow<TelegramBackendKind> = error("not used")
        override suspend fun select(accountId: String, backend: TelegramBackendKind) {
            beforeSelect()
            events += "select-$backend"
            selectFailure?.let { throw it }
            this.backend = backend
        }
        override suspend fun reset(accountId: String) = Unit
    }

    private class RecordingMtProtoResetter(
        private val events: MutableList<String> = mutableListOf(),
        private val deleteFailure: Throwable? = null,
    ) : MtProtoAccountStateResetter {
        val deletedAccounts = mutableListOf<Pair<String, MtProtoEnvironment>>()

        override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) {
            events += "mtproto-reset"
            deletedAccounts += accountSlot to environment
            deleteFailure?.let { throw it }
        }
    }
}
