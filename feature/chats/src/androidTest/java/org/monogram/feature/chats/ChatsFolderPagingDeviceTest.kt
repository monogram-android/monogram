package org.monogram.feature.chats

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import java.lang.reflect.Proxy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import org.monogram.core.common.Outcome
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PeerId
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.UpdatesCursor

class ChatsFolderPagingDeviceTest {
    @Test
    fun rejectedCustomFolderPagesArchivedSecondPageOnDevice() = runBlocking {
        val lateArchived = Chat(
            id = PeerId(199),
            title = "Archived second page",
            archived = true,
            lastMessageDate = 199,
            lastMessageId = 1,
            unreadCount = 2,
        )
        val archivePages = ArrayDeque<List<Chat>>()
        archivePages.addLast((100L..139L).map { id ->
            Chat(PeerId(id), "Archive $id", archived = true, lastMessageDate = id, lastMessageId = 1)
        })
        archivePages.addLast(listOf(lateArchived))
        val folderCalls = mutableListOf<Int>()
        val client = Proxy.newProxyInstance(
            MtprotoClient::class.java.classLoader,
            arrayOf(MtprotoClient::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ChatsFolderPagingDeviceClient"
                "libraryVersion" -> "test"
                "updates", "sessionLost" -> emptyFlow<Nothing>()
                "connect" -> Outcome.Ok(Unit)
                "getChats" -> Outcome.Ok<List<Chat>>(
                    listOf(Chat(PeerId(1), "Main", lastMessageDate = 1, lastMessageId = 1)),
                )
                "loadMoreChats" -> Outcome.Ok<List<Chat>>(emptyList())
                "loadMoreFolderChats" -> {
                    val folderId = args!![0] as Int
                    folderCalls += folderId
                    if (folderId != ARCHIVE_FOLDER_WIRE_ID) {
                        Outcome.Err(FOLDER_ID_INVALID)
                    } else {
                        Outcome.Ok<List<Chat>>(
                            if (archivePages.isEmpty()) emptyList() else archivePages.removeFirst(),
                        )
                    }
                }
                "getNotifySettings" -> Outcome.Ok(NotifySettings())
                "getFolders" -> Outcome.Ok(emptyList<Folder>())
                "getProfile" -> Outcome.Err("unavailable")
                "getUpdatesState" -> Outcome.Ok(UpdatesCursor(0, 0, 0, 0))
                "close" -> Unit
                else -> defaultOutcome(method.returnType)
            }
        } as MtprotoClient

        lateinit var store: ChatsStore
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            store = ChatsStoreFactory(
                DefaultStoreFactory(),
                client,
                warmup = null,
                sessionStore = null,
            ).create()
            store.accept(ChatsStore.Intent.FolderSelected(7))
        }
        try {
            withTimeout(10_000) {
                while (folderCalls.count { it == ARCHIVE_FOLDER_WIRE_ID } < 2) delay(10)
            }
            val folder = Folder(
                id = 7,
                title = "Work",
                chatIds = listOf(lateArchived.id),
                excludeArchived = true,
            )
            val visible = visibleChats(store.state.chats, listOf(folder), 7)
            assertTrue(folderCalls.contains(7))
            assertEquals(listOf(lateArchived.id), visible.map { it.id })
            assertEquals(FolderUnreadBadge(unmuted = 1, muted = 0), folderUnreadBadge(visible))
            assertFalse(store.state.hasMore)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.dispose() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun defaultOutcome(returnType: Class<*>): Any? = when {
        returnType == java.lang.Boolean.TYPE -> false
        returnType == java.lang.Integer.TYPE -> 0
        returnType == java.lang.Long.TYPE -> 0L
        returnType == java.lang.Void.TYPE -> Unit
        Outcome::class.java.isAssignableFrom(returnType) -> Outcome.Err("unsupported")
        List::class.java.isAssignableFrom(returnType) -> emptyList<Any>()
        else -> null
    }
}
