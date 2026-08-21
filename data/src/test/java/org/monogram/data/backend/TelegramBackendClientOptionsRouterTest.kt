package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.mtproto.MtProtoClientOptionsRepository

class TelegramBackendClientOptionsRouterTest {
    @Test
    fun `selected MTProto contact notification setting avoids legacy repository`() = runBlocking {
        var observedEnabled: Boolean? = null
        val router = TelegramBackendClientOptionsRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy options repository must not be created") },
            mtProtoFactory = {
                object : MtProtoClientOptionsRepository {
                    override suspend fun getContactJoinedNotificationsEnabled() = true
                    override suspend fun setContactJoinedNotificationsEnabled(enabled: Boolean) { observedEnabled = enabled }
                }
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertTrue(router.getContactJoinedNotificationsEnabled())
        router.setContactJoinedNotificationsEnabled(false)
        assertEquals(false, observedEnabled)
    }

    @Test
    fun `selected MTProto unsupported client options fail closed without creating legacy repository`() = runBlocking {
        val router = TelegramBackendClientOptionsRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy options repository must not be created") },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val failure = runCatching { router.getAnimatedEmojiEnabled() }.exceptionOrNull()

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
