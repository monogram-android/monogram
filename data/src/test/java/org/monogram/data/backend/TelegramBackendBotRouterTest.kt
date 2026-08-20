package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.data.mtproto.MtProtoBotCommandRepository
import org.monogram.domain.models.BotCommandModel
import org.monogram.domain.models.BotInfoModel
import org.monogram.domain.repository.BotRepository

class TelegramBackendBotRouterTest {
    @Test
    fun `selected MTProto bot commands avoid legacy repository`() = runBlocking {
        val router = TelegramBackendBotRouter(
            selectionStore = SelectionStore,
            legacyFactory = { error("legacy bot repository must not be created") },
            mtProtoFactory = {
                object : MtProtoBotCommandRepository {
                    override suspend fun getCommands(botId: Long) = listOf(BotCommandModel("start", "Begin"))
                }
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(listOf("start"), router.getBotCommands(7).map { it.command })
    }

    private object SelectionStore : TelegramBackendSelectionStore {
        override suspend fun get(accountId: String) = TelegramBackendKind.KOTLIN_MTPROTO
        override fun observe(accountId: String): Flow<TelegramBackendKind> = MutableStateFlow(TelegramBackendKind.KOTLIN_MTPROTO)
        override suspend fun select(accountId: String, backend: TelegramBackendKind) = Unit
        override suspend fun reset(accountId: String) = Unit
    }

    private object UnusedLegacyRepository : BotRepository {
        override suspend fun getBotCommands(botId: Long): List<BotCommandModel> = emptyList()
        override suspend fun getBotInfo(botId: Long): BotInfoModel? = null
    }
}
