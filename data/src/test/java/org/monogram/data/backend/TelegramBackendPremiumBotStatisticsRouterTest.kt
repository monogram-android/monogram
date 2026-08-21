package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.monogram.data.mtproto.MtProtoChatStatisticsRepository
import org.monogram.data.mtproto.MtProtoPremiumRepository
import org.monogram.domain.models.ChatStatisticsModel
import org.monogram.domain.models.DateRangeModel
import org.monogram.domain.models.StatisticsGraphModel
import org.monogram.domain.models.StatisticsType
import org.monogram.domain.models.StatisticsValueModel
import org.junit.Test

class TelegramBackendPremiumBotStatisticsRouterTest {
    @Test
    fun `selected MTProto premium bot and statistics contracts fail closed`() = runBlocking {
        val selection = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val premium = TelegramBackendPremiumRouter(
            selectionStore = selection,
            legacyFactory = { error("legacy premium created") },
            scope = scope,
        )
        val bot = TelegramBackendBotRouter(selection, { error("legacy bot created") }, scope)
        assertTrue(runCatching { premium.getPremiumState() }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { bot.getBotCommands(1L) }.exceptionOrNull() is UnsupportedOperationException)
    }

    @Test
    fun `selected MTProto premium mutation avoids legacy repository`() = runBlocking {
        var enabled: Boolean? = null
        val premium = TelegramBackendPremiumRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy premium created") },
            mtProtoFactory = { MtProtoPremiumRepository { enabled = it } },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        premium.setSponsoredMessagesEnabled(false)

        assertEquals(false, enabled)
        assertTrue(runCatching { premium.getPremiumFeatures(org.monogram.domain.models.PremiumSource.SETTINGS) }.exceptionOrNull() is UnsupportedOperationException)
    }

    @Test
    fun `selected MTProto statistics graph avoids legacy repository`() = runBlocking {
        val statistics = TelegramBackendChatStatisticsRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy statistics created") },
            mtProtoFactory = { object : MtProtoChatStatisticsRepository {
                override suspend fun getChatStatistics(chatId: Long, isDark: Boolean) = statistics()
                override suspend fun loadGraph(token: String, x: Long) = StatisticsGraphModel.Async("$token:$x")
            } },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(StatisticsGraphModel.Async("token:7"), statistics.loadStatisticsGraph(-1, "token", 7))
        assertEquals(StatisticsType.CHANNEL, statistics.getChatStatistics(-1, false)?.type)
    }

    private fun statistics() = ChatStatisticsModel(
        type = StatisticsType.CHANNEL,
        period = DateRangeModel(1, 2),
        memberCount = StatisticsValueModel(1.0, 0.0, 0.0),
    )

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
