package org.monogram.feature.settings

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
class SettingsStoreCacheTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun cacheChatRowsKeepAvatarKeysFromWarmup() {
        val rows = cacheChatRows(
            usageByChat = mapOf(7L to 40L, 3L to 90L, 9L to 10L),
            chats = listOf(
                Chat(id = PeerId(3), title = "Photos", photoCacheKey = "avatar:3:video"),
                Chat(id = PeerId(7), title = "Docs", photoCacheKey = "photo:7"),
            ),
        )
        assertEquals(listOf(3L, 7L, 9L), rows.map { it.chatId })
        assertEquals("Photos", rows[0].title)
        assertEquals("avatar:3:video", rows[0].photoCacheKey)
        assertEquals("Docs", rows[1].title)
        assertEquals("photo:7", rows[1].photoCacheKey)
        assertEquals("9", rows[2].title)
        assertNull(rows[2].photoCacheKey)
    }

    @Test
    fun cachedSelfProfileStaysWhenNetworkFails() {
        val self = Profile(
            id = PeerId(42),
            kind = "user",
            title = "Me",
            isSelf = true,
            phone = "+1",
            isPremium = true,
        )
        val session = object : SessionMetadataStore() {
            override suspend fun readAuthorizedUserId() = PeerId(42)
            override suspend fun readProfile(peerId: Long) = self.takeIf { it.id.value == peerId }
        }
        val store = SettingsStoreFactory(
            DefaultStoreFactory(),
            StubClient(),
            session,
            warmup = null,
            mediaRepository = null,
            appVersion = "test",
            buildStamp = "test",
            pushRegistration = null,
        ).create()
        try {
            assertEquals("Me", store.state.profile?.title)
            assertTrue(store.state.profile?.isSelf == true)
            assertEquals("+1", store.state.profile?.phone)
            assertNull(store.state.error)
            assertTrue(!store.state.loading)
        } finally {
            store.dispose()
        }
    }

    private class StubClient : MtprotoClient {
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
            webpageUrl: String?,
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
        override suspend fun getProfile(peerId: PeerId): Outcome<Profile> = Outcome.Err("offline")
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
