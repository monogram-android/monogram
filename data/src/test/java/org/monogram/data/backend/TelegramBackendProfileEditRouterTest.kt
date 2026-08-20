package org.monogram.data.backend

import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.domain.repository.UserProfileEditRepository

class TelegramBackendProfileEditRouterTest {
    @Test
    fun `MTProto name edit does not construct legacy profile repository`() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val mtProto = Proxy.newProxyInstance(
            UserProfileEditRepository::class.java.classLoader,
            arrayOf(UserProfileEditRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "setName" -> {
                    calls += args!![0] as String to args[1] as String
                    Unit
                }
                else -> error("Unexpected ${method.name}")
            }
        } as UserProfileEditRepository
        val router = TelegramBackendProfileEditRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy profile repository must not be created") },
            mtProtoFactory = { mtProto },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        router.setName("Ada", "Lovelace")

        assertEquals(listOf("Ada" to "Lovelace"), calls)
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
