package org.monogram.feature.profile

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.models.AuthState
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.bridge.UpdatesCursor

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileStoreCacheTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun cachedProfileStaysWhenNetworkFails() {
        val cached = Profile(
            id = PeerId(9),
            kind = "user",
            title = "Cara",
            phone = "+3",
            isPremium = true,
            isSelf = false,
        )
        val session = object : SessionMetadataStore() {
            override suspend fun readProfile(peerId: Long) = cached.takeIf { it.id.value == peerId }
        }
        val store = ProfileStoreFactory(
            DefaultStoreFactory(),
            StubClient(Outcome.Err("offline")),
            session,
            PeerId(9),
        ).create()
        try {
            assertEquals("Cara", store.state.profile?.title)
            assertEquals("+3", store.state.profile?.phone)
            assertTrue(store.state.profile?.isPremium == true)
            assertNull(store.state.error)
            assertTrue(!store.state.loading)
        } finally {
            store.dispose()
        }
    }

    private class StubClient(
        private val profile: Outcome<Profile>,
    ) : MtprotoClient {
        override suspend fun connect() = Outcome.Ok(Unit)
        override suspend fun sendAuthCode(phone: String) = unused<AuthState.AwaitingCode>()
        override suspend fun signIn(phone: String, phoneCodeHash: String, code: String) = unused<AuthState>()
        override suspend fun checkPassword(password: String) = unused<AuthState.Authorized>()
        override suspend fun getChats() = Outcome.Ok(emptyList<Chat>())
        override suspend fun loadMoreChats(offsetDate: Int, offsetId: Int, offsetPeerId: Long) =
            Outcome.Ok(emptyList<Chat>())
        override suspend fun getFolders() = Outcome.Ok(emptyList<Folder>())
        override suspend fun getHistory(chatId: PeerId, limit: Int) = Outcome.Ok(emptyList<Message>())
        override suspend fun getHistoryPage(
            chatId: PeerId,
            limit: Int,
            offsetId: Int,
            offsetDate: Int,
            addOffset: Int,
        ) = Outcome.Ok(emptyList<Message>())
        override suspend fun searchMessages(chatId: PeerId, query: String, limit: Int) =
            Outcome.Ok(emptyList<Message>())
        override suspend fun getPinnedMessages(chatId: PeerId, limit: Int) = Outcome.Ok(emptyList<Message>())
        override suspend fun sendText(
            chatId: PeerId,
            text: String,
            replyToMsgId: Int,
            entitiesJson: String?,
            topMsgId: Int,
        ) = unused<Message>()
        override suspend fun sendPhoto(
            chatId: PeerId,
            path: String,
            caption: String,
            replyToMsgId: Int,
            topMsgId: Int,
            entitiesJson: String?,
        ) = unused<Message>()
        override suspend fun editText(chatId: PeerId, messageId: Int, text: String, entitiesJson: String?) =
            unused<Message>()
        override suspend fun deleteMessage(chatId: PeerId, messageId: Int, revoke: Boolean) = Outcome.Ok(Unit)
        override suspend fun forwardMessage(fromChatId: PeerId, messageId: Int, toChatId: PeerId) =
            Outcome.Ok(emptyList<Message>())
        override suspend fun readHistory(chatId: PeerId, maxId: Int) = Outcome.Ok(Unit)
        override suspend fun setTyping(chatId: PeerId, typing: Boolean) = Outcome.Ok(Unit)
        override suspend fun getProfile(peerId: PeerId) = profile
        override suspend fun downloadMessageMedia(chatId: PeerId, messageId: Int, destPath: String) =
            unused<String>()
        override suspend fun downloadMessageThumb(chatId: PeerId, messageId: Int, destPath: String) =
            unused<String>()
        override suspend fun getUpdatesState() = Outcome.Ok(UpdatesCursor(0, 0, 0, 0))
        override fun updates(): Flow<MtprotoUpdate> = MutableSharedFlow()
        override fun sessionLost(): Flow<Unit> = emptyFlow()
        override fun libraryVersion() = "test"
        override suspend fun logout() = Outcome.Ok(Unit)
        override fun close() = Unit
        private fun <T> unused(): Outcome<T> = Outcome.Err("unused")
    }
}
