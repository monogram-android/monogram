package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.domain.models.TdLibLimits
import org.monogram.domain.repository.TdLibLimitsRepository

class TelegramBackendLimitsRouterTest {
    @Test
    fun `selected MTProto limits use defaults without creating legacy repository`() = runBlocking {
        val router = TelegramBackendLimitsRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy limits repository must not be created") },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        router.refresh()

        assertEquals(TdLibLimits.DEFAULTS, router.limits.value)
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
