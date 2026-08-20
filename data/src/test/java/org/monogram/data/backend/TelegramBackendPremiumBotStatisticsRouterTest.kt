package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramBackendPremiumBotStatisticsRouterTest {
    @Test
    fun `selected MTProto premium bot and statistics contracts fail closed`() = runBlocking {
        val selection = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val premium = TelegramBackendPremiumRouter(selection, { error("legacy premium created") }, scope)
        val bot = TelegramBackendBotRouter(selection, { error("legacy bot created") }, scope)
        val statistics = TelegramBackendChatStatisticsRouter(selection, { error("legacy statistics created") }, scope)

        assertTrue(runCatching { premium.getPremiumState() }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { bot.getBotCommands(1L) }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { statistics.getChatStatistics(1L, false) }.exceptionOrNull() is UnsupportedOperationException)
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
