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
        router.setChatHasProtectedContent(-7, true)
        router.setChatUsername(-7, "team")
        router.setChatSlowModeDelay(-7, 10)
        router.setChatHasHiddenMembers(-7, true)
        router.setChatHasAggressiveAntiSpamEnabled(-7, true)
        router.setChatJoinToSendMessages(-7, true)
        router.setChatJoinByRequest(-7, true)
        router.setChatSignMessages(-7, true)
        router.toggleChatIsForum(-7, true)
        router.setChatAvailableReactions(-7, listOf("👍"))

        assertEquals(listOf("title:-7:Team", "description:-7:Owned MTProto", "protected:true", "username:-7:team", "slow:10", "hidden:true", "spam:true", "join:true", "request:true", "sign:true", "forum:true", "reactions:👍"), settings.calls)
    }

    private class RecordingSettings : MtProtoChatSettingsRepository {
        val calls = mutableListOf<String>()
        override suspend fun setTitle(chatId: Long, title: String) {
            calls += "title:$chatId:$title"
        }
        override suspend fun setDescription(chatId: Long, description: String) {
            calls += "description:$chatId:$description"
        }
        override suspend fun setProtectedContent(chatId: Long, enabled: Boolean) { calls += "protected:$enabled" }
        override suspend fun setUsername(chatId: Long, username: String) {
            calls += "username:$chatId:$username"
        }
        override suspend fun setSlowModeDelay(chatId: Long, seconds: Int) { calls += "slow:$seconds" }
        override suspend fun setParticipantsHidden(chatId: Long, enabled: Boolean) { calls += "hidden:$enabled" }
        override suspend fun setAntiSpamEnabled(chatId: Long, enabled: Boolean) { calls += "spam:$enabled" }
        override suspend fun setJoinToSend(chatId: Long, enabled: Boolean) { calls += "join:$enabled" }
        override suspend fun setJoinByRequest(chatId: Long, enabled: Boolean) { calls += "request:$enabled" }
        override suspend fun setSignMessages(chatId: Long, enabled: Boolean) { calls += "sign:$enabled" }
        override suspend fun setForumEnabled(chatId: Long, enabled: Boolean) { calls += "forum:$enabled" }
        override suspend fun setAvailableReactions(chatId: Long, reactions: List<String>) { calls += "reactions:${reactions.joinToString()}" }
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
