package org.monogram.data.backend

import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.domain.repository.PrivacyRepository

class TelegramBackendPrivacyRouterTest {
    @Test fun `MTProto blocked users do not construct legacy privacy repository`() = runBlocking {
        val mtProto = Proxy.newProxyInstance(PrivacyRepository::class.java.classLoader, arrayOf(PrivacyRepository::class.java)) { _, method, _ ->
            when (method.name) { "getBlockedUsers" -> listOf(42L); else -> error("Unexpected ${method.name}") }
        } as PrivacyRepository
        val router = TelegramBackendPrivacyRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy privacy repository must not be created") },
            mtProtoFactory = { mtProto },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        assertEquals(listOf(42L), router.getBlockedUsers())
    }
    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
