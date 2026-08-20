package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.data.mtproto.MtProtoChatCreationRepository

class TelegramBackendChatCreationRouterTest {
    @Test
    fun `selected MTProto creation avoids legacy repository`() = runBlocking {
        val router = TelegramBackendChatCreationRouter(
            selectionStore = SelectionStore,
            legacyFactory = { error("legacy chat creation repository must not be created") },
            mtProtoFactory = { object : MtProtoChatCreationRepository {
                override suspend fun createGroup(title: String, userIds: List<Long>, messageAutoDeleteTime: Int) = -11L
                override suspend fun createChannel(title: String, description: String, isMegagroup: Boolean, messageAutoDeleteTime: Int) = -1_000_000_000_012L
            } },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(-11L, router.createGroup("Team", listOf(7), 0))
        assertEquals(-1_000_000_000_012L, router.createChannel("News", "", false, 0))
    }

    private object SelectionStore : TelegramBackendSelectionStore {
        override suspend fun get(accountId: String) = TelegramBackendKind.KOTLIN_MTPROTO
        override fun observe(accountId: String): Flow<TelegramBackendKind> = MutableStateFlow(TelegramBackendKind.KOTLIN_MTPROTO)
        override suspend fun select(accountId: String, backend: TelegramBackendKind) = Unit
        override suspend fun reset(accountId: String) = Unit
    }
}
