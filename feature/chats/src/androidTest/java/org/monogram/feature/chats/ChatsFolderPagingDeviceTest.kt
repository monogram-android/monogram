package org.monogram.feature.chats

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.database.ChatCacheCursor
import org.monogram.core.database.MonogramDatabase
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PeerId
import org.monogram.network.bridge.DialogOffset
import org.monogram.network.bridge.DialogPage
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.UpdatesCursor
import java.lang.reflect.Proxy

class ChatsFolderPagingDeviceTest {
    private fun dialogPage(page: List<Chat>) =
        DialogPage(
            page,
            page
                .lastOrNull()
                ?.takeIf { page.size >= DIALOGS_NETWORK_PAGE }
                ?.let(::dialogsPageOffset)
                ?.let { (date, id, peer) -> DialogOffset(date, id, peer) },
        )

    @Test
    fun customFolderPagesArchivedSecondPageOnDevice() =
        runBlocking {
            val lateArchived =
                Chat(
                    id = PeerId(199),
                    title = "Archived second page",
                    archived = true,
                    lastMessageDate = 199,
                    lastMessageId = 1,
                    unreadCount = 2,
                )
            val archivePages = ArrayDeque<List<Chat>>()
            archivePages.addLast(
                (100L..139L).map { id ->
                    Chat(
                        PeerId(id),
                        "Archive $id",
                        archived = true,
                        lastMessageDate = id,
                        lastMessageId = 1
                    )
                },
            )
            archivePages.addLast(listOf(lateArchived))
            val folderCalls = mutableListOf<Int>()
            val client =
                Proxy.newProxyInstance(
                    MtprotoClient::class.java.classLoader,
                    arrayOf(MtprotoClient::class.java),
                ) { proxy, method, args ->
                    when (method.name) {
                        "equals" -> {
                            proxy === args?.firstOrNull()
                        }

                        "hashCode" -> {
                            System.identityHashCode(proxy)
                        }

                        "toString" -> {
                            "ChatsFolderPagingDeviceClient"
                        }

                        "libraryVersion" -> {
                            "test"
                        }

                        "updates", "sessionLost" -> {
                            emptyFlow<Nothing>()
                        }

                        "connect" -> {
                            Outcome.Ok(Unit)
                        }

                        "getChatsPage" -> {
                            Outcome.Ok(
                                dialogPage(
                                    listOf(
                                        Chat(
                                            PeerId(1),
                                            "Main",
                                            lastMessageDate = 1,
                                            lastMessageId = 1
                                        )
                                    ),
                                ),
                            )
                        }

                        "loadMoreChatsPage" -> {
                            Outcome.Ok(dialogPage(emptyList()))
                        }

                        "loadMoreFolderChatsPage" -> {
                            val folderId = args!![0] as Int
                            folderCalls += folderId
                            if (folderId != ARCHIVE_FOLDER_WIRE_ID) {
                                Outcome.Err(FOLDER_ID_INVALID)
                            } else {
                                Outcome.Ok(
                                    dialogPage(
                                        if (archivePages.isEmpty()) emptyList() else archivePages.removeFirst(),
                                    ),
                                )
                            }
                        }

                        "getNotifySettings" -> {
                            Outcome.Ok(NotifySettings())
                        }

                        "getFolders" -> {
                            Outcome.Ok(emptyList<Folder>())
                        }

                        "getProfile" -> {
                            Outcome.Err("unavailable")
                        }

                        "getUpdatesState" -> {
                            Outcome.Ok(UpdatesCursor(0, 0, 0, 0))
                        }

                        "close" -> {
                            Unit
                        }

                        else -> {
                            defaultOutcome(method.returnType)
                        }
                    }
                } as MtprotoClient

            lateinit var store: ChatsStore
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                store =
                    ChatsStoreFactory(
                        DefaultStoreFactory(),
                        client,
                        warmup = null,
                        sessionStore = null,
                    ).create()
                store.accept(ChatsStore.Intent.FolderSelected(7))
            }
            try {
                withTimeout(10_000) {
                    while (store.state.chats.none { it.id == lateArchived.id } || store.state.loadingMore) delay(
                        10
                    )
                }
                val folder =
                    Folder(
                        id = 7,
                        title = "Work",
                        chatIds = listOf(lateArchived.id),
                        excludeArchived = true,
                    )
                val visible = visibleChats(store.state.chats, listOf(folder), 7)
                assertEquals(2, folderCalls.count { it == ARCHIVE_FOLDER_WIRE_ID })
                assertFalse(folderCalls.contains(7))
                assertEquals(listOf(lateArchived.id), visible.map { it.id })
                assertEquals(FolderUnreadBadge(unmuted = 1, muted = 0), folderUnreadBadge(visible))
                assertFalse(store.state.hasMore)
                val completedCalls = folderCalls.toList()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    store.accept(ChatsStore.Intent.FolderSelected(null))
                    store.accept(ChatsStore.Intent.FolderSelected(7))
                    repeat(3) { store.accept(ChatsStore.Intent.LoadMore) }
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(completedCalls, folderCalls.toList())
                assertFalse(store.state.hasMore)
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { store.dispose() }
            }
        }

    @Test
    fun roomCatalogTerminalPageDoesNotHideFolderMember() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val tail = Chat(PeerId(10), "Visible tail", lastMessageDate = 10, lastMessageId = 1)
            val member = Chat(PeerId(11), "Folder member", lastMessageDate = 10, lastMessageId = 1)
            val rawOffset = DialogOffset(10, 1, 10)
            val folder = Folder(7, "Work", chatIds = listOf(member.id))
            val catalogScanned = CompletableDeferred<Unit>()
            val calls = mutableListOf<Pair<Int, DialogOffset>>()
            val client =
                Proxy.newProxyInstance(
                    MtprotoClient::class.java.classLoader,
                    arrayOf(MtprotoClient::class.java),
                ) { _, method, args ->
                    when (method.name) {
                        "updates", "sessionLost" -> {
                            emptyFlow<Nothing>()
                        }

                        "getNotifySettings" -> {
                            Outcome.Ok(NotifySettings())
                        }

                        "getFolders" -> {
                            Outcome.Ok(listOf(folder))
                        }

                        "getProfile" -> {
                            Outcome.Err("unavailable")
                        }

                        "getChatsPage" -> {
                            Outcome.Ok(DialogPage(listOf(tail), rawOffset))
                        }

                        "loadMoreFolderChatsPage" -> {
                            val wire = args!![0] as Int
                            val offset =
                                DialogOffset(args[1] as Int, args[2] as Int, args[3] as Long)
                            calls += wire to offset
                            when (wire) {
                                MAIN_FOLDER_WIRE_ID -> {
                                    Outcome.Ok(DialogPage(listOf(member), null))
                                }

                                ARCHIVE_FOLDER_WIRE_ID -> {
                                    // The archive scan starts only after the main catalog handles its terminal page.
                                    catalogScanned.complete(Unit)
                                    Outcome.Ok(DialogPage(emptyList(), null))
                                }

                                else -> {
                                    error("Unexpected folder stream: $wire")
                                }
                            }
                        }

                        else -> {
                            defaultOutcome(method.returnType)
                        }
                    }
                } as MtprotoClient

            withContext(Dispatchers.Main) {
                val db = Room.inMemoryDatabaseBuilder(
                    instrumentation.targetContext,
                    MonogramDatabase::class.java
                ).build()
                val warmup = OfflineWarmup(db)
                val store = ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup,
                    sessionStore = null
                ).create()
                try {
                    withTimeout(10_000) { catalogScanned.await() }
                    assertEquals(member.id, warmup.chat(member.id)?.id)
                    assertEquals(
                        emptyList<Chat>(),
                        warmup.chatsAfter(ChatCacheCursor.from(tail), DIALOGS_NETWORK_PAGE)
                    )
                    val mainCall = MAIN_FOLDER_WIRE_ID to rawOffset
                    assertEquals(listOf(mainCall), calls.filter { it.first == MAIN_FOLDER_WIRE_ID })
                    assertEquals(listOf(tail.id), store.state.chats.map { it.id })

                    val catalogCalls = calls.toList()
                    store.accept(ChatsStore.Intent.FolderSelected(folder.id, folder))
                    withTimeout(10_000) {
                        while (store.state.folderActivation == null) delay(10)
                    }
                    val activation = checkNotNull(store.state.folderActivation)
                    assertEquals(listOf(member.id), activation.cachedMembers.map { it.id })
                    assertEquals(0, activation.remainingPages)
                    store.accept(
                        ChatsStore.Intent.FolderUnderfilled(
                            folder.id,
                            activation.remainingPages
                        )
                    )
                    assertEquals(catalogCalls, calls)
                    assertEquals(listOf(tail.id), store.state.chats.map { it.id })

                    store.accept(ChatsStore.Intent.FolderSelected(null))
                    store.accept(ChatsStore.Intent.LoadMore)
                    withTimeout(10_000) {
                        while (store.state.loadingMore) delay(10)
                    }
                    assertEquals(catalogCalls + mainCall, calls)
                    assertEquals(
                        listOf(member.id),
                        visibleChats(store.state.chats, listOf(folder), folder.id).map { it.id })
                    assertFalse(store.state.hasMore)
                } finally {
                    store.dispose()
                    db.close()
                }
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun defaultOutcome(returnType: Class<*>): Any? =
        when {
            returnType == java.lang.Boolean.TYPE -> false
            returnType == java.lang.Integer.TYPE -> 0
            returnType == java.lang.Long.TYPE -> 0L
            returnType == java.lang.Void.TYPE -> Unit
            Outcome::class.java.isAssignableFrom(returnType) -> Outcome.Err("unsupported")
            List::class.java.isAssignableFrom(returnType) -> emptyList<Any>()
            else -> null
        }
}
