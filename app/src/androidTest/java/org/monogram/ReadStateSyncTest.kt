package org.monogram

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.database.MonogramDatabase
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.Chat
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.NotifyException
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PeerId
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.root.RootComponent

class ReadStateSyncTest {
    @Test
    fun remoteReadUpdatesListCacheAndSurvivesReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "read-state-regression.db"
        context.deleteDatabase(name)
        var database = Room.databaseBuilder(context, MonogramDatabase::class.java, name).build()
        var lifecycle = LifecycleRegistry()
        val events = MutableSharedFlow<MtprotoUpdate>(extraBufferCapacity = 8)
        val chat = Chat(PeerId(42), "Test", unreadCount = 5, readInboxMaxId = 10, lastMessageId = 20)
        val client = Proxy.newProxyInstance(MtprotoClient::class.java.classLoader, arrayOf(MtprotoClient::class.java)) { _, method, _ ->
            when (method.name) {
                "updates" -> events
                "sessionLost" -> emptyFlow<Unit>()
                "getChats", "loadMoreChats", "getFolders" -> Outcome.Ok(emptyList<Chat>())
                "getNotifySettings" -> Outcome.Ok(NotifySettings())
                "getNotifyExceptions" -> Outcome.Ok(emptyList<NotifyException>())
                else -> Outcome.Ok(Unit)
            }
        } as MtprotoClient
        suspend fun open(warmup: OfflineWarmup): RootComponent = withContext(Dispatchers.Main) {
            lifecycle.resume()
            RootComponent(DefaultComponentContext(lifecycle), DefaultStoreFactory(), client,
                warmup, null, null, startOnHome = true)
        }
        suspend fun chats(root: RootComponent) = withContext(Dispatchers.Main) {
            (root.stack.value.active.instance as RootComponent.Child.Home).component.chats
        }
        try {
            var warmup = OfflineWarmup(database)
            warmup.upsertChats(listOf(chat))
            var root = open(warmup)
            var list = chats(root)
            withTimeout(5_000) { list.state.first { it.chats.singleOrNull()?.unreadCount == 5 } }
            withTimeout(5_000) { events.subscriptionCount.first { it >= 2 } }
            events.emit(MtprotoUpdate.ReadInbox(chat.id, 18, 2))
            withTimeout(5_000) { list.state.first { it.chats.singleOrNull()?.readInboxMaxId == 18 } }
            withTimeout(5_000) { warmup.observeReadStates().first { it.singleOrNull()?.readInboxMaxId == 18 } }
            assertEquals(2, warmup.chats().single().unreadCount)
            warmup.upsertChats(listOf(chat))
            events.emit(MtprotoUpdate.ChatsChanged(listOf(chat)))
            events.emit(MtprotoUpdate.ReadInbox(chat.id, 20, 0))
            withTimeout(5_000) { list.state.first { it.chats.singleOrNull()?.readInboxMaxId == 20 } }
            withTimeout(5_000) { warmup.observeReadStates().first { it.singleOrNull()?.readInboxMaxId == 20 } }
            withContext(Dispatchers.Main) { lifecycle.destroy() }
            database.close()
            database = Room.databaseBuilder(context, MonogramDatabase::class.java, name).build()
            warmup = OfflineWarmup(database)
            lifecycle = LifecycleRegistry()
            root = open(warmup)
            list = chats(root)
            val restored = withTimeout(5_000) { list.state.first { it.chats.isNotEmpty() } }.chats.single()
            assertEquals(20, restored.readInboxMaxId)
            assertEquals(0, restored.unreadCount)
            withTimeout(5_000) { events.subscriptionCount.first { it >= 2 } }
            val message = Message(MessageId(chat.id, 21), senderId = null, text = "original", date = 2, outgoing = true)
            events.emit(MtprotoUpdate.NewMessage(message))
            events.emit(MtprotoUpdate.MessageEdited(message.copy(text = "edited", editDate = 3)))
            events.emit(MtprotoUpdate.MessageReactions(chat.id, 21, "[]"))
            events.emit(MtprotoUpdate.ReadOutbox(chat.id, 21))
            events.emit(MtprotoUpdate.DiscussionInbox(chat.id, 100, 25))
            events.emit(MtprotoUpdate.FoldersChanged(emptyList()))
            withTimeout(5_000) {
                while (warmup.discussionReadMax(chat.id, 100) != 25) delay(10)
            }
            assertEquals("edited", warmup.chats().single().lastMessagePreview)
            assertEquals("[]", warmup.messages(chat.id).single().reactionsJson)
            assertEquals(21, warmup.chats().single().readOutboxMaxId)
            events.emit(MtprotoUpdate.MessagesDeleted(chat.id, listOf(21)))
            withTimeout(5_000) { while (warmup.messages(chat.id).isNotEmpty()) delay(10) }
            assertEquals(null, warmup.chats().single().lastMessagePreview)
        } finally {
            withContext(Dispatchers.Main) { lifecycle.destroy() }
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun partialLocalReadPreservesNewerIncomingAndRejectsStaleSnapshots() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MonogramDatabase::class.java).build()
        try {
            val cache = OfflineWarmup(database)
            val id = PeerId(42)
            cache.upsertChats(listOf(Chat(id, "Test", unreadCount = 3, readInboxMaxId = 10, lastMessageId = 13)))
            cache.upsertMessages((11..13).map { Message(MessageId(id, it), senderId = null, text = null, date = 1, outgoing = false) })
            cache.markChatRead(id, 12)
            assertEquals(1, cache.chats().single().unreadCount)
            assertEquals(12, cache.chats().single().readInboxMaxId)
            cache.applyInboxRead(id, 11, 2)
            cache.upsertChats(listOf(Chat(id, "Updated title", unreadCount = 3, readInboxMaxId = 10)))
            assertEquals(1, cache.chats().single().unreadCount)
            assertEquals("Updated title", cache.chats().single().title)
            cache.applyInboxRead(id, 13, 0)
            val next = Message(MessageId(id, 14), senderId = null, text = null, date = 2, outgoing = false)
            cache.applyIncomingMessage(next)
            cache.applyIncomingMessage(next)
            assertEquals(1, cache.chats().single().unreadCount)
            assertEquals(13, cache.chats().single().readInboxMaxId)
            cache.applyInboxRead(PeerId(99), 30, 2)
            cache.upsertChats(listOf(Chat(PeerId(99), "Late row", unreadCount = 5, readInboxMaxId = 25)))
            assertEquals(2, cache.chats().first { it.id.value == 99L }.unreadCount)
        } finally {
            database.close()
        }
    }
}
