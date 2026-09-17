package org.monogram.feature.chats

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.AuthState
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.Message
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.bridge.UpdatesCursor

@OptIn(ExperimentalCoroutinesApi::class)
class ChatsStoreCacheTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun cachePaintsBeforeFailedNetwork() {
        val cached = Chat(PeerId(1), "Ada")
        val warmup = object : OfflineWarmup() {
            override suspend fun chats() = listOf(cached)
        }
        val store = ChatsStoreFactory(
            DefaultStoreFactory(),
            StubClient(chats = Outcome.Err("offline")),
            warmup,
            sessionStore = null,
        ).create()
        try {
            assertEquals("Ada", store.state.chats.single().title)
            assertTrue(store.state.fromCache)
            assertNull(store.state.error)
            assertTrue(!store.state.loading)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun markReadCallsReadHistoryForUnreadChatsOnly() {
        val unread = Chat(PeerId(1), "Ada", unreadCount = 3, lastMessageId = 44)
        val read = Chat(PeerId(2), "Bob", unreadCount = 0, lastMessageId = 7)
        val client = StubClient(chats = Outcome.Ok(listOf(unread, read)))
        val store = ChatsStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = null,
            sessionStore = null,
        ).create()
        try {
            store.accept(ChatsStore.Intent.MarkRead(listOf(PeerId(1), PeerId(2))))
            assertEquals(listOf(PeerId(1) to 44), client.reads)
            assertEquals(0, store.state.chats.first { it.id == PeerId(1) }.unreadCount)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun cachedMuteSurvivesMissingNotifyDefaults() {
        // getNotifySettings is stubbed as an error: the cached effective flag is all there is.
        val cached = Chat(PeerId(1), "Ada", muted = true, muteOverride = false)
        val warmup = object : OfflineWarmup() {
            override suspend fun chats() = listOf(cached)
        }
        val store = ChatsStoreFactory(
            DefaultStoreFactory(),
            StubClient(chats = Outcome.Err("offline"), failNotifySettings = true),
            warmup,
            sessionStore = null,
        ).create()
        try {
            assertTrue(store.state.chats.single().muted)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun ownMuteSettingWinsOverTheTypeDefaultFromTheServer() {
        val client = StubClient(
            chats = Outcome.Ok(
                listOf(
                    Chat(PeerId(1), "Ada", muted = false, muteOverride = true),
                    Chat(PeerId(2), "Bob", muted = false, muteOverride = false),
                ),
            ),
            defaults = NotifySettings(muteUntil = Int.MAX_VALUE),
        )
        val store = ChatsStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = null,
            sessionStore = null,
        ).create()
        try {
            // The dialog without its own setting inherits the muted "users" default; the one with
            // an own setting stays unmuted.
            assertFalse(store.state.chats.first { it.id == PeerId(1) }.muted)
            assertTrue(store.state.chats.first { it.id == PeerId(2) }.muted)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun peerNotifyChangeUpdatesTheRowAndTheCache() = runTest {
        val upserted = mutableListOf<Chat>()
        val warmup = object : OfflineWarmup() {
            override suspend fun chats() = emptyList<Chat>()
            override suspend fun upsertChats(chats: List<Chat>) {
                upserted += chats
            }
        }
        val client = StubClient(chats = Outcome.Ok(listOf(Chat(PeerId(1), "Ada"))))
        val store = ChatsStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup,
            sessionStore = null,
        ).create()
        try {
            client.events.emit(
                MtprotoUpdate.NotifySettingsChanged(
                    peerKind = "peer",
                    chatId = PeerId(1),
                    muteUntil = Int.MAX_VALUE,
                ),
            )
            val updated = store.state.chats.single()
            assertTrue(updated.muted)
            assertTrue(updated.muteOverride)
            assertTrue(upserted.isNotEmpty())
            assertEquals(PeerId(1), upserted.last().id)
            assertTrue(upserted.last().muted)
            assertTrue(upserted.last().muteOverride)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun typeNotifyChangeReloadsTheDefaults() = runTest {
        val client = StubClient(chats = Outcome.Ok(listOf(Chat(PeerId(1), "Ada"))))
        val store = ChatsStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = null,
            sessionStore = null,
        ).create()
        try {
            val before = client.notifySettingsCalls
            client.events.emit(
                MtprotoUpdate.NotifySettingsChanged(
                    peerKind = "chats",
                    chatId = PeerId(0),
                    muteUntil = Int.MAX_VALUE,
                ),
            )
            assertTrue(client.notifySettingsCalls > before)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun metadataPageRewritesTheCacheOnlyWhenMuteStateMoved() = runTest {
        val upserted = mutableListOf<List<Chat>>()
        val warmup = object : OfflineWarmup() {
            override suspend fun chats() = emptyList<Chat>()
            override suspend fun upsertChats(chats: List<Chat>) {
                upserted += chats
            }
        }
        val client = StubClient(chats = Outcome.Ok(listOf(Chat(PeerId(1), "Ada"))))
        val store = ChatsStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup,
            sessionStore = null,
        ).create()
        try {
            upserted.clear()
            val mutedByServer = Chat(PeerId(1), "Ada", muted = true, muteOverride = true)

            // A page that carries a mute the cache does not know yet is written through.
            client.events.emit(MtprotoUpdate.ChatsChanged(listOf(mutedByServer)))
            assertTrue(store.state.chats.single().muted)
            assertEquals(1, upserted.size)
            assertTrue(upserted.single().single().muted)

            // A repeated metadata page (typing, presence) must not rewrite the row.
            client.events.emit(MtprotoUpdate.ChatsChanged(listOf(mutedByServer)))
            assertEquals(1, upserted.size)
        } finally {
            store.dispose()
        }
    }

    private class StubClient(
        private val chats: Outcome<List<Chat>>,
        private val defaults: NotifySettings = NotifySettings(),
        private val failNotifySettings: Boolean = false,
    ) : MtprotoClient {
        val reads = mutableListOf<Pair<PeerId, Int>>()
        val events = MutableSharedFlow<MtprotoUpdate>(extraBufferCapacity = 8)
        var notifySettingsCalls = 0
        override suspend fun getNotifySettings(peerKind: String, chatId: PeerId): Outcome<NotifySettings> {
            notifySettingsCalls++
            return if (failNotifySettings) Outcome.Err("offline") else Outcome.Ok(defaults)
        }
        override suspend fun connect() = Outcome.Ok(Unit)
        override suspend fun sendAuthCode(phone: String) = unused<AuthState.AwaitingCode>()
        override suspend fun signIn(phone: String, phoneCodeHash: String, code: String) = unused<AuthState>()
        override suspend fun checkPassword(password: String) = unused<AuthState.Authorized>()
        override suspend fun getChats() = chats
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
        override suspend fun readHistory(chatId: PeerId, maxId: Int): Outcome<Unit> {
            reads += chatId to maxId
            return Outcome.Ok(Unit)
        }
        override suspend fun setTyping(chatId: PeerId, typing: Boolean) = Outcome.Ok(Unit)
        override suspend fun getProfile(peerId: PeerId) = unused<Profile>()
        override suspend fun downloadMessageMedia(chatId: PeerId, messageId: Int, destPath: String) =
            unused<String>()
        override suspend fun downloadMessageThumb(chatId: PeerId, messageId: Int, destPath: String) =
            unused<String>()
        override suspend fun getUpdatesState() = Outcome.Ok(UpdatesCursor(0, 0, 0, 0))
        override fun updates(): Flow<MtprotoUpdate> = events
        override fun sessionLost(): Flow<Unit> = emptyFlow()
        override fun libraryVersion() = "test"
        override suspend fun logout() = Outcome.Ok(Unit)
        override fun close() = Unit
        private fun <T> unused(): Outcome<T> = Outcome.Err("unused")
    }
}
