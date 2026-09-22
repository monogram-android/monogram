package org.monogram.feature.chats

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
import org.monogram.core.database.dao.ChatReadState
import org.monogram.core.models.ARCHIVE_FOLDER_ID
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
import java.util.ArrayDeque

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
    fun rejectedCustomFolderPagesArchiveStreamForArchivedMembers() = runTest {
        val archived = Chat(
            PeerId(99),
            "Archived late member",
            archived = true,
            unreadCount = 2,
            lastMessageDate = 99,
            lastMessageId = 1,
        )
        val archivePageOne = (100L..139L).map { id ->
            Chat(PeerId(id), "Archive $id", archived = true, lastMessageDate = id, lastMessageId = 1)
        }
        val client = StubClient(
            chats = Outcome.Ok(
                (1L..40L).map { id ->
                    Chat(
                        PeerId(id),
                        "Initial archive $id",
                        archived = true,
                        lastMessageDate = id,
                        lastMessageId = 1,
                    )
                },
            ),
        ).apply {
            archivePages.addLast(archivePageOne)
            archivePages.addLast(listOf(archived))
        }
        val store = ChatsStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
        ).create()
        try {
            store.accept(ChatsStore.Intent.FolderSelected(7))
            advanceUntilIdle()
            val folder = Folder(
                id = 7,
                title = "Work",
                chatIds = listOf(archived.id),
                excludeArchived = true,
            )
            val visible = visibleChats(store.state.chats, listOf(folder), 7)
            assertTrue("folder calls=${client.folderCalls}", client.folderCalls.contains(7))
            assertTrue(
                "archive calls=${client.folderCalls}",
                client.folderCalls.count { it == ARCHIVE_FOLDER_WIRE_ID } >= 2,
            )
            assertEquals(listOf(99L), visible.map { it.id.value })
            assertEquals(FolderUnreadBadge(unmuted = 1, muted = 0), folderUnreadBadge(visible))

            // Both fallback streams are now exhausted and must terminate cleanly.
            store.accept(ChatsStore.Intent.LoadMore)
            advanceUntilIdle()
            assertFalse(store.state.hasMore)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun exhaustedMainStreamDoesNotStarveArchiveFallbackOrLeakOnFolderSwitch() = runTest {
        val archived = Chat(
            PeerId(199),
            "Archived second page",
            archived = true,
            lastMessageDate = 199,
            lastMessageId = 1,
        )
        val client = StubClient(
            chats = Outcome.Ok(listOf(Chat(PeerId(1), "Main", lastMessageDate = 1, lastMessageId = 1))),
        ).apply {
            archivePages.addLast((100L..139L).map { id ->
                Chat(PeerId(id), "Archive $id", archived = true, lastMessageDate = id, lastMessageId = 1)
            })
            archivePages.addLast(listOf(archived))
        }
        val store = ChatsStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
        ).create()
        try {
            store.accept(ChatsStore.Intent.FolderSelected(7))
            advanceUntilIdle()
            assertTrue(client.folderCalls.count { it == ARCHIVE_FOLDER_WIRE_ID } >= 2)
            assertTrue(
                visibleChats(
                    store.state.chats,
                    listOf(Folder(7, "Work", chatIds = listOf(archived.id), excludeArchived = true)),
                    7,
                ).any { it.id == archived.id },
            )
            assertFalse(store.state.hasMore)
            store.accept(ChatsStore.Intent.FolderSelected(null))
            advanceUntilIdle()
            assertFalse(store.state.hasMore)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun switchingFoldersWhilePagingDefersAndStartsTheNewFolderLoad() = runTest {
        val client = StubClient(
            chats = Outcome.Ok(listOf(Chat(PeerId(1), "Main", lastMessageDate = 1, lastMessageId = 1))),
        ).apply {
            blockedFolderId = 7
        }
        val store = ChatsStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(ChatsStore.Intent.FolderSelected(7))
            runCurrent()
            assertTrue(client.blockedFolderStarted.isCompleted)

            store.accept(ChatsStore.Intent.FolderSelected(8))
            runCurrent()
            assertEquals(listOf(7), client.folderCalls)

            client.releaseBlockedFolder.complete(Unit)
            advanceUntilIdle()

            assertTrue(client.folderCalls.contains(8))
            assertFalse(store.state.loadingMore)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun switchingFoldersDuringCachedTailPagingStartsArchiveRequest() = runTest {
        val client = StubClient(
            chats = Outcome.Ok((1L..80L).map { id ->
                Chat(PeerId(id), "Main $id", lastMessageDate = id, lastMessageId = 1)
            }),
        )
        val store = ChatsStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
        ).create()
        try {
            repeat(20) {
                runCurrent()
                if (store.state.chats.isNotEmpty()) return@repeat
            }
            if (!store.state.loadingMore) store.accept(ChatsStore.Intent.LoadMore)
            runCurrent()
            // Select while the cache owner is either draining or about to release its gate.
            store.accept(ChatsStore.Intent.FolderSelected(ARCHIVE_FOLDER_ID))
            runCurrent()
            advanceUntilIdle()
            assertTrue(client.folderCalls.contains(ARCHIVE_FOLDER_WIRE_ID))
        } finally {
            store.dispose()
        }
    }

    @Test
    fun switchingFoldersDuringRoomPagingStartsArchiveRequest() = runTest {
        val roomStarted = CompletableDeferred<Unit>()
        val releaseRoom = CompletableDeferred<Unit>()
        val warmup = object : OfflineWarmup(db = null) {
            override suspend fun chatsWindow(limit: Int, archiveLimit: Int): List<Chat> = emptyList()
            override suspend fun mainListCount(): Int = 2
            override suspend fun chatsExcluding(excludeIds: List<Long>, limit: Int): List<Chat> {
                roomStarted.complete(Unit)
                releaseRoom.await()
                return listOf(Chat(PeerId(2), "Room", lastMessageDate = 2, lastMessageId = 1))
            }
        }
        val client = StubClient(
            chats = Outcome.Ok(listOf(Chat(PeerId(1), "Main", lastMessageDate = 1, lastMessageId = 1))),
        )
        val store = ChatsStoreFactory(
            DefaultStoreFactory(), client, warmup, sessionStore = null,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(ChatsStore.Intent.LoadMore)
            runCurrent()
            assertTrue(roomStarted.isCompleted)
            store.accept(ChatsStore.Intent.FolderSelected(ARCHIVE_FOLDER_ID))
            runCurrent()
            releaseRoom.complete(Unit)
            advanceUntilIdle()
            assertTrue(client.folderCalls.contains(ARCHIVE_FOLDER_WIRE_ID))
        } finally {
            store.dispose()
        }
    }

    @Test
    fun archivePageSurvivesChatsChanged() = runTest {
        val archived = archiveChats(20)
        val client = StubClient(
            chats = Outcome.Ok(listOf(Chat(PeerId(100), "Main", lastMessageDate = 100, lastMessageId = 1))),
        ).apply {
            archivePages.addLast(archived)
        }
        val store = ChatsStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(ChatsStore.Intent.FolderSelected(ARCHIVE_FOLDER_ID))
            advanceUntilIdle()
            assertEquals(20, visibleArchive(store).size)
            client.events.emit(
                MtprotoUpdate.ChatsChanged(listOf(archived.last().copy(title = "Updated"))),
            )
            advanceUntilIdle()
            val visible = visibleArchive(store)
            assertEquals(20, visible.size)
            assertEquals("Updated", visible.first { it.id.value == 20L }.title)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun delayedMainRefreshDoesNotCollapseArchiveOrResetPaging() = runTest {
        val archived = archiveChats(20)
        val client = StubClient(
            chats = Outcome.Ok(listOf(Chat(PeerId(100), "Main", lastMessageDate = 100, lastMessageId = 1))),
        ).apply {
            archivePages.addLast(archived)
            chatResponses.addLast(
                Outcome.Ok(
                    (200L..239L).map { id ->
                        Chat(PeerId(id), "Main $id", lastMessageDate = id, lastMessageId = 1)
                    },
                ),
            )
        }
        val mergeEntered = CompletableDeferred<Unit>()
        val releaseMerge = CompletableDeferred<Unit>()
        var hookArmed = false
        val store = ChatsStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = null,
            sessionStore = null,
            refreshMergeHook = {
                if (hookArmed) {
                    mergeEntered.complete(Unit)
                    releaseMerge.await()
                }
            },
        ).create()
        try {
            advanceUntilIdle()
            store.accept(ChatsStore.Intent.FolderSelected(ARCHIVE_FOLDER_ID))
            advanceUntilIdle()
            assertEquals(20, visibleArchive(store).size)
            assertFalse(store.state.hasMore)
            val archiveCalls = client.folderCalls.count { it == ARCHIVE_FOLDER_WIRE_ID }
            hookArmed = true
            store.accept(ChatsStore.Intent.Refresh)
            mergeEntered.await()
            releaseMerge.complete(Unit)
            advanceUntilIdle()
            assertEquals(20, visibleArchive(store).size)
            assertFalse(store.state.hasMore)
            assertEquals(
                archiveCalls,
                client.folderCalls.count { it == ARCHIVE_FOLDER_WIRE_ID },
            )
        } finally {
            releaseMerge.complete(Unit)
            store.dispose()
        }
    }

    @Test
    fun openingArchiveExposesCachedArchivedRowsWithoutWaitingForServer() = runTest {
        val archived = archiveChats(8)
        val warmup = object : OfflineWarmup() {
            override suspend fun chats() =
                listOf(Chat(PeerId(100), "Main", lastMessageDate = 100, lastMessageId = 1)) +
                    archived
        }
        val client = StubClient(
            chats = Outcome.Ok(listOf(Chat(PeerId(100), "Main", lastMessageDate = 100, lastMessageId = 1))),
        )
        val store = ChatsStoreFactory(
            DefaultStoreFactory(), client, warmup, sessionStore = null,
        ).create()
        try {
            advanceUntilIdle()
            assertEquals(3, visibleArchive(store).size)
            store.accept(ChatsStore.Intent.FolderSelected(ARCHIVE_FOLDER_ID))
            advanceUntilIdle()
            assertEquals((8L downTo 1L).toList(), visibleArchive(store).map { it.id.value })
        } finally {
            store.dispose()
        }
    }

    @Test
    fun exhaustedArchiveRepublishDoesNotHideRowsOrRequestAgain() = runTest {
        val archived = archiveChats(20)
        val client = StubClient(
            chats = Outcome.Ok(listOf(Chat(PeerId(100), "Main", lastMessageDate = 100, lastMessageId = 1))),
        ).apply {
            archivePages.addLast(archived)
        }
        val store = ChatsStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(ChatsStore.Intent.FolderSelected(ARCHIVE_FOLDER_ID))
            advanceUntilIdle()
            assertEquals(20, visibleArchive(store).size)
            assertFalse(store.state.hasMore)
            val archiveCalls = client.folderCalls.count { it == ARCHIVE_FOLDER_WIRE_ID }
            client.events.emit(
                MtprotoUpdate.ChatsChanged(listOf(archived.last().copy(title = "Updated"))),
            )
            advanceUntilIdle()
            store.accept(ChatsStore.Intent.LoadMore)
            advanceUntilIdle()
            assertEquals(20, visibleArchive(store).size)
            assertFalse(store.state.hasMore)
            assertEquals(
                archiveCalls,
                client.folderCalls.count { it == ARCHIVE_FOLDER_WIRE_ID },
            )
        } finally {
            store.dispose()
        }
    }

    @Test
    fun customFolderKeepsArchivedMembersAfterChatsChanged() = runTest {
        val archived = Chat(
            PeerId(99),
            "Archived late member",
            archived = true,
            unreadCount = 2,
            lastMessageDate = 99,
            lastMessageId = 1,
        )
        val client = StubClient(
            chats = Outcome.Ok(
                (1L..40L).map { id ->
                    Chat(
                        PeerId(id),
                        "Initial archive $id",
                        archived = true,
                        lastMessageDate = id,
                        lastMessageId = 1,
                    )
                },
            ),
        ).apply {
            archivePages.addLast((100L..139L).map { id ->
                Chat(PeerId(id), "Archive $id", archived = true, lastMessageDate = id, lastMessageId = 1)
            })
            archivePages.addLast(listOf(archived))
        }
        val store = ChatsStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
        ).create()
        try {
            store.accept(ChatsStore.Intent.FolderSelected(7))
            advanceUntilIdle()
            val folder = Folder(
                id = 7,
                title = "Work",
                chatIds = listOf(archived.id),
                excludeArchived = true,
            )
            assertEquals(
                listOf(99L),
                visibleChats(store.state.chats, listOf(folder), 7).map { it.id.value },
            )
            client.events.emit(MtprotoUpdate.ChatsChanged(listOf(archived.copy(title = "Renamed"))))
            advanceUntilIdle()
            assertEquals(
                listOf(99L),
                visibleChats(store.state.chats, listOf(folder), 7).map { it.id.value },
            )
        } finally {
            store.dispose()
        }
    }

    @Test
    fun incomingChatUpdatePreservesUnfinishedNetworkPagination() = runTest {
        val initial = (1L..40L).map { id ->
            Chat(
                PeerId(id),
                "Chat $id",
                archived = true,
                lastMessageDate = id,
                lastMessageId = 1,
            )
        }
        val client = StubClient(chats = Outcome.Ok(initial))
        val store = ChatsStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
        ).create()
        try {
            advanceUntilIdle()
            assertTrue(store.state.hasMore)
            client.events.emit(MtprotoUpdate.ChatsChanged(listOf(initial.first().copy(title = "Updated"))))
            advanceUntilIdle()
            assertTrue(store.state.hasMore)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun refreshMergeLockPreservesReadUpdateBeforePublication() = runTest {
        val target = Chat(
            PeerId(999),
            "Target",
            unreadCount = 3,
            lastMessageDate = 999,
            lastMessageId = 1,
        )
        val refreshed = (1L..40L).map { id ->
            Chat(PeerId(id), "Chat $id", lastMessageDate = id, lastMessageId = 1)
        } + target
        val client = StubClient(chats = Outcome.Ok(listOf(target))).apply {
            chatResponses.addLast(Outcome.Ok(refreshed))
        }
        val mergeEntered = CompletableDeferred<Unit>()
        val releaseMerge = CompletableDeferred<Unit>()
        var hookArmed = false
        val store = ChatsStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = null,
            sessionStore = null,
            refreshMergeHook = {
                if (hookArmed) {
                    mergeEntered.complete(Unit)
                    releaseMerge.await()
                }
            },
        ).create()
        try {
            advanceUntilIdle()
            hookArmed = true
            store.accept(ChatsStore.Intent.Refresh)
            mergeEntered.await()
            store.accept(ChatsStore.Intent.MarkRead(listOf(target.id)))
            releaseMerge.complete(Unit)
            advanceUntilIdle()
            val row = store.state.chats.first { it.id == target.id }
            assertEquals(0, row.unreadCount)
            assertEquals(1, row.readInboxMaxId)
        } finally {
            releaseMerge.complete(Unit)
            store.dispose()
        }
    }

    @Test
    fun roomReadStateWaitsForRefreshPublicationLock() = runTest {
        val target = Chat(
            PeerId(999),
            "Target",
            unreadCount = 3,
            lastMessageDate = 999,
            lastMessageId = 1,
        )
        val readStates = MutableSharedFlow<List<ChatReadState>>(replay = 1, extraBufferCapacity = 1)
        val warmup = object : OfflineWarmup(db = null) {
            override fun observeReadStates() = readStates
        }
        val client = StubClient(chats = Outcome.Ok(listOf(target))).apply {
            chatResponses.addLast(Outcome.Ok(listOf(target)))
        }
        val mergeEntered = CompletableDeferred<Unit>()
        val releaseMerge = CompletableDeferred<Unit>()
        val observerAttempted = CompletableDeferred<Unit>()
        val releaseObserver = CompletableDeferred<Unit>()
        var hookArmed = false
        val store = ChatsStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup,
            sessionStore = null,
            refreshMergeHook = {
                if (hookArmed) {
                    mergeEntered.complete(Unit)
                    releaseMerge.await()
                }
            },
            readStateHook = {
                if (hookArmed) {
                    observerAttempted.complete(Unit)
                    releaseObserver.await()
                }
            },
        ).create()
        try {
            advanceUntilIdle()
            hookArmed = true
            store.accept(ChatsStore.Intent.Refresh)
            mergeEntered.await()
            readStates.emit(listOf(ChatReadState(target.id.value, 1, 0)))
            observerAttempted.await()
            releaseMerge.complete(Unit)
            releaseObserver.complete(Unit)
            advanceUntilIdle()
            val row = store.state.chats.first { it.id == target.id }
            assertEquals(0, row.unreadCount)
            assertEquals(1, row.readInboxMaxId)
        } finally {
            releaseMerge.complete(Unit)
            releaseObserver.complete(Unit)
            store.dispose()
        }
    }

    @Test
    fun listRebuildDoesNotRestoreUnreadAfterReadUpdate() = runTest {
        val targetId = PeerId(999)
        val target = Chat(
            targetId,
            "Target",
            unreadCount = 3,
            lastMessageDate = 999,
            lastMessageId = 1,
        )
        val initial = (1L..40L).map { id ->
            Chat(PeerId(id), "Chat $id", lastMessageDate = id, lastMessageId = 1)
        } + target
        val client = StubClient(chats = Outcome.Ok(listOf(target)))
        val mergeEntered = CompletableDeferred<Unit>()
        val releaseMerge = CompletableDeferred<Unit>()
        var hookArmed = false
        val store = ChatsStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = null,
            sessionStore = null,
            refreshMergeHook = {
                if (hookArmed) {
                    mergeEntered.complete(Unit)
                    releaseMerge.await()
                }
            },
        ).create()
        try {
            advanceUntilIdle()
            runCurrent()
            client.events.emit(MtprotoUpdate.ChatsChanged(initial))
            runCurrent()
            advanceUntilIdle()
            hookArmed = true
            val rebuild = launch {
                client.events.emit(
                    MtprotoUpdate.ChatsChanged(listOf(target.copy(title = "Updated"))),
                )
            }
            mergeEntered.await()
            store.accept(ChatsStore.Intent.MarkRead(listOf(targetId)))
            releaseMerge.complete(Unit)
            rebuild.join()
            advanceUntilIdle()
            client.events.emit(MtprotoUpdate.ReadInbox(targetId, maxId = 2, stillUnread = 0))
            advanceUntilIdle()
            val row = store.state.chats.first { it.id == targetId }
            assertEquals(0, row.unreadCount)
            assertEquals(2, row.readInboxMaxId)
            assertTrue(row.title == target.title || row.title == "Updated")
        } finally {
            releaseMerge.complete(Unit)
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

    private fun archiveChats(count: Int): List<Chat> =
        (1L..count.toLong()).map { id ->
            Chat(
                PeerId(id),
                "Archived $id",
                archived = true,
                lastMessageDate = id,
                lastMessageId = 1,
            )
        }

    private fun visibleArchive(store: ChatsStore): List<Chat> =
        visibleChats(store.state.chats, emptyList(), ARCHIVE_FOLDER_ID)

    private open class StubClient(
        private val chats: Outcome<List<Chat>>,
        private val defaults: NotifySettings = NotifySettings(),
        private val failNotifySettings: Boolean = false,
        private val profile: Outcome<Profile> = Outcome.Err("unavailable"),
    ) : MtprotoClient {
        val reads = mutableListOf<Pair<PeerId, Int>>()
        val folderCalls = mutableListOf<Int>()
        val mainPages = ArrayDeque<List<Chat>>()
        val chatResponses = ArrayDeque<Outcome<List<Chat>>>()
        val archivePages = ArrayDeque<List<Chat>>()
        var blockedFolderId: Int? = null
        val blockedFolderStarted = CompletableDeferred<Unit>()
        val releaseBlockedFolder = CompletableDeferred<Unit>()
        val events = MutableSharedFlow<MtprotoUpdate>(extraBufferCapacity = 8)
        var notifySettingsCalls = 0
        var profileCalls = 0
        override suspend fun getNotifySettings(peerKind: String, chatId: PeerId): Outcome<NotifySettings> {
            notifySettingsCalls++
            return if (failNotifySettings) Outcome.Err("offline") else Outcome.Ok(defaults)
        }
        override suspend fun connect() = Outcome.Ok(Unit)
        override suspend fun sendAuthCode(phone: String) = unused<AuthState.AwaitingCode>()
        override suspend fun signIn(phone: String, phoneCodeHash: String, code: String) = unused<AuthState>()
        override suspend fun checkPassword(password: String) = unused<AuthState.Authorized>()
        override suspend fun getChats(): Outcome<List<Chat>> =
            if (chatResponses.isEmpty()) chats else chatResponses.removeFirst()
        override suspend fun loadMoreChats(offsetDate: Int, offsetId: Int, offsetPeerId: Long): Outcome<List<Chat>> =
            Outcome.Ok(if (mainPages.isEmpty()) emptyList() else mainPages.removeFirst())
        override suspend fun loadMoreFolderChats(
            folderId: Int,
            offsetDate: Int,
            offsetId: Int,
            offsetPeerId: Long,
        ): Outcome<List<Chat>> {
            folderCalls += folderId
            if (folderId == blockedFolderId) {
                if (!blockedFolderStarted.isCompleted) blockedFolderStarted.complete(Unit)
                releaseBlockedFolder.await()
                return Outcome.Err(FOLDER_ID_INVALID)
            }
            if (folderId != ARCHIVE_FOLDER_WIRE_ID) return Outcome.Err(FOLDER_ID_INVALID)
            return Outcome.Ok(if (archivePages.isEmpty()) emptyList() else archivePages.removeFirst())
        }
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
        override suspend fun getProfile(peerId: PeerId): Outcome<Profile> {
            profileCalls++
            return profile
        }
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
