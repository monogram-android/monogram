package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.data.mtproto.MtProtoChatSettingsRepository

class TelegramBackendChatSettingsRouterTest {
    @Test
    fun `MTProto routes title and description without legacy`() = runBlocking {
        val settings = RecordingSettings()
        val router = TelegramBackendChatSettingsRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy chat settings must not be created") },
            mtProtoFactory = { settings },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        router.setChatTitle(-7, "Team")
        router.setChatDescription(-7, "Owned MTProto")

        assertEquals(listOf("title:-7:Team", "description:-7:Owned MTProto"), settings.calls)
    }

    private class RecordingSettings : MtProtoChatSettingsRepository {
        val calls = mutableListOf<String>()
        override suspend fun setTitle(chatId: Long, title: String) {
            calls += "title:$chatId:$title"
        }
        override suspend fun setDescription(chatId: Long, description: String) {
            calls += "description:$chatId:$description"
        }
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
