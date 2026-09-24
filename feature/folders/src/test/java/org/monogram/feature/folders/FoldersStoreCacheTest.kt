package org.monogram.feature.folders

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.bridge.UpdatesCursor

@OptIn(ExperimentalCoroutinesApi::class)
class FoldersStoreCacheTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun cachePaintsAndNetworkStillRuns() {
        val cached = Folder(id = 2, title = "Work")
        val warmup = object : OfflineWarmup() {
            override suspend fun folders() = listOf(cached)
        }
        val client = StubClient(folders = Outcome.Err("offline"))
        val store = FoldersStoreFactory(DefaultStoreFactory(), client, warmup).create()
        try {
            // "All chats" is synthetic and always leads the list, so the cached folder is not alone.
            assertEquals(2, store.state.folders.size)
            assertEquals("Work", store.state.folders.first { it.id == cached.id }.title)
            assertTrue(store.state.fromCache)
            assertNull(store.state.error)
            assertEquals(1, client.folderCalls)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun cachedOrderIsPublishedAsStored() {
        // The DAO hands rows back by position, so the store must not re-sort them by id.
        val warmup = RecordingWarmup(
            listOf(Folder(id = 7, title = "Later"), Folder(id = 3, title = "Earlier")),
        )
        val store = FoldersStoreFactory(
            DefaultStoreFactory(),
            StubClient(folders = Outcome.Err("offline")),
            warmup,
        ).create()
        try {
            assertEquals(listOf(0, 7, 3), store.state.order)
            assertEquals(listOf(0, 7, 3), store.state.folders.map { it.id })
        } finally {
            store.dispose()
        }
    }

    @Test
    fun reorderIsCachedBeforeTheServerAnswers() {
        val warmup = RecordingWarmup(
            listOf(Folder(id = 2, title = "Work"), Folder(id = 3, title = "Fun")),
        )
        val client = StubClient(folders = Outcome.Err("offline"))
        val store = FoldersStoreFactory(DefaultStoreFactory(), client, warmup).create()
        try {
            store.accept(FoldersStore.Intent.Move(from = 2, to = 0))

            assertEquals(listOf(3, 0, 2), client.lastOrder)
            assertEquals(
                "an offline reorder must still survive the restart",
                listOf(3, 0, 2),
                warmup.stored.map { it.id },
            )
        } finally {
            store.dispose()
        }
    }

    @Test
    fun aDragPushesOneOrderPerRoundTrip() = runBlocking {
        val warmup = RecordingWarmup(
            listOf(Folder(id = 2, title = "Work"), Folder(id = 3, title = "Fun")),
        )
        val gate = CompletableDeferred<Unit>()
        val client = StubClient(folders = Outcome.Err("offline"), orderGate = gate)
        val store = FoldersStoreFactory(DefaultStoreFactory(), client, warmup).create()
        try {
            // The first move parks on the gate; the next two publish over it before it returns.
            store.accept(FoldersStore.Intent.Move(from = 2, to = 0))
            store.accept(FoldersStore.Intent.Move(from = 0, to = 2))
            store.accept(FoldersStore.Intent.Move(from = 0, to = 1))
            gate.complete(Unit)

            assertEquals("the burst must collapse, not queue one call per row", 2, client.orderCalls)
            assertEquals(listOf(2, 0, 3), client.lastOrder)
            assertEquals(listOf(2, 0, 3), warmup.stored.map { it.id })
        } finally {
            store.dispose()
        }
    }

    private class RecordingWarmup(initial: List<Folder>) : OfflineWarmup() {
        var stored: List<Folder> = initial
            private set

        override suspend fun folders() = stored

        override suspend fun replaceFolders(folders: List<Folder>) {
            stored = folders
        }
    }

    private class StubClient(
        private val folders: Outcome<List<Folder>>,
        private val orderGate: CompletableDeferred<Unit>? = null,
    ) : MtprotoClient {
        var folderCalls = 0
        var orderCalls = 0
        var lastOrder: List<Int>? = null
        override suspend fun updateFolderOrder(order: List<Int>): Outcome<Unit> {
            orderCalls++
            lastOrder = order
            orderGate?.await()
            return Outcome.Err("offline")
        }
        override suspend fun connect() = Outcome.Ok(Unit)
        override suspend fun sendAuthCode(phone: String) = unused<AuthState.AwaitingCode>()
        override suspend fun signIn(phone: String, phoneCodeHash: String, code: String) = unused<AuthState>()
        override suspend fun checkPassword(password: String) = unused<AuthState.Authorized>()
        override suspend fun getChats() = Outcome.Ok(emptyList<Chat>())
        override suspend fun loadMoreChats(offsetDate: Int, offsetId: Int, offsetPeerId: Long) =
            Outcome.Ok(emptyList<Chat>())
        override suspend fun getFolders(): Outcome<List<Folder>> {
            folderCalls++
            return folders
        }
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
        override suspend fun getProfile(peerId: PeerId) = unused<Profile>()
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
