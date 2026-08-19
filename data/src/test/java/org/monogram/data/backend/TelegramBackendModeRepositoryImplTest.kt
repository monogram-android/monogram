package org.monogram.data.backend

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.domain.repository.TelegramBackendMode

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramBackendModeRepositoryImplTest {
    @Test
    fun `maps selected backend and follows selector changes`() = runTest {
        val selection = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO)
        val repository = TelegramBackendModeRepositoryImpl(selection, backgroundScope)

        runCurrent()
        assertEquals(TelegramBackendMode.KOTLIN_MTPROTO, repository.backendMode.value)

        selection.backend.value = TelegramBackendKind.LEGACY
        runCurrent()
        assertEquals(TelegramBackendMode.LEGACY, repository.backendMode.value)
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        val backend = MutableStateFlow(initial)

        override suspend fun get(accountId: String): TelegramBackendKind = backend.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = backend
        override suspend fun select(accountId: String, backend: TelegramBackendKind) {
            this.backend.value = backend
        }
        override suspend fun reset(accountId: String) = Unit
    }
}
