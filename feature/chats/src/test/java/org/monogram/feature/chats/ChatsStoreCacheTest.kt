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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.database.ChatCacheCursor
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.dao.ChatReadState
import org.monogram.core.models.ARCHIVE_FOLDER_ID
import org.monogram.core.models.AuthState
import org.monogram.core.models.Chat
import org.monogram.core.models.ContactsSearch
import org.monogram.core.models.Folder
import org.monogram.core.models.GlobalMessageSearch
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.SearchPeer
import org.monogram.network.bridge.DialogOffset
import org.monogram.network.bridge.DialogPage
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.bridge.UpdatesCursor
import java.util.ArrayDeque

@OptIn(ExperimentalCoroutinesApi::class)
class ChatsStoreCacheTest {
    private fun ChatsStoreFactory(
        storeFactory: com.arkivanov.mvikotlin.core.store.StoreFactory,
        client: MtprotoClient,
        warmup: OfflineWarmup?,
        sessionStore: org.monogram.core.database.SessionMetadataStore?,
        notifications: org.monogram.core.common.push.NotificationLocalStore? = null,
        refreshMergeHook: (suspend () -> Unit)? = null,
        readStateHook: (suspend () -> Unit)? = null,
    ) = ChatsStoreFactory(
        storeFactory,
        client,
        warmup,
        sessionStore,
        notifications,
        refreshMergeHook,
        readStateHook,
        ioContext = Dispatchers.Main.immediate,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun cachedFirstPageDoesNotRetryFloodAfterNetworkRetryDelay() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val cached = Chat(PeerId(1), "Cached", lastMessageDate = 5, lastMessageId = 1)
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun chatsWindow(
                        limit: Int,
                        archiveLimit: Int,
                    ) = listOf(cached)

                    override suspend fun mainListCount() = 1
                }
            var calls = 0
            val client =
                object : StubClient(chats = Outcome.Ok(emptyList())) {
                    override suspend fun getChats(): Outcome<List<Chat>> {
                        calls++
                        return Outcome.Err("FLOOD_WAIT_17")
                    }
                }
            val store = ChatsStoreFactory(
                DefaultStoreFactory(),
                client,
                warmup,
                sessionStore = null
            ).create()
            try {
                advanceTimeBy(16_999)
                runCurrent()
                assertEquals(
                    cached.id,
                    store.state.chats
                        .single()
                        .id,
                )
                assertEquals(1, calls)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun protocolCatchupKeepsUpdatingAfterDialogsRefreshCompletes() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val release = CompletableDeferred<Unit>()
            val cached = Chat(PeerId(1), "Synthetic", lastMessageDate = 5, lastMessageId = 1)
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun chatsWindow(
                        limit: Int,
                        archiveLimit: Int,
                    ) = listOf(cached)

                    override suspend fun mainListCount() = 1
                }
            val client = StubClient(chats = Outcome.Ok(listOf(cached)))
            client.chatsGate = release
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup,
                    sessionStore = null,
                ).create()
            try {
                runCurrent()
                client.events.emit(MtprotoUpdate.SyncState(true))
                release.complete(Unit)
                advanceUntilIdle()
                assertFalse(store.state.loading)
                assertEquals(
                    cached.id,
                    store.state.chats
                        .single()
                        .id,
                )
                assertTrue(
                    "Unfinished protocol recovery must keep Updating visible",
                    store.state.syncing,
                )
                client.events.emit(MtprotoUpdate.SyncState(false))
                runCurrent()
                assertFalse(store.state.syncing)
            } finally {
                release.complete(Unit)
                store.dispose()
            }
        }

    @Test
    fun reconnectCatchupUpdatesAlreadyLoadedChats() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val client = StubClient(chats = Outcome.Ok(emptyList()))
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                advanceUntilIdle()
                assertFalse(store.state.syncing)
                client.events.emit(MtprotoUpdate.SyncState(true))
                runCurrent()
                assertTrue(store.state.syncing)
                client.events.emit(MtprotoUpdate.SyncState(false))
                runCurrent()
                assertFalse(store.state.syncing)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun protocolCompletionDoesNotClearAnOutstandingDialogsRefresh() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val release = CompletableDeferred<Unit>()
            val cached = Chat(PeerId(1), "Synthetic", lastMessageDate = 5, lastMessageId = 1)
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun chatsWindow(
                        limit: Int,
                        archiveLimit: Int,
                    ) = listOf(cached)

                    override suspend fun mainListCount() = 1
                }
            val client = StubClient(chats = Outcome.Ok(listOf(cached)))
            client.chatsGate = release
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup,
                    sessionStore = null,
                ).create()
            try {
                runCurrent()
                client.events.emit(MtprotoUpdate.SyncState(true))
                client.events.emit(MtprotoUpdate.SyncState(false))
                runCurrent()
                assertTrue(store.state.syncing)
                release.complete(Unit)
                advanceUntilIdle()
                assertFalse(store.state.syncing)
            } finally {
                release.complete(Unit)
                store.dispose()
            }
        }

    @Test
    fun cachePaintsBeforeDialogsRequest() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val cached = Chat(PeerId(1), "Ada", lastMessageDate = 5, lastMessageId = 1)
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun chatsWindow(
                        limit: Int,
                        archiveLimit: Int,
                    ) = listOf(cached)

                    override suspend fun mainListCount() = 1
                }
            val client = StubClient(chats = Outcome.Ok(emptyList()))
            client.chatsGate = release
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup,
                    sessionStore = null,
                ).create()
            try {
                runCurrent()
                assertEquals(
                    "Ada",
                    store.state.chats
                        .single()
                        .title,
                )
                assertTrue(store.state.fromCache)
                assertFalse(release.isCompleted)
            } finally {
                release.complete(Unit)
                store.dispose()
            }
        }

    @Test
    fun backgroundCatalogKeepsTheSmallWindowAndUpsertsLaterPages() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val first =
                List(DIALOGS_NETWORK_PAGE) { index ->
                    val id = index + 1
                    Chat(
                        PeerId(id.toLong()),
                        "C$id",
                        lastMessageDate = (1_000 - index).toLong(),
                        lastMessageId = id,
                    )
                }
            val later = Chat(PeerId(900), "Later", lastMessageDate = 10, lastMessageId = 900)
            val upserted = mutableListOf<Chat>()
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun upsertChats(chats: List<Chat>) {
                        upserted += chats
                    }
                }
            val client = StubClient(chats = Outcome.Ok(first))
            client.mainPages.add(listOf(later))
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup,
                    sessionStore = null,
                ).create()
            try {
                advanceUntilIdle()
                assertFalse(store.state.chats.any { it.id == later.id })
                assertTrue(
                    "offsets=${client.mainOffsets} upserted=${upserted.map { it.id.value }}",
                    upserted.any { it.id == later.id },
                )
                assertTrue(client.mainOffsets.none { it.first == 0 && it.second == 0 })
                assertTrue(client.historyCalls == 0)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun restartResumesCatalogFromTheOldestSavedRow() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val top = Chat(PeerId(5), "Top", lastMessageDate = 50, lastMessageId = 5)
            val oldest = Chat(PeerId(1), "Old", lastMessageDate = 9, lastMessageId = 1)
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun oldestListed(archived: Boolean): Chat? =
                        if (archived) null else oldest
                }
            val client = StubClient(chats = Outcome.Ok(listOf(top)))
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup,
                    sessionStore = null,
                ).create()
            try {
                advanceUntilIdle()
                assertEquals(listOf(Triple(9, 1, 1L)), client.mainOffsets)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun catalogEndBeyondRoomGapsDoesNotExhaustForegroundPaging() =
        runTest {
            val first = (100L downTo 61L).map { id ->
                Chat(
                    PeerId(id),
                    "Main $id",
                    lastMessageDate = id,
                    lastMessageId = 1
                )
            }
            val member = Chat(PeerId(60), "Missing member", lastMessageDate = 60, lastMessageId = 1)
            val oldest = Chat(PeerId(10), "Cached main", lastMessageDate = 10, lastMessageId = 1)
            val archived = Chat(
                PeerId(50),
                "Archived member",
                archived = true,
                lastMessageDate = 50,
                lastMessageId = 1
            )
            val oldestArchive = Chat(
                PeerId(5),
                "Cached archive",
                archived = true,
                lastMessageDate = 5,
                lastMessageId = 1
            )
            val folder = Folder(7, "Work", chatIds = listOf(member.id, archived.id))
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun chatsWindow(
                        limit: Int,
                        archiveLimit: Int,
                    ) = first

                    override suspend fun mainListCount() = first.size + 1

                    override suspend fun chatsAfter(
                        cursor: ChatCacheCursor,
                        limit: Int,
                    ) = if (cursor.id == first.last().id.value) listOf(oldest) else emptyList()

                    override suspend fun oldestListed(archived: Boolean) =
                        if (archived) oldestArchive else oldest
                }
            val archiveOffsets = mutableListOf<Long>()
            val client =
                object : StubClient(chats = Outcome.Ok(first)) {
                    override suspend fun loadMoreFolderChats(
                        folderId: Int,
                        offsetDate: Int,
                        offsetId: Int,
                        offsetPeerId: Long,
                    ): Outcome<List<Chat>> {
                        assertEquals(ARCHIVE_FOLDER_WIRE_ID, folderId)
                        archiveOffsets += offsetPeerId
                        return Outcome.Ok(
                            if (offsetPeerId == 0L) listOf(
                                archived,
                                oldestArchive
                            ) else emptyList()
                        )
                    }
                }.apply {
                    mainPages.addLast(emptyList())
                    mainPages.addLast(listOf(member, oldest))
                }
            val store = ChatsStoreFactory(
                DefaultStoreFactory(),
                client,
                warmup,
                sessionStore = null
            ).create()
            try {
                advanceUntilIdle()
                assertEquals(listOf(Triple(10, 1, 10L)), client.mainOffsets)
                assertEquals(listOf(5L), archiveOffsets)
                assertTrue(visibleChats(store.state.chats, listOf(folder), folder.id).isEmpty())

                store.accept(ChatsStore.Intent.FolderSelected(ARCHIVE_FOLDER_ID))
                advanceUntilIdle()
                assertEquals(listOf(5L, 0L), archiveOffsets)
                assertEquals(
                    listOf(archived.id, oldestArchive.id),
                    visibleArchive(store).map { it.id })
                assertFalse(store.state.hasMore)

                store.accept(ChatsStore.Intent.FolderSelected(folder.id))
                advanceUntilIdle()
                assertEquals(
                    listOf(archived.id),
                    visibleChats(store.state.chats, listOf(folder), folder.id).map { it.id })

                store.accept(ChatsStore.Intent.LoadMore)
                advanceUntilIdle()
                assertTrue(store.state.chats.any { it.id == oldest.id })
                assertEquals(listOf(Triple(10, 1, 10L)), client.mainOffsets)
                assertTrue(
                    "Room's sparse tail is not the end of the foreground stream",
                    store.state.hasMore
                )

                store.accept(ChatsStore.Intent.LoadMore)
                advanceUntilIdle()
                val expectedMainOffsets = listOf(Triple(10, 1, 10L), Triple(61, 1, 61L))
                assertEquals(expectedMainOffsets, client.mainOffsets)
                assertEquals(
                    listOf(member.id, archived.id),
                    visibleChats(store.state.chats, listOf(folder), folder.id).map { it.id })
                assertFalse(store.state.hasMore)
                assertFalse(store.state.loadingMore)

                store.accept(ChatsStore.Intent.LoadMore)
                advanceUntilIdle()
                assertEquals(expectedMainOffsets, client.mainOffsets)
                assertEquals(listOf(5L, 0L), archiveOffsets)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun catalogEndAtMappedCursorDoesNotExhaustRawForegroundContinuation() =
        runTest {
            val seed =
                listOf(Chat(PeerId(10), "Mapped preview", lastMessageDate = 10, lastMessageId = 1))
            val mappedCursor = DialogOffset(10, 1, 10)
            val rawContinuation = DialogOffset(90, 1, 90)
            val member = Chat(PeerId(60), "Missing chat", lastMessageDate = 60, lastMessageId = 1)
            val offsets = mutableListOf<DialogOffset>()
            val client =
                object : StubClient(chats = Outcome.Ok(seed)) {
                    override suspend fun getChatsPage(): Outcome<DialogPage> =
                        Outcome.Ok(DialogPage(seed, rawContinuation))

                    override suspend fun loadMoreFolderChatsPage(
                        folderId: Int,
                        offsetDate: Int,
                        offsetId: Int,
                        offsetPeerId: Long,
                    ): Outcome<DialogPage> {
                        assertEquals(MAIN_FOLDER_WIRE_ID, folderId)
                        val offset = DialogOffset(offsetDate, offsetId, offsetPeerId)
                        offsets += offset
                        val chats = if (offset == rawContinuation) listOf(member) else emptyList()
                        return Outcome.Ok(DialogPage(chats, null))
                    }
                }
            val store = ChatsStoreFactory(
                DefaultStoreFactory(),
                client,
                warmup = null,
                sessionStore = null
            ).create()
            try {
                advanceUntilIdle()
                assertEquals(listOf(mappedCursor), offsets)
                assertTrue(store.state.hasMore)
                assertFalse(store.state.chats.any { it.id == member.id })

                store.accept(ChatsStore.Intent.LoadMore)
                advanceUntilIdle()
                assertEquals(listOf(mappedCursor, rawContinuation), offsets)
                assertTrue(store.state.chats.any { it.id == member.id })
                assertFalse(store.state.hasMore)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun uncachedCatalogTerminalPageDoesNotHideFolderMember() =
        assertUncachedCatalogLeavesMemberFetchable(memberOnTerminalPage = true)

    @Test
    fun uncachedCatalogEmptyTerminalPageDoesNotHideEarlierFolderMember() =
        assertUncachedCatalogLeavesMemberFetchable(memberOnTerminalPage = false)

    @Test
    fun catalogTerminalPageForVisiblePeerDoesNotHideUpdatedContactMembership() =
        runTest {
            val displayed =
                Chat(PeerId(10), "Peer", lastMessageDate = 10, lastMessageId = 1, isContact = false)
            val updated = displayed.copy(isContact = true)
            val offset = DialogOffset(10, 1, 10)
            val folder = Folder(7, "Contacts", includeContacts = true)
            val requests = mutableListOf<Pair<Int, DialogOffset>>()
            val client =
                object : StubClient(chats = Outcome.Ok(listOf(displayed))) {
                    override suspend fun getChatsPage(): Outcome<DialogPage> =
                        Outcome.Ok(DialogPage(listOf(displayed), offset))

                    override suspend fun loadMoreFolderChatsPage(
                        folderId: Int,
                        offsetDate: Int,
                        offsetId: Int,
                        offsetPeerId: Long,
                    ): Outcome<DialogPage> {
                        requests += folderId to DialogOffset(offsetDate, offsetId, offsetPeerId)
                        val rows =
                            if (folderId == MAIN_FOLDER_WIRE_ID) listOf(updated) else emptyList()
                        return Outcome.Ok(DialogPage(rows, null))
                    }
                }
            val store = ChatsStoreFactory(
                DefaultStoreFactory(),
                client,
                warmup = null,
                sessionStore = null
            ).create()
            try {
                advanceUntilIdle()
                val mainRequest = MAIN_FOLDER_WIRE_ID to offset
                assertEquals(listOf(mainRequest), requests)
                assertFalse(
                    store.state.chats
                        .single()
                        .isContact,
                )
                assertTrue(visibleChats(store.state.chats, listOf(folder), folder.id).isEmpty())

                store.accept(ChatsStore.Intent.FolderSelected(folder.id))
                advanceUntilIdle()
                assertTrue(
                    store.state.chats
                        .single()
                        .isContact,
                )
                assertEquals(
                    listOf(updated.id),
                    visibleChats(store.state.chats, listOf(folder), folder.id).map { it.id })
                assertFalse(store.state.hasMore)
                val exhaustedRequests = listOf(
                    mainRequest,
                    ARCHIVE_FOLDER_WIRE_ID to DialogOffset(0, 0, 0),
                    mainRequest
                )
                assertEquals(exhaustedRequests, requests)

                store.accept(ChatsStore.Intent.FolderSelected(null))
                advanceUntilIdle()
                store.accept(ChatsStore.Intent.FolderSelected(folder.id))
                advanceUntilIdle()
                store.accept(ChatsStore.Intent.LoadMore)
                advanceUntilIdle()
                assertEquals(
                    listOf(updated.id),
                    visibleChats(store.state.chats, listOf(folder), folder.id).map { it.id })
                assertFalse(store.state.hasMore)
                assertEquals(exhaustedRequests, requests)
            } finally {
                store.dispose()
            }
        }

    private fun assertUncachedCatalogLeavesMemberFetchable(memberOnTerminalPage: Boolean) =
        runTest {
            val seed = listOf(Chat(PeerId(10), "Seed", lastMessageDate = 10, lastMessageId = 1))
            val seedOffset = DialogOffset(10, 1, 10)
            val member = Chat(PeerId(9), "Folder member", lastMessageDate = 9, lastMessageId = 1)
            val memberOffset = DialogOffset(9, 1, 9)
            val folder = Folder(7, "Work", chatIds = listOf(member.id))
            val mainOffsets = mutableListOf<DialogOffset>()
            var archiveRequests = 0
            val client =
                object : StubClient(chats = Outcome.Ok(seed)) {
                    override suspend fun getChatsPage(): Outcome<DialogPage> =
                        Outcome.Ok(DialogPage(seed, seedOffset))

                    override suspend fun loadMoreFolderChatsPage(
                        folderId: Int,
                        offsetDate: Int,
                        offsetId: Int,
                        offsetPeerId: Long,
                    ): Outcome<DialogPage> {
                        if (folderId == ARCHIVE_FOLDER_WIRE_ID) {
                            archiveRequests++
                            return Outcome.Ok(DialogPage(emptyList(), null))
                        }
                        assertEquals(MAIN_FOLDER_WIRE_ID, folderId)
                        val offset = DialogOffset(offsetDate, offsetId, offsetPeerId)
                        mainOffsets += offset
                        return when (offset) {
                            seedOffset -> Outcome.Ok(
                                DialogPage(
                                    listOf(member),
                                    memberOffset.takeUnless { memberOnTerminalPage })
                            )

                            memberOffset -> Outcome.Ok(DialogPage(emptyList(), null))
                            else -> error("Unexpected main offset: $offset")
                        }
                    }
                }
            val store = ChatsStoreFactory(
                DefaultStoreFactory(),
                client,
                warmup = null,
                sessionStore = null
            ).create()
            try {
                advanceUntilIdle()
                val scanOffsets = if (memberOnTerminalPage) listOf(seedOffset) else listOf(
                    seedOffset,
                    memberOffset
                )
                assertEquals(scanOffsets, mainOffsets)
                assertTrue(store.state.hasMore)

                store.accept(ChatsStore.Intent.FolderSelected(folder.id, folder))
                advanceUntilIdle()
                repeat(3) {
                    store.accept(
                        ChatsStore.Intent.FolderUnderfilled(
                            folder.id,
                            checkNotNull(store.state.folderActivation).remainingPages
                        )
                    )
                    advanceUntilIdle()
                }
                assertEquals(
                    listOf(member.id),
                    visibleChats(store.state.chats, listOf(folder), folder.id).map { it.id })
                assertEquals(scanOffsets + seedOffset, mainOffsets)
                assertEquals(1, archiveRequests)
                assertFalse(store.state.hasMore)

                store.accept(ChatsStore.Intent.FolderSelected(null))
                advanceUntilIdle()
                assertEquals(!memberOnTerminalPage, store.state.hasMore)
                store.accept(ChatsStore.Intent.FolderSelected(folder.id, folder))
                advanceUntilIdle()
                assertEquals(scanOffsets + seedOffset, mainOffsets)
                assertEquals(1, archiveRequests)
                store.accept(ChatsStore.Intent.LoadMore)
                advanceUntilIdle()
                assertEquals(
                    listOf(member.id),
                    visibleChats(store.state.chats, listOf(folder), folder.id).map { it.id })
                assertFalse(store.state.hasMore)
                assertEquals(scanOffsets + seedOffset, mainOffsets)
                assertEquals(1, archiveRequests)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun persistedWarmupStartsTheArchiveCatalogAtOffsetZero() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val warmup =
                object : OfflineWarmup() {
                    override val usesIoDispatcher = true

                    override suspend fun oldestListed(archived: Boolean): Chat? = null

                    override suspend fun upsertChats(chats: List<Chat>) = Unit
                }
            val client =
                StubClient(
                    chats =
                        Outcome.Ok(
                            listOf(
                                Chat(
                                    PeerId(1),
                                    "Top",
                                    lastMessageDate = 1,
                                    lastMessageId = 1,
                                ),
                            ),
                        ),
                )
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup,
                    sessionStore = null,
                ).create()
            try {
                advanceUntilIdle()
                assertEquals(listOf(ARCHIVE_FOLDER_WIRE_ID), client.folderCalls)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun catalogFloodWaitRetriesTheSamePage() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val first =
                List(DIALOGS_NETWORK_PAGE) { index ->
                    val id = index + 1
                    Chat(
                        PeerId(id.toLong()),
                        "C$id",
                        lastMessageDate = (1_000 - index).toLong(),
                        lastMessageId = id,
                    )
                }
            val later = Chat(PeerId(900), "Later", lastMessageDate = 10, lastMessageId = 900)
            val upserted = mutableListOf<Chat>()
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun upsertChats(chats: List<Chat>) {
                        upserted += chats
                    }
                }
            val client =
                StubClient(chats = Outcome.Ok(first)).apply {
                    mainFlood = "FLOOD_WAIT_1"
                    mainPages.add(listOf(later))
                }
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup,
                    sessionStore = null,
                ).create()
            try {
                advanceTimeBy(1_000)
                advanceUntilIdle()
                assertTrue(upserted.any { it.id == later.id })
                assertEquals(2, client.mainOffsets.size)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun scrollServesCachedRowsBeforeTheNetwork() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val first = Chat(PeerId(5), "Newer", lastMessageDate = 50, lastMessageId = 1)
            val older = Chat(PeerId(4), "Older", lastMessageDate = 40, lastMessageId = 1)
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun chatsWindow(
                        limit: Int,
                        archiveLimit: Int,
                    ) = listOf(first)

                    override suspend fun mainListCount() = 2

                    override suspend fun chatsAfter(
                        cursor: ChatCacheCursor,
                        limit: Int,
                    ) = listOf(older)
                }
            val client = StubClient(chats = Outcome.Ok(listOf(first)))
            client.mainPages.add(
                listOf(
                    Chat(
                        PeerId(3),
                        "Network",
                        lastMessageDate = 3,
                        lastMessageId = 3,
                    ),
                ),
            )
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup,
                    sessionStore = null,
                ).create()
            try {
                advanceUntilIdle()
                val offsetsBeforeScroll = client.mainOffsets.size
                store.accept(ChatsStore.Intent.LoadMore)
                advanceUntilIdle()
                assertTrue(store.state.chats.any { it.id == older.id })
                assertEquals(offsetsBeforeScroll, client.mainOffsets.size)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun loadMoreUsesKeysetCursor() =
        runTest {
            var seen: ChatCacheCursor? = null
            val first = Chat(PeerId(5), "Newer", lastMessageDate = 50, lastMessageId = 1)
            val warmup =
                object : OfflineWarmup(db = null) {
                    override suspend fun chatsWindow(
                        limit: Int,
                        archiveLimit: Int,
                    ) = listOf(first)

                    override suspend fun mainListCount() = 2

                    override suspend fun chatsAfter(
                        cursor: ChatCacheCursor,
                        limit: Int,
                    ): List<Chat> {
                        seen = cursor
                        return listOf(
                            Chat(
                                PeerId(4),
                                "Older",
                                lastMessageDate = 40,
                                lastMessageId = 1,
                            ),
                        )
                    }
                }
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    StubClient(chats = Outcome.Ok(listOf(first))),
                    warmup,
                    sessionStore = null,
                ).create()
            try {
                advanceUntilIdle()
                assertEquals(5L, seen?.id)
                assertEquals(50L, seen?.lastMessageDate)
                assertFalse(seen?.archived ?: true)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun cachePaintsBeforeFailedNetwork() {
        val cached = Chat(PeerId(1), "Ada")
        val warmup =
            object : OfflineWarmup() {
                override suspend fun chats() = listOf(cached)
            }
        val store =
            ChatsStoreFactory(
                DefaultStoreFactory(),
                StubClient(chats = Outcome.Err("offline")),
                warmup,
                sessionStore = null,
            ).create()
        try {
            assertEquals(
                "Ada",
                store.state.chats
                    .single()
                    .title,
            )
            assertTrue(store.state.fromCache)
            assertNull(store.state.error)
            assertTrue(!store.state.loading)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun customFolderPagesArchiveStreamForArchivedMembers() =
        runTest {
            val archived =
                Chat(
                    PeerId(99),
                    "Archived late member",
                    archived = true,
                    unreadCount = 2,
                    lastMessageDate = 99,
                    lastMessageId = 1,
                )
            val archivePageOne =
                (100L..139L).map { id ->
                    Chat(
                        PeerId(id),
                        "Archive $id",
                        archived = true,
                        lastMessageDate = id,
                        lastMessageId = 1,
                    )
                }
            val client =
                StubClient(
                    chats =
                        Outcome.Ok(
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
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                store.accept(ChatsStore.Intent.FolderSelected(7))
                advanceUntilIdle()
                val folder =
                    Folder(
                        id = 7,
                        title = "Work",
                        chatIds = listOf(archived.id),
                        excludeArchived = true,
                    )
                val visible = visibleChats(store.state.chats, listOf(folder), 7)
                assertFalse("custom filter sent as wire folder", client.folderCalls.contains(7))
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
    fun archivePeerErrorsStopAtAttemptLimitWithoutStarvingMainOnNextLoad() =
        runTest {
            val initialMain =
                (101L..140L).map { id ->
                    Chat(PeerId(id), "Main $id", lastMessageDate = id, lastMessageId = 1)
                }
            val nextMain =
                Chat(PeerId(100), "Next main chat", lastMessageDate = 100, lastMessageId = 1)
            val offsetPeers = mutableListOf<Long>()
            val client =
                object : StubClient(chats = Outcome.Ok(initialMain)) {
                    override suspend fun loadMoreChats(
                        offsetDate: Int,
                        offsetId: Int,
                        offsetPeerId: Long,
                    ): Outcome<List<Chat>> {
                        offsetPeers += offsetPeerId
                        return super.loadMoreChats(offsetDate, offsetId, offsetPeerId)
                    }

                    override suspend fun loadMoreFolderChats(
                        folderId: Int,
                        offsetDate: Int,
                        offsetId: Int,
                        offsetPeerId: Long,
                    ): Outcome<List<Chat>> {
                        assertEquals(ARCHIVE_FOLDER_WIRE_ID, folderId)
                        offsetPeers += offsetPeerId
                        return when (offsetPeerId) {
                            0L -> Outcome.Ok(archiveChats(DIALOGS_NETWORK_PAGE))
                            in 36L..40L -> Outcome.Err("PEER_ID_INVALID")
                            else -> Outcome.Ok(emptyList())
                        }
                    }
                }.apply {
                    mainPages.addLast(initialMain) // The background catalog sees an unchanged cursor.
                }
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun chats() = initialMain.take(1)
                }
            val store = ChatsStoreFactory(
                DefaultStoreFactory(),
                client,
                warmup,
                sessionStore = null
            ).create()
            try {
                advanceUntilIdle()
                offsetPeers.clear()
                client.mainPages.addLast(listOf(nextMain))
                store.accept(ChatsStore.Intent.FolderSelected(ARCHIVE_FOLDER_ID))
                advanceUntilIdle()
                store.accept(ChatsStore.Intent.FolderSelected(7))
                advanceUntilIdle()
                assertEquals(listOf(0L), offsetPeers)
                offsetPeers.clear()

                store.accept(ChatsStore.Intent.LoadMore)
                advanceUntilIdle()

                assertEquals(listOf(40L, 39L, 38L, 37L, 36L), offsetPeers)
                assertFalse(store.state.loadingMore)
                assertTrue(store.state.hasMore)
                offsetPeers.clear()

                store.accept(ChatsStore.Intent.LoadMore)
                advanceUntilIdle()

                assertEquals(listOf(140L, 35L), offsetPeers)
                assertTrue(store.state.chats.any { it.id == nextMain.id })
            } finally {
                store.dispose()
            }
        }

    @Test
    fun exhaustedMainStreamDoesNotStarveArchiveFallbackOrLeakOnFolderSwitch() =
        runTest {
            val archived =
                Chat(
                    PeerId(199),
                    "Archived second page",
                    archived = true,
                    lastMessageDate = 199,
                    lastMessageId = 1,
                )
            val client =
                StubClient(
                    chats =
                        Outcome.Ok(
                            listOf(
                                Chat(
                                    PeerId(1),
                                    "Main",
                                    lastMessageDate = 1,
                                    lastMessageId = 1,
                                ),
                            ),
                        ),
                ).apply {
                    archivePages.addLast(
                        (100L..139L).map { id ->
                            Chat(
                                PeerId(id),
                                "Archive $id",
                                archived = true,
                                lastMessageDate = id,
                                lastMessageId = 1,
                            )
                        },
                    )
                    archivePages.addLast(listOf(archived))
                }
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                store.accept(ChatsStore.Intent.FolderSelected(7))
                advanceUntilIdle()
                assertTrue(client.folderCalls.count { it == ARCHIVE_FOLDER_WIRE_ID } >= 2)
                assertTrue(
                    visibleChats(
                        store.state.chats,
                        listOf(
                            Folder(
                                7,
                                "Work",
                                chatIds = listOf(archived.id),
                                excludeArchived = true,
                            ),
                        ),
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
    fun customFolderCanLoadOlderArchivedMemberAfterShortMainRefresh() =
        runTest {
            val main = Chat(PeerId(100), "Main", lastMessageDate = 200, lastMessageId = 1)
            val cachedOlder =
                Chat(PeerId(101), "Cached main", lastMessageDate = 190, lastMessageId = 1)
            val archived = Chat(
                PeerId(99),
                "Archived member",
                archived = true,
                lastMessageDate = 99,
                lastMessageId = 1
            )
            val folder = Folder(7, "Work", chatIds = listOf(archived.id), excludeArchived = true)
            val client =
                StubClient(chats = Outcome.Ok(listOf(main))).apply {
                    archivePages.addLast(archiveChats(DIALOGS_NETWORK_PAGE).map {
                        it.copy(
                            lastMessageDate = it.id.value + 100
                        )
                    })
                    archivePages.addLast(listOf(archived))
                }
            var cacheReady = false
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun chatsWindow(
                        limit: Int,
                        archiveLimit: Int,
                    ) = if (cacheReady) listOf(main) else emptyList()

                    override suspend fun mainListCount() = if (cacheReady) 2 else 0

                    override suspend fun chatsAfter(
                        cursor: ChatCacheCursor,
                        limit: Int,
                    ) =
                        if (cacheReady && cursor.id == main.id.value) listOf(cachedOlder) else emptyList()
                }
            val releaseRefresh = CompletableDeferred<Unit>()
            val mergeEntered = CompletableDeferred<Unit>()
            val releaseMerge = CompletableDeferred<Unit>()
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup,
                    sessionStore = null,
                    refreshMergeHook = {
                        if (cacheReady) {
                            mergeEntered.complete(Unit)
                            releaseMerge.await()
                        }
                    },
                ).create()
            try {
                advanceUntilIdle()
                store.accept(ChatsStore.Intent.FolderSelected(ARCHIVE_FOLDER_ID))
                advanceUntilIdle()
                store.accept(ChatsStore.Intent.FolderSelected(folder.id))
                advanceUntilIdle()
                assertEquals(listOf(ARCHIVE_FOLDER_WIRE_ID), client.folderCalls)

                cacheReady = true
                client.chatsGate = releaseRefresh
                store.accept(ChatsStore.Intent.Refresh)
                advanceUntilIdle()
                assertTrue(store.state.chats.any { it.id == cachedOlder.id })
                assertTrue(
                    "consuming the cached main tail must not exhaust the archive",
                    store.state.hasMore
                )

                client.blockedFolderId = ARCHIVE_FOLDER_WIRE_ID
                store.accept(ChatsStore.Intent.LoadMore)
                client.blockedFolderStarted.await()
                releaseRefresh.complete(Unit)
                mergeEntered.await()
                client.releaseBlockedFolder.complete(Unit)
                advanceUntilIdle()
                assertTrue(client.archivePages.isEmpty())

                releaseMerge.complete(Unit)
                advanceUntilIdle()
                assertEquals(
                    listOf(archived.id),
                    visibleChats(store.state.chats, listOf(folder), folder.id).map { it.id })
                assertFalse(store.state.hasMore)
                assertFalse(store.state.loadingMore)
                store.accept(ChatsStore.Intent.LoadMore)
                advanceUntilIdle()
                assertEquals(List(2) { ARCHIVE_FOLDER_WIRE_ID }, client.folderCalls)
            } finally {
                releaseMerge.complete(Unit)
                store.dispose()
            }
        }

    @Test
    fun switchingCustomFoldersSharesTheInFlightArchivePage() =
        runTest {
            val client =
                StubClient(
                    chats =
                        Outcome.Ok(
                            listOf(
                                Chat(
                                    PeerId(1),
                                    "Main",
                                    lastMessageDate = 1,
                                    lastMessageId = 1,
                                ),
                            ),
                        ),
                ).apply {
                    blockedFolderId = ARCHIVE_FOLDER_WIRE_ID
                }
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                advanceUntilIdle()
                store.accept(ChatsStore.Intent.FolderSelected(7))
                runCurrent()
                assertTrue(client.blockedFolderStarted.isCompleted)

                store.accept(ChatsStore.Intent.FolderSelected(8))
                runCurrent()
                assertEquals(listOf(ARCHIVE_FOLDER_WIRE_ID), client.folderCalls)

                client.releaseBlockedFolder.complete(Unit)
                advanceUntilIdle()

                assertEquals(listOf(ARCHIVE_FOLDER_WIRE_ID), client.folderCalls)
                assertFalse(store.state.hasMore)
                assertFalse(store.state.loadingMore)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun switchingFoldersDuringCachedTailPagingStartsArchiveRequest() =
        runTest {
            val client =
                StubClient(
                    chats =
                        Outcome.Ok(
                            (1L..80L).map { id ->
                                Chat(
                                    PeerId(id),
                                    "Main $id",
                                    lastMessageDate = id,
                                    lastMessageId = 1,
                                )
                            },
                        ),
                )
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
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
    fun switchingFoldersDuringRoomPagingStartsArchiveRequest() =
        runTest {
            val roomStarted = CompletableDeferred<Unit>()
            val releaseRoom = CompletableDeferred<Unit>()
            val warmup =
                object : OfflineWarmup(db = null) {
                    override suspend fun chatsWindow(
                        limit: Int,
                        archiveLimit: Int,
                    ): List<Chat> = emptyList()

                    override suspend fun mainListCount(): Int = 2

                    override suspend fun chatsAfter(
                        cursor: ChatCacheCursor,
                        limit: Int,
                    ): List<Chat> {
                        roomStarted.complete(Unit)
                        releaseRoom.await()
                        return listOf(
                            Chat(
                                PeerId(2),
                                "Room",
                                lastMessageDate = 2,
                                lastMessageId = 1,
                            ),
                        )
                    }
                }
            val client =
                StubClient(
                    chats =
                        Outcome.Ok(
                            listOf(
                                Chat(
                                    PeerId(1),
                                    "Main",
                                    lastMessageDate = 1,
                                    lastMessageId = 1,
                                ),
                            ),
                        ),
                )
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup,
                    sessionStore = null,
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
    fun archivePageSurvivesChatsChanged() =
        runTest {
            val archived = archiveChats(20)
            val client =
                StubClient(
                    chats =
                        Outcome.Ok(
                            listOf(
                                Chat(
                                    PeerId(100),
                                    "Main",
                                    lastMessageDate = 100,
                                    lastMessageId = 1,
                                ),
                            ),
                        ),
                ).apply {
                    archivePages.addLast(archived)
                }
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
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
    fun delayedMainRefreshDoesNotCollapseArchiveOrResetPaging() =
        runTest {
            val archived = archiveChats(20)
            val client =
                StubClient(
                    chats =
                        Outcome.Ok(
                            listOf(
                                Chat(
                                    PeerId(100),
                                    "Main",
                                    lastMessageDate = 100,
                                    lastMessageId = 1,
                                ),
                            ),
                        ),
                ).apply {
                    archivePages.addLast(archived)
                    chatResponses.addLast(
                        Outcome.Ok(
                            (200L..239L).map { id ->
                                Chat(
                                    PeerId(id),
                                    "Main $id",
                                    lastMessageDate = id,
                                    lastMessageId = 1,
                                )
                            },
                        ),
                    )
                }
            val mergeEntered = CompletableDeferred<Unit>()
            val releaseMerge = CompletableDeferred<Unit>()
            var hookArmed = false
            val store =
                ChatsStoreFactory(
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
    fun openingArchiveExposesCachedArchivedRowsWithoutWaitingForServer() =
        runTest {
            val archived = archiveChats(8)
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun chats() =
                        listOf(
                            Chat(
                                PeerId(100),
                                "Main",
                                lastMessageDate = 100,
                                lastMessageId = 1,
                            ),
                        ) +
                                archived
                }
            val client =
                StubClient(
                    chats =
                        Outcome.Ok(
                            listOf(
                                Chat(
                                    PeerId(100),
                                    "Main",
                                    lastMessageDate = 100,
                                    lastMessageId = 1,
                                ),
                            ),
                        ),
                )
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup,
                    sessionStore = null,
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
    fun archivePagesFromNetworkWhenOlderRoomPageIsEmpty() =
        runTest {
            val archived = archiveChats(DIALOGS_NETWORK_PAGE + 1).asReversed()
            val firstPage = archived.dropLast(1)
            val older = archived.last()
            val roomCursors = mutableListOf<ChatCacheCursor>()
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun chatsAfter(
                        cursor: ChatCacheCursor,
                        limit: Int,
                    ): List<Chat> {
                        roomCursors += cursor
                        return emptyList()
                    }
                }
            val client =
                StubClient(
                    chats = Outcome.Ok(
                        listOf(
                            Chat(
                                PeerId(100),
                                "Main",
                                lastMessageDate = 100,
                                lastMessageId = 1
                            )
                        )
                    ),
                ).apply {
                    archivePages.addLast(firstPage)
                    archivePages.addLast(listOf(older))
                }
            val store = ChatsStoreFactory(
                DefaultStoreFactory(),
                client,
                warmup,
                sessionStore = null
            ).create()
            try {
                advanceUntilIdle()
                store.accept(ChatsStore.Intent.FolderSelected(ARCHIVE_FOLDER_ID))
                advanceUntilIdle()
                assertEquals(listOf(ARCHIVE_FOLDER_WIRE_ID), client.folderCalls)
                assertEquals(firstPage.map { it.id }, visibleArchive(store).map { it.id })
                assertTrue(store.state.hasMore)

                store.accept(ChatsStore.Intent.LoadMore)
                advanceUntilIdle()
                val expectedCalls = listOf(ARCHIVE_FOLDER_WIRE_ID, ARCHIVE_FOLDER_WIRE_ID)
                assertEquals(listOf(ChatCacheCursor.from(firstPage.last())), roomCursors)
                assertEquals(expectedCalls, client.folderCalls)
                assertEquals(archived.map { it.id }, visibleArchive(store).map { it.id })
                assertFalse(store.state.hasMore)
                assertFalse(store.state.loadingMore)

                store.accept(ChatsStore.Intent.LoadMore)
                advanceUntilIdle()
                assertEquals(expectedCalls, client.folderCalls)
                store.accept(ChatsStore.Intent.FolderSelected(null))
                advanceUntilIdle()
                store.accept(ChatsStore.Intent.FolderSelected(ARCHIVE_FOLDER_ID))
                advanceUntilIdle()
                assertEquals(expectedCalls, client.folderCalls)
                assertEquals(archived.map { it.id }, visibleArchive(store).map { it.id })
                assertFalse(store.state.hasMore)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun exhaustedArchiveRepublishDoesNotHideRowsOrRequestAgain() =
        runTest {
            val archived = archiveChats(20)
            val client =
                StubClient(
                    chats =
                        Outcome.Ok(
                            listOf(
                                Chat(
                                    PeerId(100),
                                    "Main",
                                    lastMessageDate = 100,
                                    lastMessageId = 1,
                                ),
                            ),
                        ),
                ).apply {
                    archivePages.addLast(archived)
                }
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
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
    fun customFolderKeepsArchivedMembersAfterChatsChanged() =
        runTest {
            val archived =
                Chat(
                    PeerId(99),
                    "Archived late member",
                    archived = true,
                    unreadCount = 2,
                    lastMessageDate = 99,
                    lastMessageId = 1,
                )
            val client =
                StubClient(
                    chats =
                        Outcome.Ok(
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
                    archivePages.addLast(
                        (100L..139L).map { id ->
                            Chat(
                                PeerId(id),
                                "Archive $id",
                                archived = true,
                                lastMessageDate = id,
                                lastMessageId = 1,
                            )
                        },
                    )
                    archivePages.addLast(listOf(archived))
                }
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                store.accept(ChatsStore.Intent.FolderSelected(7))
                advanceUntilIdle()
                val folder =
                    Folder(
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
    fun incomingChatUpdatePreservesUnfinishedNetworkPagination() =
        runTest {
            val initial =
                (1L..40L).map { id ->
                    Chat(
                        PeerId(id),
                        "Chat $id",
                        archived = true,
                        lastMessageDate = id,
                        lastMessageId = 1,
                    )
                }
            val client = StubClient(chats = Outcome.Ok(initial))
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                advanceUntilIdle()
                assertTrue(store.state.hasMore)
                client.events.emit(
                    MtprotoUpdate.ChatsChanged(
                        listOf(
                            initial.first().copy(title = "Updated"),
                        ),
                    ),
                )
                advanceUntilIdle()
                assertTrue(store.state.hasMore)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun refreshDuringNetworkRefreshRunsOneCoalescedFollowUp() =
        runTest {
            val initial = Chat(PeerId(1), "Initial", lastMessageId = 1)
            val client =
                StubClient(chats = Outcome.Ok(listOf(initial))).apply {
                    chatResponses.addLast(
                        Outcome.Ok(
                            listOf(
                                initial.copy(
                                    title = "First",
                                    lastMessageId = 2,
                                ),
                            ),
                        ),
                    )
                    chatResponses.addLast(
                        Outcome.Ok(
                            listOf(
                                initial.copy(
                                    title = "Second",
                                    lastMessageId = 3,
                                ),
                            ),
                        ),
                    )
                }
            val release = CompletableDeferred<Unit>()
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                advanceUntilIdle()
                client.chatsGate = release
                store.accept(ChatsStore.Intent.Refresh)
                runCurrent()
                assertEquals(2, client.getChatsCalls)
                store.accept(ChatsStore.Intent.Refresh)
                release.complete(Unit)
                advanceUntilIdle()
                assertEquals(3, client.getChatsCalls)
                assertEquals(
                    3,
                    store.state.chats
                        .single()
                        .lastMessageId,
                )
            } finally {
                release.complete(Unit)
                store.dispose()
            }
        }

    @Test
    fun refreshMergeLockPreservesReadUpdateBeforePublication() =
        runTest {
            val target =
                Chat(
                    PeerId(999),
                    "Target",
                    unreadCount = 3,
                    lastMessageDate = 999,
                    lastMessageId = 1,
                )
            val refreshed =
                (1L..40L).map { id ->
                    Chat(PeerId(id), "Chat $id", lastMessageDate = id, lastMessageId = 1)
                } + target
            val client =
                StubClient(chats = Outcome.Ok(listOf(target))).apply {
                    chatResponses.addLast(Outcome.Ok(refreshed))
                }
            val mergeEntered = CompletableDeferred<Unit>()
            val releaseMerge = CompletableDeferred<Unit>()
            var hookArmed = false
            val store =
                ChatsStoreFactory(
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
    fun roomReadStateWaitsForRefreshPublicationLock() =
        runTest {
            val target =
                Chat(
                    PeerId(999),
                    "Target",
                    unreadCount = 3,
                    lastMessageDate = 999,
                    lastMessageId = 1,
                )
            val readStates =
                MutableSharedFlow<List<ChatReadState>>(replay = 1, extraBufferCapacity = 1)
            val warmup =
                object : OfflineWarmup(db = null) {
                    override fun observeReadStates() = readStates
                }
            val client =
                StubClient(chats = Outcome.Ok(listOf(target))).apply {
                    chatResponses.addLast(Outcome.Ok(listOf(target)))
                }
            val mergeEntered = CompletableDeferred<Unit>()
            val releaseMerge = CompletableDeferred<Unit>()
            val observerAttempted = CompletableDeferred<Unit>()
            val releaseObserver = CompletableDeferred<Unit>()
            var hookArmed = false
            val store =
                ChatsStoreFactory(
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
    fun listRebuildDoesNotRestoreUnreadAfterReadUpdate() =
        runTest {
            val targetId = PeerId(999)
            val target =
                Chat(
                    targetId,
                    "Target",
                    unreadCount = 3,
                    lastMessageDate = 999,
                    lastMessageId = 1,
                )
            val initial =
                (1L..40L).map { id ->
                    Chat(PeerId(id), "Chat $id", lastMessageDate = id, lastMessageId = 1)
                } + target
            val client = StubClient(chats = Outcome.Ok(listOf(target)))
            val mergeEntered = CompletableDeferred<Unit>()
            val releaseMerge = CompletableDeferred<Unit>()
            var hookArmed = false
            val store =
                ChatsStoreFactory(
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
                val rebuild =
                    launch {
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
        val store =
            ChatsStoreFactory(
                DefaultStoreFactory(),
                client,
                warmup = null,
                sessionStore = null,
            ).create()
        try {
            store.accept(ChatsStore.Intent.MarkRead(listOf(PeerId(1), PeerId(2))))
            assertEquals(listOf(PeerId(1) to 44), client.reads)
            assertEquals(
                0,
                store.state.chats
                    .first { it.id == PeerId(1) }
                    .unreadCount,
            )
        } finally {
            store.dispose()
        }
    }

    @Test
    fun cachedMuteSurvivesMissingNotifyDefaults() {
        // getNotifySettings is stubbed as an error: the cached effective flag is all there is.
        val cached = Chat(PeerId(1), "Ada", muted = true, muteOverride = false)
        val warmup =
            object : OfflineWarmup() {
                override suspend fun chats() = listOf(cached)
            }
        val store =
            ChatsStoreFactory(
                DefaultStoreFactory(),
                StubClient(chats = Outcome.Err("offline"), failNotifySettings = true),
                warmup,
                sessionStore = null,
            ).create()
        try {
            assertTrue(
                store.state.chats
                    .single()
                    .muted,
            )
        } finally {
            store.dispose()
        }
    }

    @Test
    fun dialogsRequestStartsWhileNotifyDefaultsArePending() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val release = CompletableDeferred<Unit>()
            val client =
                StubClient(
                    chats = Outcome.Ok(listOf(Chat(PeerId(1), "Ada"))),
                    defaults = NotifySettings(muteUntil = Int.MAX_VALUE),
                ).apply { notifySettingsGate = release }
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                runCurrent()
                assertEquals(1, client.getChatsCalls)
                assertEquals(3, client.notifySettingsCalls)
                assertTrue(store.state.loading)
                release.complete(Unit)
                advanceUntilIdle()
                assertFalse(store.state.loading)
                assertTrue(
                    store.state.chats
                        .single()
                        .muted,
                )
            } finally {
                release.complete(Unit)
                store.dispose()
            }
        }

    @Test
    fun catalogWaitsForProtocolCatchupWithoutHoldingTheVisibleList() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val release = CompletableDeferred<Unit>()
            var catalogStarted = false
            val page =
                (1L..40L).map { id ->
                    Chat(
                        PeerId(id),
                        "Chat $id",
                        lastMessageDate = id,
                        lastMessageId = 1,
                    )
                }
            val client =
                object : StubClient(chats = Outcome.Ok(page)) {
                    override suspend fun getChats(): Outcome<List<Chat>> {
                        release.await()
                        return super.getChats()
                    }

                    override suspend fun loadMoreChats(
                        offsetDate: Int,
                        offsetId: Int,
                        offsetPeerId: Long,
                    ): Outcome<List<Chat>> {
                        catalogStarted = true
                        return Outcome.Ok(emptyList())
                    }
                }
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                runCurrent()
                client.events.emit(MtprotoUpdate.SyncState(true))
                release.complete(Unit)
                runCurrent()
                assertEquals(40, store.state.chats.size)
                assertFalse(store.state.loading)
                assertTrue(store.state.syncing)
                assertFalse(catalogStarted)
                client.events.emit(MtprotoUpdate.SyncState(false))
                advanceUntilIdle()
                assertTrue(catalogStarted)
                assertFalse(store.state.syncing)
            } finally {
                store.dispose()
                release.complete(Unit)
            }
        }

    @Test
    fun catalogPausesBetweenPagesWhenCatchupRestarts() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val release = CompletableDeferred<Unit>()
            var requests = 0
            val seed =
                (81L..120L).map { id ->
                    Chat(
                        PeerId(id),
                        "Chat $id",
                        lastMessageDate = id,
                        lastMessageId = 1,
                    )
                }
            val next =
                (41L..80L).map { id ->
                    Chat(
                        PeerId(id),
                        "Chat $id",
                        lastMessageDate = id,
                        lastMessageId = 1,
                    )
                }
            val client =
                object : StubClient(chats = Outcome.Ok(seed)) {
                    override suspend fun loadMoreChats(
                        offsetDate: Int,
                        offsetId: Int,
                        offsetPeerId: Long,
                    ): Outcome<List<Chat>> {
                        requests++
                        if (requests > 1) return Outcome.Ok(emptyList())
                        release.await()
                        return Outcome.Ok(next)
                    }
                }
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                runCurrent()
                assertEquals(1, requests)
                client.events.emit(MtprotoUpdate.SyncState(true))
                release.complete(Unit)
                runCurrent()
                assertEquals(1, requests)
                client.events.emit(MtprotoUpdate.SyncState(false))
                advanceUntilIdle()
                assertEquals(2, requests)
            } finally {
                store.dispose()
                release.complete(Unit)
            }
        }

    @Test
    fun refreshCancelsCatalogWithoutWaitingForItsNextPage() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val release = CompletableDeferred<Unit>()
            var catalogStarted = false
            var catalogCancelled = false
            val page =
                (1L..40L).map { id ->
                    Chat(
                        PeerId(id),
                        "Chat $id",
                        lastMessageDate = id,
                        lastMessageId = 1,
                    )
                }
            val client =
                object : StubClient(chats = Outcome.Ok(page)) {
                    override suspend fun loadMoreChats(
                        offsetDate: Int,
                        offsetId: Int,
                        offsetPeerId: Long,
                    ): Outcome<List<Chat>> {
                        catalogStarted = true
                        try {
                            release.await()
                            return Outcome.Ok(emptyList())
                        } finally {
                            catalogCancelled = true
                        }
                    }
                }
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                runCurrent()
                assertTrue(catalogStarted)
                assertFalse(store.state.syncing)
                store.accept(ChatsStore.Intent.Refresh)
                runCurrent()
                assertEquals(2, client.getChatsCalls)
                assertTrue(catalogCancelled)
            } finally {
                store.dispose()
                release.complete(Unit)
            }
        }

    @Test
    fun supersededCatalogDoesNotCacheAnUncancellableResponse() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            var pending: kotlin.coroutines.Continuation<Outcome<List<Chat>>>? = null
            var pageRequests = 0
            val persisted = mutableListOf<Chat>()
            val page =
                (1L..40L).map { id ->
                    Chat(
                        PeerId(id),
                        "Chat $id",
                        lastMessageDate = id,
                        lastMessageId = 1,
                    )
                }
            val stale =
                Chat(PeerId(999), "Superseded catalog", lastMessageDate = 1, lastMessageId = 1)
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun upsertChats(chats: List<Chat>) {
                        persisted.addAll(chats)
                    }
                }
            val client =
                object : StubClient(chats = Outcome.Ok(page)) {
                    override suspend fun loadMoreChats(
                        offsetDate: Int,
                        offsetId: Int,
                        offsetPeerId: Long,
                    ): Outcome<List<Chat>> {
                        pageRequests++
                        return if (pageRequests == 1) {
                            kotlin.coroutines.suspendCoroutine { pending = it }
                        } else {
                            Outcome.Ok(page)
                        }
                    }
                }
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup,
                    sessionStore = null,
                ).create()
            try {
                runCurrent()
                val response = checkNotNull(pending)
                store.accept(ChatsStore.Intent.Refresh)
                runCurrent()
                assertEquals(2, client.getChatsCalls)
                pending = null
                response.resumeWith(Result.success(Outcome.Ok(listOf(stale))))
                advanceUntilIdle()
                assertTrue(persisted.none { it.id == stale.id })
                assertTrue(store.state.chats.none { it.id == stale.id })
                val beforePaging = pageRequests
                store.accept(ChatsStore.Intent.LoadMore)
                runCurrent()
                assertTrue(pageRequests > beforePaging)
            } finally {
                store.dispose()
                pending?.resumeWith(Result.success(Outcome.Ok(emptyList())))
            }
        }

    @Test
    fun incompleteDefaultsPreserveEffectiveMutesAndRetryTogether() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            var broadcastsFail = true
            val client =
                object : StubClient(
                    chats =
                        Outcome.Ok(
                            listOf(
                                Chat(
                                    PeerId(1),
                                    "Ada",
                                    muted = true,
                                    muteOverride = false,
                                ),
                            ),
                        ),
                ) {
                    override suspend fun getNotifySettings(
                        peerKind: String,
                        chatId: PeerId,
                    ): Outcome<NotifySettings> =
                        if (peerKind == "broadcasts" && broadcastsFail) {
                            Outcome.Err("offline")
                        } else {
                            Outcome.Ok(NotifySettings())
                        }
                }
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                advanceUntilIdle()
                assertTrue(
                    store.state.chats
                        .single()
                        .muted,
                )
                broadcastsFail = false
                store.accept(ChatsStore.Intent.Refresh)
                advanceUntilIdle()
                assertFalse(
                    store.state.chats
                        .single()
                        .muted,
                )
            } finally {
                store.dispose()
            }
        }

    @Test
    fun disposingStoreCancelsPendingDialogsAndDefaults() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val dialogsGate = CompletableDeferred<Unit>()
            val defaultsGate = CompletableDeferred<Unit>()
            var dialogsCancelled = false
            var defaultsCancelled = false
            val client =
                object : StubClient(chats = Outcome.Ok(emptyList())) {
                    override suspend fun getChats(): Outcome<List<Chat>> =
                        try {
                            dialogsGate.await()
                            Outcome.Ok(listOf(Chat(PeerId(1), "Ada")))
                        } finally {
                            dialogsCancelled = true
                        }

                    override suspend fun getNotifySettings(
                        peerKind: String,
                        chatId: PeerId,
                    ): Outcome<NotifySettings> =
                        try {
                            defaultsGate.await()
                            Outcome.Ok(NotifySettings())
                        } finally {
                            defaultsCancelled = true
                        }
                }
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            runCurrent()
            store.dispose()
            runCurrent()
            assertTrue(dialogsCancelled)
            assertTrue(defaultsCancelled)
        }

    @Test
    fun ownMuteSettingWinsOverTheTypeDefaultFromTheServer() {
        val client =
            StubClient(
                chats =
                    Outcome.Ok(
                        listOf(
                            Chat(PeerId(1), "Ada", muted = false, muteOverride = true),
                            Chat(PeerId(2), "Bob", muted = false, muteOverride = false),
                        ),
                    ),
                defaults = NotifySettings(muteUntil = Int.MAX_VALUE),
            )
        val store =
            ChatsStoreFactory(
                DefaultStoreFactory(),
                client,
                warmup = null,
                sessionStore = null,
            ).create()
        try {
            // The dialog without its own setting inherits the muted "users" default; the one with
            // an own setting stays unmuted.
            assertFalse(
                store.state.chats
                    .first { it.id == PeerId(1) }
                    .muted,
            )
            assertTrue(
                store.state.chats
                    .first { it.id == PeerId(2) }
                    .muted,
            )
        } finally {
            store.dispose()
        }
    }

    @Test
    fun explicitUnmuteStaysUnmutedAfterDefaultsAndAnotherDialogsPage() =
        runTest {
            val channel =
                Chat(
                    PeerId(-100),
                    "News",
                    isChannel = true,
                    muted = false,
                    muteOverride = true,
                    lastMessageId = 8,
                )
            val client =
                StubClient(
                    chats = Outcome.Ok(listOf(channel)),
                    defaults = NotifySettings(muteUntil = Int.MAX_VALUE),
                )
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                assertFalse(
                    store.state.chats
                        .single()
                        .muted,
                )
                client.events.emit(
                    MtprotoUpdate.ChatsChanged(
                        listOf(channel.copy(muted = false, muteOverride = false)),
                    ),
                )
                val row = store.state.chats.single()
                assertFalse(row.muted)
                assertTrue(row.muteOverride)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun inheritedNotifyUpdateDoesNotUnmuteAMutedChannel() =
        runTest {
            val channel =
                Chat(
                    PeerId(-100),
                    "News",
                    isChannel = true,
                    muted = true,
                    muteOverride = true,
                )
            val client = StubClient(chats = Outcome.Ok(listOf(channel)))
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                client.events.emit(
                    MtprotoUpdate.NotifySettingsChanged(
                        peerKind = "peer",
                        chatId = channel.id,
                        muteUntil = 0,
                        muteSpecified = false,
                    ),
                )
                assertTrue(
                    store.state.chats
                        .single()
                        .muted,
                )
                assertTrue(
                    store.state.chats
                        .single()
                        .muteOverride,
                )
            } finally {
                store.dispose()
            }
        }

    @Test
    fun peerNotifyChangeUpdatesTheRowAndTheCache() =
        runTest {
            val upserted = mutableListOf<Chat>()
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun chats() = emptyList<Chat>()

                    override suspend fun upsertChats(chats: List<Chat>) {
                        upserted += chats
                    }
                }
            val client = StubClient(chats = Outcome.Ok(listOf(Chat(PeerId(1), "Ada"))))
            val store =
                ChatsStoreFactory(
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
    fun typeNotifyChangeReloadsTheDefaults() =
        runTest {
            val client = StubClient(chats = Outcome.Ok(listOf(Chat(PeerId(1), "Ada"))))
            val store =
                ChatsStoreFactory(
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
    fun refreshPersistsLeftNetworkDialogsAndDropsThemFromTheList() =
        runTest {
            val leaked =
                Chat(
                    PeerId(-100),
                    "/vg/ comments",
                    isGroup = true,
                    lastMessageDate = 50,
                    lastMessageId = 6,
                    unreadCount = 6,
                )
            val anna = Chat(PeerId(1), "Anna", lastMessageDate = 10, lastMessageId = 2)
            val upserted = mutableListOf<Chat>()
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun chats() = listOf(anna, leaked)

                    override suspend fun upsertChats(chats: List<Chat>) {
                        upserted += chats
                    }
                }
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    StubClient(chats = Outcome.Ok(listOf(anna, leaked.copy(left = true)))),
                    warmup,
                    sessionStore = null,
                ).create()
            try {
                advanceUntilIdle()
                assertTrue(store.state.chats.none { it.id == leaked.id })
                assertEquals(listOf(1L), store.state.chats.map { it.id.value })
                assertTrue(upserted.any { it.id == leaked.id && it.left })
            } finally {
                store.dispose()
            }
        }

    @Test
    fun deletedPreviewUpdatesWithoutRepeatingCommittedDeletesAndPersistsLiveDeletes() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            for (persisted in listOf(true, false)) {
                val chatId = PeerId(1)
                val previous =
                    Message(MessageId(chatId, 1), null, "previous", 10L, outgoing = false)
                val latest = previous.copy(id = MessageId(chatId, 2), text = "deleted", date = 20L)
                val chat =
                    Chat(
                        chatId,
                        "Ada",
                        lastMessageId = 2,
                        lastMessagePreview = "deleted",
                        lastMessageDate = 20L,
                    )
                var cached = listOf(latest, previous)
                val deletes = mutableListOf<Pair<PeerId?, List<Int>>>()
                val warmup =
                    object : OfflineWarmup() {
                        override suspend fun messages(
                            chatId: PeerId,
                            limit: Int,
                        ) = cached.take(limit)

                        override suspend fun deleteMessages(
                            chatId: PeerId?,
                            messageIds: Collection<Int>,
                        ) {
                            deletes += chatId to messageIds.toList()
                            cached = cached.filterNot { it.id.id in messageIds }
                        }
                    }
                val client = StubClient(chats = Outcome.Ok(listOf(chat)))
                val store =
                    ChatsStoreFactory(
                        DefaultStoreFactory(),
                        client,
                        warmup,
                        sessionStore = null,
                    ).create()
                try {
                    advanceUntilIdle()
                    assertEquals(
                        2,
                        store.state.chats
                            .single()
                            .lastMessageId,
                    )
                    if (persisted) cached = listOf(previous)
                    val deletion = MtprotoUpdate.MessagesDeleted(chatId, listOf(2))
                    assertFalse(deletion.persisted)
                    client.events.emit(deletion.copy(persisted = persisted))
                    advanceUntilIdle()
                    val shown = store.state.chats.single()
                    assertEquals(1, shown.lastMessageId)
                    assertEquals("previous", shown.lastMessagePreview)
                    assertEquals(10L, shown.lastMessageDate)
                    assertEquals(
                        if (persisted) emptyList() else listOf(chatId to listOf(2)),
                        deletes,
                    )
                    assertEquals(listOf(previous), cached)
                } finally {
                    store.dispose()
                }
            }
        }

    @Test
    fun incomingMessageDoesNotPromoteALeftCommentGroup() =
        runTest {
            val comments =
                Chat(
                    PeerId(-100),
                    "Comments",
                    isGroup = true,
                    left = true,
                    lastMessageDate = 5,
                    lastMessageId = 1,
                )
            val anna = Chat(PeerId(1), "Anna", lastMessageDate = 10, lastMessageId = 2)
            val upserted = mutableListOf<Chat>()
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun chats() = listOf(anna, comments)

                    override suspend fun chat(chatId: PeerId) =
                        chats().firstOrNull { it.id == chatId }

                    override suspend fun upsertChats(chats: List<Chat>) {
                        upserted += chats
                    }
                }
            val client = StubClient(chats = Outcome.Ok(listOf(anna)))
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup,
                    sessionStore = null,
                ).create()
            try {
                advanceUntilIdle()
                client.events.emit(
                    MtprotoUpdate.NewMessage(
                        Message(
                            id = MessageId(comments.id, 8),
                            senderId = null,
                            text = "hi",
                            date = 99L,
                            outgoing = false,
                        ),
                    ),
                )
                advanceUntilIdle()
                assertTrue(store.state.chats.none { it.id == comments.id })
                assertTrue(upserted.none { it.id == comments.id && !it.left })
            } finally {
                store.dispose()
            }
        }

    @Test
    fun metadataPageRewritesTheCacheOnlyWhenMuteStateMoved() =
        runTest {
            val upserted = mutableListOf<List<Chat>>()
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun chats() = emptyList<Chat>()

                    override suspend fun upsertChats(chats: List<Chat>) {
                        upserted += chats
                    }
                }
            val client = StubClient(chats = Outcome.Ok(listOf(Chat(PeerId(1), "Ada"))))
            val store =
                ChatsStoreFactory(
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
                assertTrue(
                    store.state.chats
                        .single()
                        .muted,
                )
                assertEquals(1, upserted.size)
                assertTrue(upserted.single().single().muted)

                // A repeated metadata page (typing, presence) must not rewrite the row.
                client.events.emit(MtprotoUpdate.ChatsChanged(listOf(mutedByServer)))
                assertEquals(1, upserted.size)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun debounceThenFillsPeopleChatsAndMessages() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val client = StubClient(chats = Outcome.Ok(listOf(Chat(PeerId(1), "Ada"))))
            client.contactsPage =
                Outcome.Ok(
                    ContactsSearch(
                        people =
                            listOf(
                                SearchPeer(
                                    PeerId(2),
                                    "Bob",
                                    username = "bob",
                                    kind = "user",
                                ),
                            ),
                        chats =
                            listOf(
                                SearchPeer(
                                    PeerId(-100),
                                    "News",
                                    username = "news",
                                    kind = "channel",
                                    isChannel = true,
                                ),
                            ),
                    ),
                )
            client.globalPages.add(
                Outcome.Ok(
                    GlobalMessageSearch(
                        messages = listOf(searchMessage(9, -3)),
                        nextRate = 11,
                        nextPeerId = PeerId(-3),
                        nextOffsetId = 9,
                    ),
                ),
            )
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                store.accept(ChatsStore.Intent.QueryChanged("bo"))
                runCurrent()
                assertTrue(client.contactsQueries.isEmpty())
                advanceTimeBy(GLOBAL_SEARCH_DEBOUNCE_MS)
                runCurrent()
                assertEquals(listOf("bo"), client.contactsQueries)
                assertEquals(
                    "Bob",
                    store.state.searchPeople
                        .single()
                        .title,
                )
                assertEquals(
                    "News",
                    store.state.searchChats
                        .single()
                        .title,
                )
                assertEquals(
                    9,
                    store.state.searchMessages
                        .single()
                        .id.id,
                )
                assertFalse(store.state.searchHasMore)
                assertEquals(0, client.globalCalls.single().folderId)
                assertFalse(store.state.searchLoading)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun emptyQueryCancelsInFlightSearch() =
        runTest {
            val client = StubClient(chats = Outcome.Ok(listOf(Chat(PeerId(1), "Ada"))))
            client.searchGate = CompletableDeferred()
            client.contactsPage =
                Outcome.Ok(
                    ContactsSearch(people = listOf(SearchPeer(PeerId(2), "Bob", kind = "user"))),
                )
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                store.accept(ChatsStore.Intent.QueryChanged("bo"))
                store.accept(ChatsStore.Intent.RetrySearch)
                runCurrent()
                assertTrue(store.state.searchLoading)
                store.accept(ChatsStore.Intent.QueryChanged(""))
                runCurrent()
                assertTrue(store.state.searchPeople.isEmpty())
                assertTrue(store.state.searchMessages.isEmpty())
                assertFalse(store.state.searchLoading)
                assertNull(store.state.searchError)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun newerQueryDropsStaleResults() =
        runTest {
            val staleGate = CompletableDeferred<Unit>()
            val client = StubClient(chats = Outcome.Ok(emptyList()))
            client.searchGate = staleGate
            client.contactsPage =
                Outcome.Ok(
                    ContactsSearch(people = listOf(SearchPeer(PeerId(2), "Bob", kind = "user"))),
                )
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                store.accept(ChatsStore.Intent.QueryChanged("bo"))
                store.accept(ChatsStore.Intent.RetrySearch)
                runCurrent()
                assertTrue(store.state.searchLoading)
                client.searchGate = null
                client.contactsPage =
                    Outcome.Ok(
                        ContactsSearch(
                            people =
                                listOf(
                                    SearchPeer(
                                        PeerId(3),
                                        "Cara",
                                        kind = "user",
                                    ),
                                ),
                        ),
                    )
                store.accept(ChatsStore.Intent.QueryChanged("ca"))
                store.accept(ChatsStore.Intent.RetrySearch)
                runCurrent()
                assertEquals(listOf("Cara"), store.state.searchPeople.map { it.title })
                staleGate.complete(Unit)
                runCurrent()
                assertEquals(listOf("Cara"), store.state.searchPeople.map { it.title })
                assertEquals(listOf("bo", "ca"), client.contactsQueries)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun pagesGlobalMessagesWithOffsetTriple() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val client = StubClient(chats = Outcome.Ok(emptyList()))
            client.contactsPage = Outcome.Ok(ContactsSearch())
            val first = (1..GLOBAL_SEARCH_LIMIT).map { searchMessage(it, -4) }
            client.globalPages.add(
                Outcome.Ok(
                    GlobalMessageSearch(
                        messages = first,
                        nextRate = 44,
                        nextPeerId = PeerId(-4),
                        nextOffsetId = GLOBAL_SEARCH_LIMIT,
                    ),
                ),
            )
            client.globalPages.add(
                Outcome.Ok(
                    GlobalMessageSearch(
                        messages = listOf(searchMessage(21, -5)),
                        nextRate = 0,
                        nextPeerId = PeerId(0),
                        nextOffsetId = 0,
                    ),
                ),
            )
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                store.accept(ChatsStore.Intent.QueryChanged("hi"))
                advanceTimeBy(GLOBAL_SEARCH_DEBOUNCE_MS)
                runCurrent()
                assertTrue(store.state.searchHasMore)
                assertEquals(GLOBAL_SEARCH_LIMIT, store.state.searchMessages.size)
                store.accept(ChatsStore.Intent.LoadMore)
                runCurrent()
                assertEquals(GLOBAL_SEARCH_LIMIT + 1, store.state.searchMessages.size)
                assertEquals(44, client.globalCalls[1].offsetRate)
                assertEquals(-4L, client.globalCalls[1].offsetPeerId)
                assertEquals(GLOBAL_SEARCH_LIMIT, client.globalCalls[1].offsetId)
                assertFalse(store.state.searchHasMore)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun archiveSearchSendsFolderId() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val client = StubClient(chats = Outcome.Ok(emptyList()))
            client.contactsPage = Outcome.Ok(ContactsSearch())
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                store.accept(ChatsStore.Intent.FolderSelected(ARCHIVE_FOLDER_ID))
                store.accept(ChatsStore.Intent.QueryChanged("hi"))
                advanceTimeBy(GLOBAL_SEARCH_DEBOUNCE_MS)
                runCurrent()
                assertEquals(ARCHIVE_FOLDER_WIRE_ID, client.globalCalls.single().folderId)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun emptySearchQueryIsNotRetried() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val client = StubClient(chats = Outcome.Ok(emptyList()))
            client.contactsPage = Outcome.Ok(ContactsSearch())
            val emptyQuery = TelegramError.parse("SEARCH_QUERY_EMPTY")
            client.globalPages.add(Outcome.Err(emptyQuery.message, telegram = emptyQuery))
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                store.accept(ChatsStore.Intent.QueryChanged("x"))
                advanceTimeBy(GLOBAL_SEARCH_DEBOUNCE_MS)
                runCurrent()
                assertNull(store.state.searchError)
                assertEquals(1, client.globalCalls.size)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun otherErrorsSurfaceForRetry() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val client = StubClient(chats = Outcome.Ok(emptyList()))
            client.contactsPage = Outcome.Ok(ContactsSearch())
            client.globalPages.add(Outcome.Err("Timeout"))
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                store.accept(ChatsStore.Intent.QueryChanged("x"))
                advanceTimeBy(GLOBAL_SEARCH_DEBOUNCE_MS)
                runCurrent()
                assertEquals(TelegramError.Kind.Network, store.state.searchError?.kind)
                client.globalPages.add(
                    Outcome.Ok(GlobalMessageSearch(messages = listOf(searchMessage(1, 8)))),
                )
                store.accept(ChatsStore.Intent.RetrySearch)
                runCurrent()
                assertNull(store.state.searchError)
                assertEquals(1, store.state.searchMessages.size)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun failedPageStopsAutomaticPaging() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val client = StubClient(chats = Outcome.Ok(emptyList()))
            client.contactsPage = Outcome.Ok(ContactsSearch())
            val first = (1..GLOBAL_SEARCH_LIMIT).map { searchMessage(it, -4) }
            client.globalPages.add(
                Outcome.Ok(
                    GlobalMessageSearch(
                        messages = first,
                        nextRate = 44,
                        nextPeerId = PeerId(-4),
                        nextOffsetId = GLOBAL_SEARCH_LIMIT,
                    ),
                ),
            )
            client.globalPages.add(Outcome.Err("Timeout"))
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                store.accept(ChatsStore.Intent.QueryChanged("hi"))
                advanceTimeBy(GLOBAL_SEARCH_DEBOUNCE_MS)
                runCurrent()
                assertTrue(store.state.searchHasMore)
                store.accept(ChatsStore.Intent.LoadMore)
                runCurrent()
                assertFalse(store.state.searchHasMore)
                assertEquals(TelegramError.Kind.Network, store.state.searchError?.kind)
                val calls = client.globalCalls.size
                store.accept(ChatsStore.Intent.LoadMore)
                runCurrent()
                assertEquals(calls, client.globalCalls.size)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun loadMoreDuringDebounceDoesNotReuseOldOffsets() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val client = StubClient(chats = Outcome.Ok(emptyList()))
            client.contactsPage = Outcome.Ok(ContactsSearch())
            val first = (1..GLOBAL_SEARCH_LIMIT).map { searchMessage(it, -4) }
            client.globalPages.add(
                Outcome.Ok(
                    GlobalMessageSearch(
                        messages = first,
                        nextRate = 44,
                        nextPeerId = PeerId(-4),
                        nextOffsetId = GLOBAL_SEARCH_LIMIT,
                    ),
                ),
            )
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                store.accept(ChatsStore.Intent.QueryChanged("hi"))
                advanceTimeBy(GLOBAL_SEARCH_DEBOUNCE_MS)
                runCurrent()
                assertEquals(1, client.globalCalls.size)
                store.accept(ChatsStore.Intent.QueryChanged("yo"))
                store.accept(ChatsStore.Intent.LoadMore)
                runCurrent()
                assertEquals(1, client.globalCalls.size)
                assertFalse(store.state.searchHasMore)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun localDialogsAreDroppedFromPeopleSection() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val local = Chat(PeerId(2), "Bob")
            val client = StubClient(chats = Outcome.Ok(listOf(local)))
            client.contactsPage =
                Outcome.Ok(
                    ContactsSearch(
                        people =
                            listOf(
                                SearchPeer(PeerId(2), "Bob", kind = "user"),
                                SearchPeer(PeerId(3), "Cara", kind = "user"),
                            ),
                    ),
                )
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                store.accept(ChatsStore.Intent.QueryChanged("b"))
                advanceTimeBy(GLOBAL_SEARCH_DEBOUNCE_MS)
                runCurrent()
                assertEquals(listOf(3L), store.state.searchPeople.map { it.id.value })
            } finally {
                store.dispose()
            }
        }

    @Test
    fun usernameHitStaysWhenLoadedTitleDoesNotMatch() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val loaded = Chat(PeerId(2), "Ada Lovelace")
            val client = StubClient(chats = Outcome.Ok(listOf(loaded)))
            client.contactsPage =
                Outcome.Ok(
                    ContactsSearch(
                        people =
                            listOf(
                                SearchPeer(
                                    PeerId(2),
                                    "Bob",
                                    username = "bob",
                                    kind = "user",
                                ),
                            ),
                    ),
                )
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                store.accept(ChatsStore.Intent.QueryChanged("bob"))
                advanceTimeBy(GLOBAL_SEARCH_DEBOUNCE_MS)
                runCurrent()
                assertEquals(listOf(2L), store.state.searchPeople.map { it.id.value })
            } finally {
                store.dispose()
            }
        }

    @Test
    fun explicitFolderKeepsAllEightKnownMembersAcrossRefreshAndReevaluatesMembershipEdits() =
        runTest {
            val members = (1..8).map {
                Chat(
                    PeerId(it.toLong()),
                    "Dev $it",
                    lastMessageDate = it.toLong(),
                    lastMessageId = 1
                )
            }
            val unrelated = (20..27).map {
                Chat(
                    PeerId(it.toLong()),
                    "Other $it",
                    lastMessageDate = it.toLong(),
                    lastMessageId = 1
                )
            }
            val folder = Folder(7, "Dev", chatIds = members.map { it.id })
            val initial = unrelated.take(6) + members.take(6)
            var page = initial
            val missing = Chat(PeerId(9), "New member", left = true)
            val requests = mutableListOf<Int>()
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun chatsWindow(
                        limit: Int,
                        archiveLimit: Int,
                    ) = initial

                    override suspend fun chatsByIds(ids: List<PeerId>) =
                        members.filter { it.id in ids }
                }
            val client =
                object : StubClient(chats = Outcome.Ok(initial)) {
                    override suspend fun getChatsPage() = Outcome.Ok(DialogPage(page, null))

                    override suspend fun loadMoreFolderChatsPage(
                        folderId: Int,
                        offsetDate: Int,
                        offsetId: Int,
                        offsetPeerId: Long,
                    ): Outcome<DialogPage> {
                        requests += folderId
                        return Outcome.Ok(
                            DialogPage(
                                listOf(missing),
                                DialogOffset(1, 1, missing.id.value)
                            )
                        )
                    }
                }
            val store = ChatsStoreFactory(
                DefaultStoreFactory(),
                client,
                warmup,
                sessionStore = null
            ).create()

            fun visibleIds(folder: Folder): Set<PeerId> =
                visibleChats(
                    (
                            store.state.chats +
                                    store.state.folderActivation
                                        ?.cachedMembers
                                        .orEmpty()
                            ).distinctBy { it.id },
                    listOf(folder),
                    folder.id,
                ).map { it.id }.toSet()

            try {
                advanceUntilIdle()
                store.accept(ChatsStore.Intent.FolderSelected(folder.id, folder))
                advanceUntilIdle()
                assertEquals(folder.chatIds.toSet(), visibleIds(folder))
                assertFalse(store.state.hasMore)

                page = unrelated.takeLast(2)
                store.accept(ChatsStore.Intent.Refresh)
                advanceUntilIdle()
                assertEquals(4, store.state.chats.count { it.id in folder.chatIds })
                assertEquals(folder.chatIds.toSet(), visibleIds(folder))
                assertFalse(store.state.hasMore)
                assertTrue(requests.isEmpty())

                val excluded = members.first().id
                val edited = folder.copy(
                    chatIds = folder.chatIds + missing.id,
                    excludeChatIds = listOf(excluded)
                )
                store.accept(ChatsStore.Intent.FolderSelected(edited.id, edited))
                advanceUntilIdle()
                val expected = folder.chatIds.toSet() - excluded
                assertEquals(expected, visibleIds(edited))
                assertTrue(store.state.hasMore)

                repeat(2) {
                    store.accept(
                        ChatsStore.Intent.FolderUnderfilled(
                            edited.id,
                            checkNotNull(store.state.folderActivation).remainingPages
                        )
                    )
                    advanceUntilIdle()
                }
                assertEquals(listOf(ARCHIVE_FOLDER_WIRE_ID), requests)
                assertEquals(expected, visibleIds(edited))
                assertFalse(store.state.hasMore)

                val departed = members[1].copy(left = true)
                page = listOf(departed)
                store.accept(ChatsStore.Intent.Refresh)
                advanceUntilIdle()
                assertEquals(expected - departed.id, visibleIds(edited))
                assertFalse(store.state.hasMore)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun measuredUnderfillUsesSuccessiveCachePagesWithoutMovingPastAPointLookup() =
        runTest {
            val seed = Chat(PeerId(10), "Seed", lastMessageDate = 10, lastMessageId = 1)
            val first = seed.copy(id = PeerId(9), lastMessageDate = 9)
            val second = seed.copy(id = PeerId(8), lastMessageDate = 8)
            val member = seed.copy(id = PeerId(1), lastMessageDate = 1)
            val folder = Folder(7, "Work", chatIds = listOf(member.id), includeGroups = true)
            val roomOffsets = mutableListOf<ChatCacheCursor>()
            var serveCache = false
            val warmup =
                object : OfflineWarmup() {
                    override suspend fun chatsWindow(
                        limit: Int,
                        archiveLimit: Int,
                    ) = listOf(seed)

                    override suspend fun chatsByIds(ids: List<PeerId>): List<Chat> {
                        assertEquals(listOf(member.id), ids)
                        return listOf(member)
                    }

                    override suspend fun chatsAfter(
                        cursor: ChatCacheCursor,
                        limit: Int,
                    ): List<Chat> {
                        if (!serveCache) return emptyList()
                        roomOffsets += cursor
                        return when (cursor.id) {
                            seed.id.value -> listOf(first)
                            first.id.value -> listOf(second)
                            else -> emptyList()
                        }
                    }
                }
            val mainOffset = DialogOffset(100, 2, 10)
            val requests = mutableListOf<Pair<Int, DialogOffset>>()
            val client =
                object : StubClient(chats = Outcome.Ok(listOf(seed))) {
                    override suspend fun getChatsPage() =
                        Outcome.Ok(DialogPage(listOf(seed), mainOffset))

                    override suspend fun loadMoreFolderChatsPage(
                        folderId: Int,
                        offsetDate: Int,
                        offsetId: Int,
                        offsetPeerId: Long,
                    ): Outcome<DialogPage> {
                        requests += folderId to DialogOffset(offsetDate, offsetId, offsetPeerId)
                        return Outcome.Ok(DialogPage(emptyList(), null))
                    }
                }
            val store = ChatsStoreFactory(
                DefaultStoreFactory(),
                client,
                warmup,
                sessionStore = null
            ).create()
            try {
                advanceUntilIdle()
                requests.clear()
                serveCache = true
                store.accept(ChatsStore.Intent.FolderSelected(folder.id, folder))
                advanceUntilIdle()
                assertEquals(listOf(member), store.state.folderActivation?.cachedMembers)
                assertEquals(listOf(seed), store.state.chats)

                repeat(2) {
                    store.accept(
                        ChatsStore.Intent.FolderUnderfilled(
                            folder.id,
                            checkNotNull(store.state.folderActivation).remainingPages
                        )
                    )
                    advanceUntilIdle()
                }
                assertEquals(listOf(seed, first, second), store.state.chats)
                assertEquals(listOf(seed, first).map(ChatCacheCursor::from), roomOffsets)
                assertEquals(emptyList<Pair<Int, DialogOffset>>(), requests)

                store.accept(ChatsStore.Intent.FolderSelected(null))
                store.accept(ChatsStore.Intent.LoadMore)
                advanceUntilIdle()
                assertEquals(ChatCacheCursor.from(second), roomOffsets.last())
                assertEquals(listOf(MAIN_FOLDER_WIRE_ID to mainOffset), requests)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun measuredUnderfillCanPagePastEightRowsAndLeavesContinuationForScroll() =
        assertUnderfilledFolderDiscovery(memberPage = 4, measuredSteps = 6, automaticMainPages = 5)

    @Test
    fun underfilledFolderStopsAtTheDiscoveryBudgetEvenWithoutNewMatches() =
        assertUnderfilledFolderDiscovery(
            memberPage = 10,
            measuredSteps = 12,
            automaticMainPages = 7
        )

    private fun assertUnderfilledFolderDiscovery(
        memberPage: Int,
        measuredSteps: Int,
        automaticMainPages: Int,
    ) = runTest {
        val seed = (100L downTo 96L).map { Chat(PeerId(it), "Dev $it") }
        val discovered = (95L downTo 93L).map { Chat(PeerId(it), "Dev $it") }
        val last = Chat(PeerId(92), "Dev 92")
        val folder = Folder(7, "Dev", chatIds = (seed + discovered + last).map { it.id })
        val mainPages = mutableListOf<Int>()
        var archiveRequests = 0

        fun offset(page: Int) = DialogOffset(page, 1, 1000L + page)

        val client =
            object : StubClient(chats = Outcome.Ok(seed)) {
                override suspend fun getChatsPage() = Outcome.Ok(DialogPage(seed, offset(1)))

                override suspend fun loadMoreFolderChatsPage(
                    folderId: Int,
                    offsetDate: Int,
                    offsetId: Int,
                    offsetPeerId: Long,
                ): Outcome<DialogPage> {
                    if (folderId == ARCHIVE_FOLDER_WIRE_ID) {
                        archiveRequests++
                        return Outcome.Ok(DialogPage(emptyList(), null))
                    }
                    mainPages += offsetDate
                    return Outcome.Ok(
                        when (offsetDate) {
                            memberPage -> DialogPage(discovered, offset(memberPage + 1))
                            memberPage + 2 -> DialogPage(listOf(last), null)
                            else -> DialogPage(emptyList(), offset(offsetDate + 1))
                        },
                    )
                }
            }
        val store = ChatsStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = null,
            sessionStore = null
        ).create()

        fun visibleCount() = visibleChats(store.state.chats, listOf(folder), folder.id).size

        try {
            advanceUntilIdle()
            mainPages.clear()
            archiveRequests = 0
            assertEquals(5, visibleCount())

            store.accept(ChatsStore.Intent.FolderSelected(folder.id, folder))
            advanceUntilIdle()
            assertEquals(emptyList<Int>(), mainPages)
            assertEquals(0, archiveRequests)
            repeat(measuredSteps) {
                val remaining = checkNotNull(store.state.folderActivation).remainingPages
                store.accept(ChatsStore.Intent.FolderUnderfilled(folder.id, remaining))
                advanceUntilIdle()
            }
            assertEquals(if (memberPage <= automaticMainPages) 8 else 5, visibleCount())
            assertEquals((1..automaticMainPages).toList(), mainPages)
            assertEquals(1, archiveRequests)
            assertTrue(store.state.hasMore)
            assertFalse(store.state.loadingMore)

            store.accept(ChatsStore.Intent.FolderSelected(folder.id, folder))
            advanceUntilIdle()
            assertEquals((1..automaticMainPages).toList(), mainPages)
            assertEquals(1, archiveRequests)

            // Scroll dispatches the same intent as the manual footer.
            repeat(2) {
                store.accept(ChatsStore.Intent.LoadMore)
                advanceUntilIdle()
            }
            assertEquals(9, visibleCount())
            assertEquals((1..memberPage + 2).toList(), mainPages)
            assertFalse(store.state.hasMore)

            store.accept(ChatsStore.Intent.FolderSelected(null))
            store.accept(ChatsStore.Intent.FolderSelected(folder.id, folder))
            advanceUntilIdle()
            assertEquals((1..memberPage + 2).toList(), mainPages)
            assertEquals(1, archiveRequests)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun mappedPageBelowLimitFollowsServerContinuationIntoCustomFolder() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val seed = (100L downTo 62L).map { id -> Chat(PeerId(id), "Seed $id") }
            val member =
                Chat(
                    PeerId(61),
                    "Folder member",
                    archived = true,
                    lastMessageDate = 61,
                    lastMessageId = 1,
                )
            val folder = Folder(7, "Work", chatIds = listOf(member.id))
            val continuation = DialogOffset(61, 1, 61)
            val pages = mutableListOf<Pair<Int, DialogOffset>>()
            val client =
                object : StubClient(chats = Outcome.Ok(seed)) {
                    override suspend fun getChatsPage(): Outcome<DialogPage> =
                        Outcome.Ok(DialogPage(seed, continuation))

                    override suspend fun loadMoreFolderChatsPage(
                        folderId: Int,
                        offsetDate: Int,
                        offsetId: Int,
                        offsetPeerId: Long,
                    ): Outcome<DialogPage> {
                        pages += folderId to DialogOffset(offsetDate, offsetId, offsetPeerId)
                        return when (folderId) {
                            MAIN_FOLDER_WIRE_ID -> Outcome.Ok(DialogPage(listOf(member), null))
                            else -> Outcome.Ok(DialogPage(emptyList(), null))
                        }
                    }
                }
            val store =
                ChatsStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    warmup = null,
                    sessionStore = null,
                ).create()
            try {
                advanceUntilIdle()
                store.accept(ChatsStore.Intent.FolderSelected(folder.id))
                advanceUntilIdle()
                assertEquals(
                    listOf(member.id),
                    visibleChats(store.state.chats, listOf(folder), folder.id).map { it.id },
                )
                assertEquals(
                    listOf(
                        ARCHIVE_FOLDER_WIRE_ID to DialogOffset(0, 0, 0),
                        MAIN_FOLDER_WIRE_ID to continuation,
                    ),
                    pages,
                )
                assertFalse(store.state.hasMore)

                store.accept(ChatsStore.Intent.LoadMore)
                advanceUntilIdle()
                assertEquals(2, pages.size)
            } finally {
                store.dispose()
            }
        }

    private fun searchMessage(
        id: Int,
        chat: Long,
    ) = Message(
        id = MessageId(PeerId(chat), id),
        senderId = null,
        text = "m$id",
        date = id.toLong(),
        outgoing = false,
    )

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

    internal open class StubClient(
        private val chats: Outcome<List<Chat>>,
        private val defaults: NotifySettings = NotifySettings(),
        private val failNotifySettings: Boolean = false,
        private val profile: Outcome<Profile> = Outcome.Err("unavailable"),
    ) : MtprotoClient {
        val reads = mutableListOf<Pair<PeerId, Int>>()
        val folderCalls = mutableListOf<Int>()
        val mainOffsets = mutableListOf<Triple<Int, Int, Long>>()
        val mainPages = ArrayDeque<List<Chat>>()
        var mainFlood: String? = null
        val chatResponses = ArrayDeque<Outcome<List<Chat>>>()
        val archivePages = ArrayDeque<List<Chat>>()
        var blockedFolderId: Int? = null
        val blockedFolderStarted = CompletableDeferred<Unit>()
        val releaseBlockedFolder = CompletableDeferred<Unit>()
        val events = MutableSharedFlow<MtprotoUpdate>(extraBufferCapacity = 8)
        var notifySettingsCalls = 0
        var notifySettingsGate: CompletableDeferred<Unit>? = null
        var profileCalls = 0
        var chatsGate: CompletableDeferred<Unit>? = null
        var contactsPage: Outcome<ContactsSearch> = Outcome.Err("unsupported")
        val globalPages = ArrayDeque<Outcome<GlobalMessageSearch>>()
        val contactsQueries = mutableListOf<String>()
        val globalCalls = mutableListOf<SearchGlobalCall>()
        var searchGate: CompletableDeferred<Unit>? = null

        override suspend fun getNotifySettings(
            peerKind: String,
            chatId: PeerId,
        ): Outcome<NotifySettings> {
            notifySettingsCalls++
            notifySettingsGate?.await()
            return if (failNotifySettings) Outcome.Err("offline") else Outcome.Ok(defaults)
        }

        override suspend fun connect() = Outcome.Ok(Unit)

        override suspend fun sendAuthCode(phone: String) = unused<AuthState.AwaitingCode>()

        override suspend fun signIn(
            phone: String,
            phoneCodeHash: String,
            code: String,
        ) = unused<AuthState>()

        override suspend fun checkPassword(password: String) = unused<AuthState.Authorized>()

        var getChatsCalls = 0

        override suspend fun getChats(): Outcome<List<Chat>> {
            getChatsCalls++
            chatsGate?.await()
            return if (chatResponses.isEmpty()) chats else chatResponses.removeFirst()
        }

        override suspend fun getChatsPage(): Outcome<DialogPage> = dialogPage(getChats())

        private fun dialogPage(result: Outcome<List<Chat>>): Outcome<DialogPage> =
            when (result) {
                is Outcome.Ok -> {
                    Outcome.Ok(
                        DialogPage(
                            result.value,
                            result.value
                                .lastOrNull()
                                ?.takeIf { result.value.size >= DIALOGS_NETWORK_PAGE }
                                ?.let(::dialogsPageOffset)
                                ?.let { (date, id, peer) -> DialogOffset(date, id, peer) },
                        ),
                    )
                }

                is Outcome.Err -> {
                    result
                }
            }

        override suspend fun loadMoreChats(
            offsetDate: Int,
            offsetId: Int,
            offsetPeerId: Long,
        ): Outcome<List<Chat>> {
            mainOffsets += Triple(offsetDate, offsetId, offsetPeerId)
            mainFlood?.let { flood ->
                mainFlood = null
                return Outcome.Err(flood)
            }
            return Outcome.Ok(if (mainPages.isEmpty()) emptyList() else mainPages.removeFirst())
        }

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
            }
            if (folderId != ARCHIVE_FOLDER_WIRE_ID) return Outcome.Err(FOLDER_ID_INVALID)
            return Outcome.Ok(if (archivePages.isEmpty()) emptyList() else archivePages.removeFirst())
        }

        override suspend fun loadMoreFolderChatsPage(
            folderId: Int,
            offsetDate: Int,
            offsetId: Int,
            offsetPeerId: Long,
        ): Outcome<DialogPage> =
            dialogPage(
                if (folderId == MAIN_FOLDER_WIRE_ID) {
                    loadMoreChats(offsetDate, offsetId, offsetPeerId)
                } else {
                    loadMoreFolderChats(folderId, offsetDate, offsetId, offsetPeerId)
                },
            )

        override suspend fun getFolders() = Outcome.Ok(emptyList<Folder>())

        var historyCalls = 0

        override suspend fun getHistory(
            chatId: PeerId,
            limit: Int,
        ): Outcome<List<Message>> {
            historyCalls++
            return Outcome.Ok(emptyList())
        }

        override suspend fun getHistoryPage(
            chatId: PeerId,
            limit: Int,
            offsetId: Int,
            offsetDate: Int,
            addOffset: Int,
        ) = Outcome.Ok(emptyList<Message>())

        override suspend fun searchMessages(
            chatId: PeerId,
            query: String,
            limit: Int,
        ) = Outcome.Ok(emptyList<Message>())

        override suspend fun contactsSearch(
            query: String,
            limit: Int,
        ): Outcome<ContactsSearch> {
            contactsQueries += query
            searchGate?.await()
            return contactsPage
        }

        override suspend fun searchGlobal(
            query: String,
            offsetRate: Int,
            offsetPeerId: PeerId,
            offsetId: Int,
            limit: Int,
            folderId: Int,
        ): Outcome<GlobalMessageSearch> {
            globalCalls += SearchGlobalCall(query, offsetRate, offsetPeerId.value, offsetId, limit, folderId)
            searchGate?.await()
            return if (globalPages.isEmpty()) {
                Outcome.Ok(GlobalMessageSearch())
            } else {
                globalPages.removeFirst()
            }
        }

        override suspend fun getPinnedMessages(
            chatId: PeerId,
            limit: Int,
        ) = Outcome.Ok(emptyList<Message>())

        override suspend fun sendText(
            chatId: PeerId,
            text: String,
            replyToMsgId: Int,
            entitiesJson: String?,
            topMsgId: Int,
            webpageUrl: String?,
            randomId: Long,
        ) = unused<Message>()

        override suspend fun sendPhoto(
            chatId: PeerId,
            path: String,
            caption: String,
            replyToMsgId: Int,
            topMsgId: Int,
            entitiesJson: String?,
        ) = unused<Message>()

        override suspend fun editText(
            chatId: PeerId,
            messageId: Int,
            text: String,
            entitiesJson: String?,
        ) = unused<Message>()

        override suspend fun deleteMessage(
            chatId: PeerId,
            messageId: Int,
            revoke: Boolean,
        ) = Outcome.Ok(Unit)

        override suspend fun forwardMessage(
            fromChatId: PeerId,
            messageId: Int,
            toChatId: PeerId,
        ) = Outcome.Ok(emptyList<Message>())

        override suspend fun readHistory(
            chatId: PeerId,
            maxId: Int,
        ): Outcome<Unit> {
            reads += chatId to maxId
            return Outcome.Ok(Unit)
        }

        override suspend fun setTyping(
            chatId: PeerId,
            typing: Boolean,
        ) = Outcome.Ok(Unit)

        override suspend fun getProfile(peerId: PeerId): Outcome<Profile> {
            profileCalls++
            return profile
        }

        override suspend fun downloadMessageMedia(
            chatId: PeerId,
            messageId: Int,
            destPath: String,
        ) = unused<String>()

        override suspend fun downloadMessageThumb(
            chatId: PeerId,
            messageId: Int,
            destPath: String,
        ) = unused<String>()

        override suspend fun getUpdatesState() = Outcome.Ok(UpdatesCursor(0, 0, 0, 0))

        override fun updates(): Flow<MtprotoUpdate> = events

        override fun sessionLost(): Flow<Unit> = emptyFlow()

        override fun libraryVersion() = "test"

        override suspend fun logout() = Outcome.Ok(Unit)

        override fun close() = Unit

        private fun <T> unused(): Outcome<T> = Outcome.Err("unused")
    }
}

internal data class SearchGlobalCall(
    val query: String,
    val offsetRate: Int,
    val offsetPeerId: Long,
    val offsetId: Int,
    val limit: Int,
    val folderId: Int,
)
