package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.mtproto.FakeKeyValueStore
import org.monogram.data.mtproto.MtProtoNetworkStatisticsRepositoryImpl
import org.monogram.data.mtproto.NetworkType

class TelegramBackendNetworkStatisticsRouterTest {
    @Test
    fun `selected MTProto network statistics dispatch to MTProto repository`() = runBlocking {
        val router = TelegramBackendNetworkStatisticsRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy network statistics repository must not be created") },
            mtProtoFactory = { MtProtoNetworkStatisticsRepositoryImpl(FakeKeyValueStore(), networkType = { NetworkType.OTHER }) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertTrue(router.getNetworkStatisticsEnabled())
        router.setNetworkStatisticsEnabled(false)
        assertEquals(false, router.getNetworkStatisticsEnabled())
        assertEquals(null, router.getNetworkUsage()?.mobile?.details?.singleOrNull())
        assertTrue(router.resetNetworkStatistics())
    }

    @Test
    fun `unconfigured MTProto network statistics fail closed without creating legacy repository`() = runBlocking {
        val router = TelegramBackendNetworkStatisticsRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy network statistics repository must not be created") },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val failure = runCatching { router.getNetworkUsage() }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
