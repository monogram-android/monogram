package org.monogram.network.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.common.TelegramCredentials
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.PeerId
import org.monogram.mtproto.MtprotoNative
import org.monogram.network.bridge.chat.ChatApi
import org.monogram.network.bridge.session.DispatchClass
import org.monogram.network.bridge.session.SessionCore
import org.monogram.network.http.MediaPriority
import uniffi.monogram_mtproto.ChatDto
import uniffi.monogram_mtproto.DialogsCursorDto
import uniffi.monogram_mtproto.DialogsPageDto
import uniffi.monogram_mtproto.FolderDto
import uniffi.monogram_mtproto.ForumTopicDto
import uniffi.monogram_mtproto.ForumTopicsPageDto
import uniffi.monogram_mtproto.HistoryPageDto
import uniffi.monogram_mtproto.MessageDto
import uniffi.monogram_mtproto.MtprotoException
import uniffi.monogram_mtproto.UpdateEventDto
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

class BridgedMtprotoClientHistoryTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun metadataUpdatesPublishChatsAndAnEmptyFolderList() =
        runTest {
            val native =
                object : RecordingNative() {
                    override fun getFolders(handle: Long): List<uniffi.monogram_mtproto.FolderDto> =
                        emptyList()
                }.apply {
                    drainEventsOverride =
                        listOf(UpdateEventDto.FoldersChanged, UpdateEventDto.ChatsChanged)
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "metadata.session",
                    native = native,
                    nativeDispatcher = StandardTestDispatcher(testScheduler),
                    refreshDcSidecar = {},
                )
            try {
                val events = mutableListOf<MtprotoUpdate>()
                backgroundScope.async { client.updates().toList(events) }
                runCurrent()
                client.connect()
                advanceTimeBy(1_600)
                runCurrent()
                assertEquals(
                    emptyList<org.monogram.core.models.Folder>(),
                    events.filterIsInstance<MtprotoUpdate.FoldersChanged>().first().folders,
                )
                val refreshed =
                    events
                        .filterIsInstance<MtprotoUpdate.ChatsChanged>()
                        .first()
                        .chats
                        .single()
                assertEquals(PeerId(42), refreshed.id)
                assertEquals(2, refreshed.unreadMentionsCount)
                assertEquals(1, refreshed.unreadReactionsCount)
            } finally {
                client.close()
            }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun remoteReadReceiptsReachSubscribersWithExactUnreadCounts() =
        runTest {
            val native =
                RecordingNative().apply {
                    drainEventsOverride =
                        listOf(
                            UpdateEventDto.ReadInbox(42L, 18, 2),
                            UpdateEventDto.ReadInbox(-1_000_000_000_042L, 50, 0),
                        )
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "read.session",
                    native = native,
                    nativeDispatcher = StandardTestDispatcher(testScheduler),
                    refreshDcSidecar = {},
                )
            try {
                client.connect()
                val events = mutableListOf<MtprotoUpdate.ReadInbox>()
                backgroundScope.async {
                    client
                        .updates()
                        .filterIsInstance<MtprotoUpdate.ReadInbox>()
                        .take(2)
                        .toList(events)
                }
                runCurrent()
                advanceTimeBy(1_600)
                runCurrent()
                assertEquals(
                    listOf(
                        MtprotoUpdate.ReadInbox(PeerId(42), 18, 2),
                        MtprotoUpdate.ReadInbox(PeerId(-1_000_000_000_042L), 50, 0),
                    ),
                    events,
                )
            } finally {
                client.close()
            }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun onlySuccessfulWholeChatReadPublishesLocalConfirmation() =
        runTest {
            var fail = false
            val native =
                object : RecordingNative() {
                    override fun readHistory(
                        handle: Long,
                        chatId: Long,
                        maxId: Int,
                    ) {
                        if (fail) throw IllegalStateException("offline")
                    }

                    override fun readDiscussion(
                        handle: Long,
                        chatId: Long,
                        msgId: Int,
                        readMaxId: Int,
                    ) = Unit
                }.apply { drainEventsOverride = emptyList() }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "read.session",
                    native = native,
                    nativeDispatcher = StandardTestDispatcher(testScheduler),
                    refreshDcSidecar = {},
                )
            try {
                val events = mutableListOf<MtprotoUpdate>()
                backgroundScope.async {
                    client.updates().filter { it !is MtprotoUpdate.SyncState }.toList(events)
                }
                runCurrent()
                assertTrue(client.readHistory(PeerId(42), 18) is Outcome.Ok)
                runCurrent()
                assertEquals(listOf(MtprotoUpdate.ReadHistoryConfirmed(PeerId(42), 18)), events)
                client.readDiscussion(PeerId(42), 10, 20)
                fail = true
                assertTrue(client.readHistory(PeerId(42), 20) is Outcome.Err)
                runCurrent()
                assertEquals(
                    listOf(
                        MtprotoUpdate.ReadHistoryConfirmed(PeerId(42), 18),
                        MtprotoUpdate.DiscussionInbox(PeerId(42), 10, 20),
                    ),
                    events,
                )
            } finally {
                client.close()
            }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun chatMetadataBurstsCoalescePremiumRefreshes() =
        runTest {
            var profileCalls = 0
            val native =
                object : RecordingNative() {
                    init {
                        drainEventsOverride = listOf(UpdateEventDto.ChatsChanged)
                    }

                    override fun getProfile(
                        handle: Long,
                        peerId: Long,
                    ): uniffi.monogram_mtproto.ProfileDto {
                        profileCalls++
                        assertEquals(0L, peerId)
                        return uniffi.monogram_mtproto.ProfileDto(
                            id = 1,
                            kind = "user",
                            title = "Self",
                            username = null,
                            about = null,
                            avatarCacheKey = null,
                            isSelf = true,
                            status = null,
                            statusAt = null,
                            extraJson = """{"premium":true}""",
                        )
                    }
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "premium.session",
                    native = native,
                    nativeDispatcher = StandardTestDispatcher(testScheduler),
                    refreshDcSidecar = {},
                )
            try {
                val premiums = mutableListOf<MtprotoUpdate.AccountPremium>()
                val collector =
                    backgroundScope.async {
                        client.updates().filterIsInstance<MtprotoUpdate.AccountPremium>()
                            .toList(premiums)
                    }
                runCurrent()
                client.getHistory(PeerId(42))
                advanceTimeBy(2_000)
                runCurrent()
                assertTrue(native.drainCalls > 1)
                assertEquals(1, profileCalls)
                assertEquals(listOf(MtprotoUpdate.AccountPremium(true)), premiums)
                collector.cancel()
            } finally {
                client.close()
            }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun slowDrainCountsTowardRefreshCooldown() =
        runTest {
            val extra = ExtraClock(testScheduler)
            var chatsCalls = 0
            val native =
                object : RecordingNative() {
                    override fun drainUpdates(handle: Long): List<UpdateEventDto> {
                        extra.extraMs += 1_000L
                        drainCalls++
                        return listOf(UpdateEventDto.ChatsChanged)
                    }

                    override fun getChats(handle: Long): List<ChatDto> {
                        chatsCalls++
                        return super.getChats(handle)
                    }
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "slow-drain.session",
                    native = native,
                    nativeDispatcher = StandardTestDispatcher(testScheduler),
                    refreshDcSidecar = {},
                    clock = extra,
                )
            try {
                backgroundScope.async { client.updates().toList() }
                runCurrent()
                client.connect()
                advanceTimeBy(100)
                runCurrent()
                assertTrue("chatsCalls=$chatsCalls drains=${native.drainCalls}", chatsCalls >= 2)
                assertTrue(
                    "drain loops should stay far below the old 80-sleep cooldown, drains=${native.drainCalls}",
                    native.drainCalls < 20,
                )
            } finally {
                client.close()
            }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun lazyDialogPatchSkipsFullChatRefresh() =
        runTest {
            var lazyEnabled: Boolean? = null
            val native =
                object : RecordingNative() {
                    override fun updateLazySyncConfig(
                        handle: Long,
                        lazy: Boolean,
                        exceptions: List<Long>,
                    ) {
                        lazyEnabled = lazy
                        assertTrue(exceptions.isEmpty())
                    }

                    override fun drainUpdates(handle: Long): List<UpdateEventDto> {
                        drainCalls++
                        return when (drainCalls) {
                            1 -> {
                                listOf(
                                    UpdateEventDto.DialogsPatched(listOf(sampleChat(title = "Patched"))),
                                    UpdateEventDto.SyncState(isSyncing = true),
                                )
                            }

                            2 -> {
                                listOf(UpdateEventDto.SyncState(isSyncing = false))
                            }

                            else -> {
                                emptyList()
                            }
                        }
                    }
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "lazy-patch.session",
                    native = native,
                    nativeDispatcher = StandardTestDispatcher(testScheduler),
                    refreshDcSidecar = {},
                )
            try {
                val seen = mutableListOf<MtprotoUpdate>()
                val collector = backgroundScope.async { client.updates().toList(seen) }
                runCurrent()
                client.connect()
                advanceTimeBy(100)
                runCurrent()
                assertEquals(true, lazyEnabled)
                assertEquals(0, native.getChatsCalls)
                assertEquals(
                    "Patched",
                    seen
                        .filterIsInstance<MtprotoUpdate.ChatsChanged>()
                        .single()
                        .chats
                        .single()
                        .title,
                )
                assertTrue(
                    seen
                        .filterIsInstance<MtprotoUpdate.ChatsChanged>()
                        .single()
                        .chats
                        .single()
                        .canSendPolls,
                )
                assertEquals(
                    listOf(false, true, false),
                    seen.filterIsInstance<MtprotoUpdate.SyncState>().map { it.isSyncing },
                )
                collector.cancel()
            } finally {
                client.close()
            }
        }

    @Test
    fun folderInvalidationDuringSnapshotRefreshesNewMembershipOnce() =
        runBlocking {
            val now = AtomicLong()
            val member = AtomicLong(42L)
            val folderCalls = AtomicInteger()
            val firstSnapshotTaken = CompletableDeferred<Unit>()
            val releaseFirstSnapshot = CountDownLatch(1)
            val pendingEvents = Channel<List<UpdateEventDto>>(Channel.UNLIMITED)
            val native =
                object : RecordingNative() {
                    override fun drainUpdates(handle: Long): List<UpdateEventDto> =
                        pendingEvents.tryReceive().getOrNull().orEmpty()

                    override fun getFolders(handle: Long): List<FolderDto> {
                        val snapshot = member.get()
                        if (folderCalls.incrementAndGet() == 1) {
                            firstSnapshotTaken.complete(Unit)
                            check(releaseFirstSnapshot.await(10, TimeUnit.SECONDS))
                        }
                        return listOf(
                            FolderDto(
                                id = 2,
                                title = "Folder",
                                chatIds = listOf(snapshot),
                                excludeChatIds = emptyList(),
                                includeContacts = false,
                                includeNonContacts = false,
                                includeGroups = false,
                                includeChannels = false,
                                includeBots = false,
                                excludeMuted = false,
                                excludeRead = false,
                                excludeArchived = false,
                                emoticon = "",
                                pinnedChatIds = emptyList(),
                            ),
                        )
                    }
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "folder-invalidation.session",
                    native = native,
                    nativeDispatcher = Dispatchers.IO,
                    refreshDcSidecar = {},
                    clock = { now.get() },
                )
            val memberships = Channel<List<PeerId>>(Channel.UNLIMITED)
            val readReceipts = Channel<Int>(Channel.UNLIMITED)
            val collector =
                launch {
                    client.updates().collect { update ->
                        when (update) {
                            is MtprotoUpdate.FoldersChanged -> memberships.send(update.folders.single().chatIds)
                            is MtprotoUpdate.ReadInbox -> readReceipts.send(update.maxId)
                            else -> Unit
                        }
                    }
                }
            try {
                withTimeout(5_000) {
                    assertTrue(client.connect() is Outcome.Ok)
                    pendingEvents.send(listOf(UpdateEventDto.FoldersChanged))
                    firstSnapshotTaken.await()

                    member.set(43L)
                    pendingEvents.send(
                        listOf(
                            UpdateEventDto.FoldersChanged,
                            UpdateEventDto.ReadInbox(42L, 1, 0)
                        )
                    )
                    assertEquals(1, readReceipts.receive())
                    assertEquals(1, folderCalls.get())
                    releaseFirstSnapshot.countDown()
                    assertEquals(listOf(PeerId(42L)), memberships.receive())

                    now.addAndGet(2_000L)
                    assertEquals(listOf(PeerId(43L)), memberships.receive())
                    assertEquals(2, folderCalls.get())

                    now.addAndGet(2_000L)
                    // Observe a subsequent drain so the elapsed cooldown alone cannot satisfy the idle assertion.
                    pendingEvents.send(listOf(UpdateEventDto.ReadInbox(42L, 2, 0)))
                    pendingEvents.send(listOf(UpdateEventDto.ReadInbox(42L, 3, 0)))
                    assertEquals(2, readReceipts.receive())
                    assertEquals(3, readReceipts.receive())
                    assertEquals(2, folderCalls.get())
                }
            } finally {
                releaseFirstSnapshot.countDown()
                collector.cancel()
                client.close()
            }
        }

    @Test
    fun foregroundFolderSnapshotBeforeInvalidationDoesNotJoinPumpRefresh() =
        runBlocking {
            val now = AtomicLong()
            val member = AtomicLong(42L)
            val folderCalls = AtomicInteger()
            val firstSnapshotTaken = CompletableDeferred<Unit>()
            val releaseFirstSnapshot = CountDownLatch(1)
            val pendingEvents = Channel<List<UpdateEventDto>>(Channel.UNLIMITED)
            val native =
                object : RecordingNative() {
                    override fun drainUpdates(handle: Long): List<UpdateEventDto> =
                        pendingEvents.tryReceive().getOrNull().orEmpty()

                    override fun getFolders(handle: Long): List<FolderDto> {
                        val snapshot = member.get()
                        if (folderCalls.incrementAndGet() == 1) {
                            firstSnapshotTaken.complete(Unit)
                            check(releaseFirstSnapshot.await(10, TimeUnit.SECONDS))
                        }
                        return listOf(
                            FolderDto(
                                id = 2,
                                title = "Folder",
                                chatIds = listOf(snapshot),
                                excludeChatIds = emptyList(),
                                includeContacts = false,
                                includeNonContacts = false,
                                includeGroups = false,
                                includeChannels = false,
                                includeBots = false,
                                excludeMuted = false,
                                excludeRead = false,
                                excludeArchived = false,
                                emoticon = "",
                                pinnedChatIds = emptyList(),
                            ),
                        )
                    }
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "folder-foreground-invalidation.session",
                    native = native,
                    nativeDispatcher = Dispatchers.IO,
                    refreshDcSidecar = {},
                    clock = { now.get() },
                )
            val memberships = Channel<List<PeerId>>(Channel.UNLIMITED)
            val readReceipts = Channel<Int>(Channel.UNLIMITED)
            val collector =
                launch {
                    client.updates().collect { update ->
                        when (update) {
                            is MtprotoUpdate.FoldersChanged -> memberships.send(update.folders.single().chatIds)
                            is MtprotoUpdate.ReadInbox -> readReceipts.send(update.maxId)
                            else -> Unit
                        }
                    }
                }
            try {
                withTimeout(5_000) {
                    assertTrue(client.connect() is Outcome.Ok)
                    val foreground = async { client.getFolders() }
                    firstSnapshotTaken.await()

                    member.set(43L)
                    pendingEvents.send(listOf(UpdateEventDto.FoldersChanged))
                    pendingEvents.send(listOf(UpdateEventDto.ReadInbox(42L, 1, 0)))
                    assertEquals(1, readReceipts.receive())
                    assertEquals(1, folderCalls.get())
                    releaseFirstSnapshot.countDown()

                    assertEquals(
                        listOf(PeerId(42L)),
                        (foreground.await() as Outcome.Ok).value.single().chatIds
                    )
                    assertEquals(listOf(PeerId(43L)), memberships.receive())
                    assertEquals(2, folderCalls.get())

                    now.addAndGet(2_000L)
                    pendingEvents.send(listOf(UpdateEventDto.ReadInbox(42L, 2, 0)))
                    pendingEvents.send(listOf(UpdateEventDto.ReadInbox(42L, 3, 0)))
                    assertEquals(2, readReceipts.receive())
                    assertEquals(3, readReceipts.receive())
                    assertEquals(2, folderCalls.get())
                }
            } finally {
                releaseFirstSnapshot.countDown()
                collector.cancel()
                client.close()
            }
        }

    @Test
    fun foregroundChatSnapshotBeforeInvalidationDoesNotJoinPumpRefresh() =
        runBlocking {
            val now = AtomicLong()
            val title = AtomicLong(42L)
            val chatCalls = AtomicInteger()
            val firstSnapshotTaken = CompletableDeferred<Unit>()
            val releaseFirstSnapshot = CountDownLatch(1)
            val pendingEvents = Channel<List<UpdateEventDto>>(Channel.UNLIMITED)
            val native =
                object : RecordingNative() {
                    override fun drainUpdates(handle: Long): List<UpdateEventDto> =
                        pendingEvents.tryReceive().getOrNull().orEmpty()

                    override fun getChats(handle: Long): List<ChatDto> {
                        val snapshot = title.get()
                        if (chatCalls.incrementAndGet() == 1) {
                            firstSnapshotTaken.complete(Unit)
                            check(releaseFirstSnapshot.await(10, TimeUnit.SECONDS))
                        }
                        return listOf(sampleChat("Ada-$snapshot"))
                    }
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "chat-foreground-invalidation.session",
                    native = native,
                    nativeDispatcher = Dispatchers.IO,
                    refreshDcSidecar = {},
                    clock = { now.get() },
                )
            val titles = Channel<String>(Channel.UNLIMITED)
            val readReceipts = Channel<Int>(Channel.UNLIMITED)
            val collector =
                launch {
                    client.updates().collect { update ->
                        when (update) {
                            is MtprotoUpdate.ChatsChanged -> titles.send(update.chats.single().title)
                            is MtprotoUpdate.ReadInbox -> readReceipts.send(update.maxId)
                            else -> Unit
                        }
                    }
                }
            try {
                withTimeout(5_000) {
                    assertTrue(client.connect() is Outcome.Ok)
                    val foreground = async { client.getChats() }
                    firstSnapshotTaken.await()

                    title.set(43L)
                    pendingEvents.send(listOf(UpdateEventDto.ChatsChanged))
                    pendingEvents.send(listOf(UpdateEventDto.ReadInbox(42L, 1, 0)))
                    assertEquals(1, readReceipts.receive())
                    assertEquals(1, chatCalls.get())
                    releaseFirstSnapshot.countDown()

                    assertEquals("Ada-42", (foreground.await() as Outcome.Ok).value.single().title)
                    assertEquals("Ada-43", titles.receive())
                    assertEquals(2, chatCalls.get())

                    now.addAndGet(2_000L)
                    pendingEvents.send(listOf(UpdateEventDto.ReadInbox(42L, 2, 0)))
                    pendingEvents.send(listOf(UpdateEventDto.ReadInbox(42L, 3, 0)))
                    assertEquals(2, readReceipts.receive())
                    assertEquals(3, readReceipts.receive())
                    assertEquals(2, chatCalls.get())
                }
            } finally {
                releaseFirstSnapshot.countDown()
                collector.cancel()
                client.close()
            }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun folderFloodRetryUsesMonotonicClock() =
        runTest {
            val extra = ExtraClock(testScheduler)
            var folderCalls = 0
            val native =
                object : RecordingNative() {
                    override fun drainUpdates(handle: Long): List<UpdateEventDto> {
                        drainCalls++
                        return if (drainCalls == 1) listOf(UpdateEventDto.FoldersChanged) else emptyList()
                    }

                    override fun getFolders(handle: Long): List<uniffi.monogram_mtproto.FolderDto> {
                        folderCalls++
                        if (folderCalls == 1) throw MtprotoException.Message("FLOOD_WAIT_120")
                        return emptyList()
                    }
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "flood-refresh.session",
                    native = native,
                    nativeDispatcher = StandardTestDispatcher(testScheduler),
                    refreshDcSidecar = {},
                    clock = extra,
                )
            try {
                val updates = mutableListOf<MtprotoUpdate.FoldersChanged>()
                backgroundScope.async {
                    client.updates().filterIsInstance<MtprotoUpdate.FoldersChanged>()
                        .toList(updates)
                }
                runCurrent()
                client.connect()
                runCurrent()
                assertEquals(1, folderCalls)

                val foreground = async { client.getFolders() }
                val cancelled = async { client.getFolders() }
                runCurrent()
                cancelled.cancel()

                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(1, folderCalls)
                assertFalse(foreground.isCompleted)
                assertTrue(native.drainCalls > 1)

                // Make the first timer wake land 1 ms before the monotonic deadline.
                advanceTimeBy(1)
                extra.extraMs--
                advanceTimeBy(59_999)
                runCurrent()
                assertEquals(1, folderCalls)
                assertFalse(foreground.isCompleted)
                assertTrue(updates.isEmpty())

                advanceTimeBy(1)
                runCurrent()
                assertEquals(2, folderCalls)
                assertTrue(foreground.isCompleted)
                assertEquals(updates.single().folders, (foreground.await() as Outcome.Ok).value)

                advanceTimeBy(2_000)
                runCurrent()
                assertEquals(2, folderCalls)
            } finally {
                client.close()
            }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun handleChangeResetsRefreshCooldown() =
        runTest {
            val extra = ExtraClock(testScheduler)
            var chatsCalls = 0
            var nextHandle = 0L
            val native =
                object : RecordingNative() {
                    override fun createClient(
                        apiId: Int,
                        apiHash: String,
                        sessionPath: String,
                    ): Long = ++nextHandle

                    override fun drainUpdates(handle: Long): List<UpdateEventDto> {
                        drainCalls++
                        return listOf(UpdateEventDto.ChatsChanged)
                    }

                    override fun getChats(handle: Long): List<ChatDto> {
                        chatsCalls++
                        return super.getChats(handle)
                    }
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "handle-refresh.session",
                    native = native,
                    nativeDispatcher = StandardTestDispatcher(testScheduler),
                    refreshDcSidecar = {},
                    clock = extra,
                )
            try {
                backgroundScope.async { client.updates().toList() }
                runCurrent()
                client.connect()
                advanceTimeBy(50)
                runCurrent()
                assertEquals(1, chatsCalls)
                client.hibernate()
                runCurrent()
                client.connect()
                advanceTimeBy(50)
                runCurrent()
                assertEquals(2, chatsCalls)
            } finally {
                client.close()
            }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun overlappingDialogPagesShareRequestAndRespectFloodDeadline() =
        runTest {
            val calls = mutableListOf<Pair<Int, DialogOffset>>()
            val native =
                object : RecordingNative() {
                    override fun loadMoreChatsPage(
                        handle: Long,
                        offsetDate: Int,
                        offsetId: Int,
                        offsetPeerId: Long,
                        folderId: Int,
                    ): DialogsPageDto {
                        calls += folderId to DialogOffset(offsetDate, offsetId, offsetPeerId)
                        if (calls.size == 1) throw MtprotoException.Message("FLOOD_WAIT_17")
                        return DialogsPageDto(
                            emptyList(),
                            1,
                            DialogsCursorDto(6, 5, -1_000_000_000_004L),
                            isSlice = true
                        )
                    }
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "page-flood.session",
                    native = native,
                    nativeDispatcher = StandardTestDispatcher(testScheduler),
                    refreshDcSidecar = {},
                    clock = ExtraClock(testScheduler),
                )
            try {
                client.connect()
                val first = async { client.loadMoreChats(10, 9, 8L) }
                val same = async { client.loadMoreFolderChatsPage(0, 10, 9, 8L) }
                assertEquals(17, (first.await() as Outcome.Err).telegramError.retryAfterSeconds)
                assertEquals(17, (same.await() as Outcome.Err).telegramError.retryAfterSeconds)
                val archive = async { client.loadMoreFolderChatsPage(1, 7, 6, -5L) }
                val legacyArchive = async { client.loadMoreFolderChats(1, 7, 6, -5L) }
                advanceTimeBy(16_999)
                runCurrent()
                val firstRequest = 0 to DialogOffset(10, 9, 8L)
                assertEquals(listOf(firstRequest), calls)
                assertFalse(archive.isCompleted)
                advanceTimeBy(1)
                val page = (archive.await() as Outcome.Ok).value
                assertEquals(DialogPage(emptyList(), DialogOffset(6, 5, -1_000_000_000_004L)), page)
                assertEquals(Outcome.Ok(page.chats), legacyArchive.await())
                assertEquals(listOf(firstRequest, 1 to DialogOffset(7, 6, -5L)), calls)
            } finally {
                client.close()
            }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun cancelledFolderPageWorkerGatesReopenedPageUntilFloodExpires() =
        runTest {
            val calls = mutableListOf<Pair<Int, DialogOffset>>()
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CountDownLatch(1)
            val dispatcher = BlockingRequestDispatcher(StandardTestDispatcher(testScheduler))
            val native =
                object : RecordingNative() {
                    override fun createRequestControl(): Long = dispatcher.createRequestControl()

                    override fun loadMoreChatsPage(
                        handle: Long,
                        offsetDate: Int,
                        offsetId: Int,
                        offsetPeerId: Long,
                        folderId: Int,
                    ): DialogsPageDto {
                        calls += folderId to DialogOffset(offsetDate, offsetId, offsetPeerId)
                        if (calls.size == 1) {
                            firstStarted.complete(Unit)
                            check(releaseFirst.await(10, TimeUnit.SECONDS))
                            throw MtprotoException.Message("FLOOD_WAIT_17")
                        }
                        return DialogsPageDto(
                            emptyList(),
                            1,
                            DialogsCursorDto(6, 5, -4L),
                            isSlice = true
                        )
                    }
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "cancelled-page-flood.session",
                    native = native,
                    nativeDispatcher = dispatcher,
                    refreshDcSidecar = {},
                    clock = ExtraClock(testScheduler),
                )
            try {
                assertTrue(client.connect() is Outcome.Ok)
                val request = 2 to DialogOffset(10, 9, -8L)
                dispatcher.offloadNextRequest = true
                val first = async { client.loadMoreFolderChatsPage(2, 10, 9, -8L) }
                firstStarted.await()
                first.cancelAndJoin()

                val reopened = async { client.loadMoreFolderChatsPage(2, 10, 9, -8L) }
                runCurrent()
                assertEquals(listOf(request), calls)
                releaseFirst.countDown()
                check(dispatcher.workerFinished.await(10, TimeUnit.SECONDS))
                runCurrent()

                advanceTimeBy(16_999)
                runCurrent()
                assertEquals(listOf(request), calls)
                assertFalse(reopened.isCompleted)

                advanceTimeBy(1)
                assertEquals(
                    Outcome.Ok(DialogPage(emptyList(), DialogOffset(6, 5, -4L))),
                    reopened.await()
                )
                assertEquals(17_000L, testScheduler.currentTime)
                assertEquals(listOf(request, request), calls)
            } finally {
                releaseFirst.countDown()
                client.close()
            }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun archiveFirstPagePinFloodDelaysRetryUntilPinsAreReachable() =
        runTest {
            val calls = mutableListOf<Pair<Int, DialogOffset>>()
            val native =
                object : RecordingNative() {
                    override fun loadMoreChatsPage(
                        handle: Long,
                        offsetDate: Int,
                        offsetId: Int,
                        offsetPeerId: Long,
                        folderId: Int,
                    ): DialogsPageDto {
                        calls += folderId to DialogOffset(offsetDate, offsetId, offsetPeerId)
                        if (calls.size == 1) throw MtprotoException.Message("RPC 420: FLOOD_WAIT_17")
                        val regular = sampleChat("Archive dialog").copy(archived = true)
                        val pinned = regular.copy(id = 43L, title = "Archive pin", pinned = true)
                        return DialogsPageDto(listOf(pinned, regular), 1, null, isSlice = false)
                    }
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "archive-pin-flood.session",
                    native = native,
                    nativeDispatcher = StandardTestDispatcher(testScheduler),
                    refreshDcSidecar = {},
                    clock = ExtraClock(testScheduler),
                )
            try {
                client.connect()
                val request = 1 to DialogOffset(0, 0, 0L)
                val failed = client.loadMoreFolderChatsPage(1, 0, 0, 0L) as Outcome.Err
                assertEquals(17, failed.telegramError.retryAfterSeconds)

                val retry = async { client.loadMoreFolderChatsPage(1, 0, 0, 0L) }
                advanceTimeBy(16_999)
                runCurrent()
                assertEquals(listOf(request), calls)
                assertFalse(retry.isCompleted)

                advanceTimeBy(1)
                runCurrent()
                assertEquals(listOf(request, request), calls)
                val page = (retry.await() as Outcome.Ok).value
                assertEquals(
                    listOf("Archive pin" to true, "Archive dialog" to false),
                    page.chats.map { it.title to it.pinned },
                )
                assertEquals(null, page.nextOffset)
            } finally {
                client.close()
            }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun idleFirstPageFloodGatesForegroundFirstPageAndPaginationAcrossChatApis() =
        runTest {
            val calls = mutableListOf<String>()
            val native =
                object : RecordingNative() {
                    override fun getChatsPage(handle: Long): DialogsPageDto {
                        calls += "first"
                        if (calls.size == 1) throw MtprotoException.Message("FLOOD_WAIT_17")
                        return DialogsPageDto(
                            listOf(sampleChat("First")),
                            2,
                            DialogsCursorDto(10, 9, 8L),
                            isSlice = true
                        )
                    }

                    override fun loadMoreChatsPage(
                        handle: Long,
                        offsetDate: Int,
                        offsetId: Int,
                        offsetPeerId: Long,
                        folderId: Int,
                    ): DialogsPageDto {
                        calls += "page"
                        return DialogsPageDto(emptyList(), 40, null, isSlice = true)
                    }
                }
            val core =
                SessionCore(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "first-page-flood.session",
                    native = native,
                    historyTimeoutMs = 1_000L,
                    nativeDispatcher = StandardTestDispatcher(testScheduler),
                    refreshDcSidecar = {},
                    clock = ExtraClock(testScheduler),
                )
            try {
                val chat = ChatApi(core)
                val flood = runCatching { ChatApi(core).getChatsIfIdle() }.exceptionOrNull()
                assertTrue(flood is MtprotoException.Message)

                val first = async { chat.getChatsPage() }
                val same = async { chat.getChats() }
                val page = async { chat.loadMoreFolderChatsPage(0, 10, 9, 8L) }
                runCurrent()
                val idle = async { ChatApi(core).getChatsIfIdle() }
                runCurrent()
                assertTrue(idle.isCompleted)
                assertEquals(null, idle.await())

                advanceTimeBy(16_999)
                runCurrent()
                assertEquals(listOf("first"), calls)
                assertFalse(first.isCompleted)
                assertFalse(page.isCompleted)

                advanceTimeBy(1)
                val firstPage = (first.await() as Outcome.Ok).value
                assertEquals("First", firstPage.chats.single().title)
                assertEquals(DialogOffset(10, 9, 8L), firstPage.nextOffset)
                assertEquals(Outcome.Ok(firstPage.chats), same.await())
                assertEquals(Outcome.Ok(DialogPage(emptyList(), null)), page.await())
                assertEquals(listOf("first", "first", "page"), calls)
            } finally {
                core.close()
            }
        }

    private class BlockingRequestDispatcher(
        private val schedulerDispatcher: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        val workerFinished = CountDownLatch(1)
        var offloadNextRequest = false
        private var offloadNextDispatch = false

        fun createRequestControl(): Long {
            offloadNextDispatch = offloadNextRequest
            offloadNextRequest = false
            return 0L
        }

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            if (offloadNextDispatch) {
                offloadNextDispatch = false
                // Let one native worker block without stalling virtual time or other workers.
                Dispatchers.IO.dispatch(context) {
                    try {
                        block.run()
                    } finally {
                        workerFinished.countDown()
                    }
                }
            } else {
                schedulerDispatcher.dispatch(context, block)
            }
        }
    }

    private class ExtraClock(
        private val scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ) : org.monogram.network.bridge.session.MonotonicClock {
        @Volatile
        var extraMs = 0L

        override fun elapsedMs(): Long = scheduler.currentTime + extraMs
    }

    @Test
    fun streamingChunkPreservesOffsetAndMapsMediaErrors() =
        runBlocking {
            var observedOffset = -1L
            val native =
                object : RecordingNative() {
                    override fun downloadMessageMediaChunk(
                        handle: Long,
                        chatId: Long,
                        messageId: Int,
                        destPath: String,
                        offset: Long,
                    ): String {
                        observedOffset = offset
                        assertEquals(42L, chatId)
                        assertEquals(7, messageId)
                        if (offset > 0) throw uniffi.monogram_mtproto.MtprotoException.Message("FILE_REFERENCE_EXPIRED")
                        return destPath
                    }
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "stream.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            try {
                client.setDialogForeground(true)
                val first = client.downloadMessageMediaChunk(PeerId(42), 7, "part.bin", 0)
                assertTrue(first is Outcome.Ok)
                assertEquals("part.bin", (first as Outcome.Ok).value)
                val second = client.downloadMessageMediaChunk(PeerId(42), 7, "part.bin", 524288)
                assertEquals(524288L, observedOffset)
                assertTrue(second is Outcome.Err)
                assertEquals(
                    org.monogram.core.common.telegram.TelegramError.Kind.FileReference,
                    (second as Outcome.Err).telegramError.kind
                )
            } finally {
                client.close()
            }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun unrelatedHistoryCompletesWhileBlockedAndCancelledFloodStillGatesTheSession() =
        runTest {
            val cursor = Triple(123, 456, -1)
            val requestedCursors = mutableListOf<Triple<Int, Int, Int>>()
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CountDownLatch(1)
            val dispatcher = BlockingRequestDispatcher(StandardTestDispatcher(testScheduler))
            val native =
                object : RecordingNative() {
                    override fun createRequestControl(): Long = dispatcher.createRequestControl()

                    override fun getHistoryPage(
                        handle: Long,
                        chatId: Long,
                        limit: Int,
                        offsetId: Int,
                        offsetDate: Int,
                        addOffset: Int,
                    ): uniffi.monogram_mtproto.HistoryPageDto {
                        if (chatId == 43L) return super.getHistoryPage(
                            handle,
                            chatId,
                            limit,
                            offsetId,
                            offsetDate,
                            addOffset
                        )
                        requestedCursors += Triple(offsetId, offsetDate, addOffset)
                        if (requestedCursors.size == 1) {
                            firstStarted.complete(Unit)
                            check(releaseFirst.await(10, TimeUnit.SECONDS))
                            throw MtprotoException.Message("FLOOD_WAIT_17")
                        }
                        return uniffi.monogram_mtproto.HistoryPageDto(emptyList(), 0uL, null)
                    }
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "history-flood.session",
                    native = native,
                    nativeDispatcher = dispatcher,
                    refreshDcSidecar = {},
                    clock = ExtraClock(testScheduler),
                )
            val peer = PeerId(42)

            suspend fun older() =
                client.getHistoryPage(peer, 50, cursor.first, cursor.second, cursor.third)

            try {
                assertTrue(client.connect() is Outcome.Ok)
                dispatcher.offloadNextRequest = true
                val first = async { older() }
                firstStarted.await()
                val unrelated = async { client.getHistory(PeerId(43)) }
                runCurrent()
                assertTrue(unrelated.isCompleted)
                assertTrue(unrelated.await() is Outcome.Ok)
                assertEquals(1, native.getHistoryCalls)

                first.cancelAndJoin()
                releaseFirst.countDown()
                check(dispatcher.workerFinished.await(10, TimeUnit.SECONDS))

                val denied =
                    listOf(
                        client.getHistory(PeerId(43)),
                        client.getHistoryPageBackground(peer, 50, 0, 0, 0),
                    )
                for (result in denied) {
                    val error = (result as Outcome.Err).telegramError
                    assertEquals(TelegramError.Kind.Flood, error.kind)
                    assertEquals(17, error.retryAfterSeconds)
                }

                advanceTimeBy(16_999)
                assertEquals(1, (older() as Outcome.Err).telegramError.retryAfterSeconds)
                assertEquals(listOf(cursor), requestedCursors)
                assertEquals(1, native.getHistoryCalls)
                assertEquals(16_999L, testScheduler.currentTime)

                advanceTimeBy(1)
                assertTrue(older() is Outcome.Ok)
                assertEquals(17_000L, testScheduler.currentTime)
                assertEquals(listOf(cursor, cursor), requestedCursors)
            } finally {
                releaseFirst.countDown()
                client.close()
            }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun foregroundJoinPromotesTheSameBackgroundRequestWaitingInNative() =
        runTest {
            val queued = CompletableDeferred<Unit>()
            val admit = CountDownLatch(1)
            val dispatcher = BlockingRequestDispatcher(StandardTestDispatcher(testScheduler))
            val native =
                object : RecordingNative() {
                    var pageCalls = 0
                    var promotedClass: Int? = null
                    private var currentClass = DispatchClass.INTERACTIVE_READ

                    override fun createRequestControl(): Long {
                        dispatcher.createRequestControl()
                        return 42L
                    }

                    override fun setDispatchClass(classId: Int) {
                        currentClass = classId
                    }

                    override fun promoteRequestControl(
                        id: Long,
                        dispatchClass: Int,
                    ) {
                        assertEquals(42L, id)
                        promotedClass = dispatchClass
                    }

                    override fun getHistoryPage(
                        handle: Long,
                        chatId: Long,
                        limit: Int,
                        offsetId: Int,
                        offsetDate: Int,
                        addOffset: Int,
                    ): uniffi.monogram_mtproto.HistoryPageDto {
                        pageCalls++
                        check(currentClass == DispatchClass.BACKGROUND_READ)
                        queued.complete(Unit)
                        check(admit.await(10, TimeUnit.SECONDS))
                        check(promotedClass == DispatchClass.INTERACTIVE_READ)
                        return uniffi.monogram_mtproto.HistoryPageDto(emptyList(), 0uL, null)
                    }
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "history-promotion.session",
                    native = native,
                    nativeDispatcher = dispatcher,
                    refreshDcSidecar = {},
                )
            val peer = PeerId(42)
            try {
                assertTrue(client.connect() is Outcome.Ok)
                dispatcher.offloadNextRequest = true
                val background = async { client.getHistoryPageBackground(peer, 50, 123, 456, -1) }
                queued.await()
                val foreground = async(start = CoroutineStart.UNDISPATCHED) {
                    client.getHistoryPage(
                        peer,
                        50,
                        123,
                        456,
                        -1
                    )
                }
                assertEquals(DispatchClass.INTERACTIVE_READ, native.promotedClass)
                admit.countDown()
                val result = foreground.await()
                assertTrue(result is Outcome.Ok && result.value.isEmpty())
                assertEquals(result, background.await())
                assertEquals(1, native.pageCalls)
            } finally {
                admit.countDown()
                client.close()
            }
        }

    @Test
    fun equivalentOverlappingHistoryReadsShareOneNativeCallPerCursor() =
        runTest {
            val initialCursor = Triple(0, 0, 0)
            val olderCursor = Triple(123, 0, 0)
            val requestedCursors = mutableListOf<Triple<Int, Int, Int>>()
            val native =
                object : RecordingNative() {
                    override fun getHistory(
                        handle: Long,
                        chatId: Long,
                        limit: Int,
                    ): List<MessageDto> = getHistoryPage(handle, chatId, limit, 0, 0, 0).messages

                    override fun getHistoryPage(
                        handle: Long,
                        chatId: Long,
                        limit: Int,
                        offsetId: Int,
                        offsetDate: Int,
                        addOffset: Int,
                    ): uniffi.monogram_mtproto.HistoryPageDto {
                        requestedCursors += Triple(offsetId, offsetDate, addOffset)
                        val rows = super.getHistory(handle, chatId, limit)
                            .map { it.copy(text = "page $offsetId") }
                        return HistoryPageDto(rows, 3uL, 17)
                    }
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "history-overlap.session",
                    native = native,
                    nativeDispatcher = StandardTestDispatcher(testScheduler),
                    refreshDcSidecar = {},
                )
            val peer = PeerId(42)
            try {
                assertTrue(client.connect() is Outcome.Ok)
                val initial = async(start = CoroutineStart.UNDISPATCHED) { client.getHistory(peer) }
                val zeroPage = async(start = CoroutineStart.UNDISPATCHED) {
                    client.getHistoryPage(
                        peer,
                        50,
                        0,
                        0,
                        0
                    )
                }
                val zeroBackground = async(start = CoroutineStart.UNDISPATCHED) {
                    client.getHistoryPageBackground(
                        peer,
                        50,
                        0,
                        0,
                        0
                    )
                }
                val olderBackground = async(start = CoroutineStart.UNDISPATCHED) {
                    client.getHistoryPageBackground(
                        peer,
                        50,
                        123,
                        0,
                        0
                    )
                }
                val olderPage = async(start = CoroutineStart.UNDISPATCHED) {
                    client.getOlderHistoryPage(
                        peer,
                        50,
                        123
                    )
                }
                assertTrue(requestedCursors.isEmpty())

                for (request in listOf(initial, zeroPage, zeroBackground)) {
                    assertEquals("page 0", (request.await() as Outcome.Ok).value.single().text)
                }
                val page = (olderPage.await() as Outcome.Ok).value
                assertEquals("page 123", page.messages.single().text)
                assertEquals(3, page.rawCount)
                assertEquals(17, page.nextOffsetId)
                assertEquals(page.messages, (olderBackground.await() as Outcome.Ok).value)
                assertEquals(
                    mapOf(initialCursor to 1, olderCursor to 1),
                    requestedCursors.groupingBy { it }.eachCount()
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun getHistoryReturnsMessagesWithoutDialogFreeze() =
        runBlocking {
            val native = RecordingNative()
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "slice1.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            client.setDialogForeground(true)
            val history = client.getHistory(PeerId(42))
            assertTrue(history is Outcome.Ok)
            assertEquals(1, (history as Outcome.Ok).value.size)
            assertEquals("hello", history.value.single().text)
            assertEquals(1, native.getHistoryCalls)

            val chats = client.getChats()
            assertTrue(chats is Outcome.Ok)
            assertEquals(1, (chats as Outcome.Ok).value.size)
            assertEquals(1, native.getChatsCalls)
            client.close()
        }

    @Test
    fun getChatsCarriesMembershipForTheChatListToFilter() =
        runBlocking {
            val joined =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "membership.session",
                    native = RecordingNative(),
                    refreshDcSidecar = {},
                )
            val joinedChat = (joined.getChats() as Outcome.Ok).value.single()
            assertFalse(joinedChat.left)
            assertFalse(joinedChat.canSendPolls)
            assertFalse(joinedChat.isGroup)
            assertEquals(2, joinedChat.unreadMentionsCount)
            assertEquals(1, joinedChat.unreadReactionsCount)
            joined.close()

            val left =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "membership-left.session",
                    native = RecordingNative(chatLeft = true),
                    refreshDcSidecar = {},
                )
            val leftChat = (left.getChats() as Outcome.Ok).value.single()
            assertTrue("a left group must reach the chat list with the flag set", leftChat.left)
            assertTrue(leftChat.isGroup)
            left.close()
        }

    @Test
    fun getHistoryMapsMediaMetrics() =
        runBlocking {
            val native =
                RecordingNative(
                    historyMediaKind = "video",
                    historyDuration = 12,
                    historyWidth = 1280,
                    historyHeight = 720,
                )
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "slice-media-metrics.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            val history = client.getHistory(PeerId(42))
            assertTrue(history is Outcome.Ok)
            val message = (history as Outcome.Ok).value.single()
            assertEquals("video", message.mediaKind)
            assertEquals(12, message.mediaDuration)
            assertEquals(1280, message.mediaWidth)
            assertEquals(720, message.mediaHeight)
            client.close()
        }

    @Test
    fun idleAvatarDownloadsAreBackgroundMediaAndSurviveAnActiveHistory() =
        runBlocking {
            val native = RecordingNative(getHistorySleepMs = 300)
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "slice-history-avatar.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            val history = async { client.getHistory(PeerId(42)) }
            delay(50)
            val avatar =
                client.downloadMessageMedia(PeerId(42), 0, "avatar.bin", MediaPriority.IDLE)
            assertTrue(avatar is Outcome.Ok)
            assertEquals(1, native.downloadCalls)
            assertEquals(DispatchClass.BACKGROUND_MEDIA, native.downloadDispatchClass)
            assertTrue(history.await() is Outcome.Ok)
            client.close()
        }

    @Test
    fun activeHistoryStillDownloadsMessagePhotos() =
        runBlocking {
            val native = RecordingNative(getHistorySleepMs = 300)
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "slice-history-photo.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            val history = async { client.getHistory(PeerId(42)) }
            delay(50)
            val photo = client.downloadMessageMedia(PeerId(42), 7, "photo.bin")
            assertTrue(photo is Outcome.Ok)
            assertEquals(1, native.downloadCalls)
            assertTrue(history.await() is Outcome.Ok)
            client.close()
        }

    @Test
    fun dialogForegroundDownloadsAvatarsAndMessagePhotos() =
        runBlocking {
            val native = RecordingNative()
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "slice5-media.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            client.setDialogForeground(true)
            val avatar = client.downloadMessageMedia(PeerId(42), 0, "avatar.bin")
            assertTrue(avatar is Outcome.Ok)
            assertEquals(1, native.downloadCalls)

            val photo = client.downloadMessageMedia(PeerId(42), 7, "photo.bin")
            assertTrue(photo is Outcome.Ok)
            assertEquals(2, native.downloadCalls)
            client.close()
        }

    @Test
    fun clearingDialogForegroundReleasesNativeChannelWatcher() =
        runBlocking {
            val native = RecordingNative()
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "slice-clear-dialog.session",
                    native = native,
                    nativeDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
                    refreshDcSidecar = {},
                )
            client.connect()
            client.setDialogForeground(true)
            client.setDialogForeground(false)

            assertEquals(1, native.clearActiveDialogCalls)
            client.close()
        }

    @Test
    fun missingThumbIsNotFakeRpc400() =
        runBlocking {
            val native =
                object : RecordingNative() {
                    override fun downloadMessageThumb(
                        handle: Long,
                        chatId: Long,
                        messageId: Int,
                        destPath: String,
                    ): String =
                        throw uniffi.monogram_mtproto.MtprotoException.Message("no downloadable thumb")
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "slice-no-thumb.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            val thumb = client.downloadMessageThumb(PeerId(42), 7, "thumb.bin")
            assertTrue(thumb is Outcome.Err)
            val err = thumb as Outcome.Err
            assertEquals("no downloadable thumb", err.message)
            assertEquals(
                org.monogram.core.common.telegram.TelegramError.Kind.Media,
                err.telegramError.kind
            )
            assertEquals(0, err.telegramError.httpCode)
            client.close()
        }

    @Test
    fun fileIdInvalidThumbMapsToNoDownloadableThumb() =
        runBlocking {
            val native =
                object : RecordingNative() {
                    override fun downloadMessageThumb(
                        handle: Long,
                        chatId: Long,
                        messageId: Int,
                        destPath: String,
                    ): String =
                        throw uniffi.monogram_mtproto.MtprotoException.Message("RPC 400: FILE_ID_INVALID")
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "slice-file-id-thumb.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            val thumb = client.downloadMessageThumb(PeerId(42), 7, "thumb.bin")
            val err = thumb as Outcome.Err
            assertEquals("no downloadable thumb", err.message)
            client.close()
        }

    @Test
    fun fileMigrateThumbDefersInsteadOfHardFail() =
        runBlocking {
            val native =
                object : RecordingNative() {
                    override fun downloadMessageThumb(
                        handle: Long,
                        chatId: Long,
                        messageId: Int,
                        destPath: String,
                    ): String =
                        throw uniffi.monogram_mtproto.MtprotoException.Message("FILE_MIGRATE_2")
                }
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "slice-migrate-thumb.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            val thumb = client.downloadMessageThumb(PeerId(42), 7, "thumb.bin")
            val err = thumb as Outcome.Err
            assertEquals("media deferred", err.message)
            client.close()
        }

    @Test
    fun getHistoryProceedsWhileGetChatsIsBusy() =
        runBlocking {
            val native = RecordingNative(getChatsSleepMs = 400)
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "slice2-overlap.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            val chats = async { client.getChats() }
            delay(50)
            val started = System.nanoTime()
            val history = client.getHistory(PeerId(42))
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(history is Outcome.Ok)
            assertEquals(1, native.getHistoryCalls)
            assertTrue("elapsed=${elapsedMs}ms", elapsedMs < 300)
            chats.await()
            client.close()
        }

    @Test
    fun drainRunsWhileDialogIsForeground() =
        runBlocking {
            val native = RecordingNative()
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "slice2-drain.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            client.connect()
            client.setDialogForeground(true)
            val event =
                withTimeout(4_000) {
                    client.updates().filterIsInstance<MtprotoUpdate.NewMessage>().first()
                }
            assertEquals("hello", event.message.text)
            assertTrue(native.drainCalls >= 1)
            client.close()
        }

    @Test
    fun drainRunsWhileHistoryIsInFlight() =
        runBlocking {
            val native = RecordingNative(getHistorySleepMs = 2_500)
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "slice2-drain-history.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            client.connect()
            val history = async { client.getHistory(PeerId(42)) }
            delay(50)
            val event =
                withTimeout(4_000) {
                    client.updates().filterIsInstance<MtprotoUpdate.NewMessage>().first()
                }
            assertEquals("hello", event.message.text)
            assertTrue(native.drainCalls >= 1)
            val historyResult = history.await()
            assertTrue(historyResult is Outcome.Ok)
            client.close()
        }

    @Test
    fun mapsEditedAndDeletedUpdateEvents() =
        runBlocking {
            val native = RecordingNative()
            native.drainEventsOverride =
                listOf(
                    UpdateEventDto.MessageEdited(
                        MessageDto(
                            chatId = 42L,
                            id = 7,
                            senderId = 1L,
                            text = "edited",
                            date = 1L,
                            editDate = 2L,
                            outgoing = false,
                            mediaKind = null,
                            mediaCacheKey = null,
                            thumbCacheKey = null,
                            mediaDuration = null,
                            mediaWidth = null,
                            mediaHeight = null,
                            replyQuote = null,
                            replyQuoteEntitiesJson = null,
                            entitiesJson = null,
                            noforwards = false,
                            replyToMsgId = null,
                            replyToTopId = null,
                            fwdFrom = null,
                            fwdFromId = null,
                            fwdDate = null,
                            viaBot = null,
                            senderName = null,
                            senderEmojiStatusDocumentId = null,
                            groupedId = null,
                            fileName = null,
                            fileSize = null,
                            supportsStreaming = false,
                            reactionsJson = null,
                            repliesCount = 0,
                            discussionPeerId = null,
                            replyMarkupJson = null,
                            forumTopic = false,
                            senderPhotoCacheKey = null,
                        ),
                    ),
                    UpdateEventDto.MessagesDeleted(chatId = 42L, messageIds = listOf(7)),
                )
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "slice-update-mutations.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            client.connect()

            val events =
                withTimeout(4_000) {
                    client
                        .updates()
                        .filter { it is MtprotoUpdate.MessageEdited || it is MtprotoUpdate.MessagesDeleted }
                        .take(2)
                        .toList()
                }

            val edited = events.filterIsInstance<MtprotoUpdate.MessageEdited>().single()
            assertEquals("edited", edited.message.text)
            assertEquals(2L, edited.message.editDate)
            val deleted = events.filterIsInstance<MtprotoUpdate.MessagesDeleted>().single()
            assertEquals(PeerId(42), deleted.chatId)
            assertEquals(listOf(7), deleted.messageIds)
            client.close()
        }

    @Test
    fun sendPhotoUsesNativePathAndCaption() =
        runBlocking {
            val native = RecordingNative()
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "slice-send-photo.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            val entities = """[{"kind":"bold","offset":0,"length":2}]"""
            val sent = client.sendPhoto(PeerId(42), "/tmp/photo.jpg", "hi", entitiesJson = entities)
            assertTrue(sent is Outcome.Ok)
            assertEquals("photo", (sent as Outcome.Ok).value.mediaKind)
            assertEquals("hi", sent.value.text)
            assertEquals("/tmp/photo.jpg", native.lastPhotoPath)
            assertEquals("hi", native.lastPhotoCaption)
            assertEquals(entities, native.lastPhotoEntities)
            client.close()
        }

    @Test
    fun forwardMessageUsesNativeIds() =
        runBlocking {
            val native = RecordingNative()
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "slice-forward.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            val sent = client.forwardMessage(PeerId(42), 7, PeerId(99))
            assertTrue(sent is Outcome.Ok)
            assertEquals(
                99L,
                (sent as Outcome.Ok)
                    .value
                    .single()
                    .id.chatId.value,
            )
            assertEquals(42L, sent.value.single().fwdFromId)
            assertEquals(123L, sent.value.single().fwdDate)
            assertEquals(42L, native.lastForwardFrom)
            assertEquals(99L, native.lastForwardTo)
            assertEquals(7, native.lastForwardMessageId)
            client.close()
        }

    @Test
    fun forwardMessagesUsesNativeIdsAndDropAuthor() =
        runBlocking {
            val native = RecordingNative()
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "slice-forward-batch.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            val sent =
                client.forwardMessages(PeerId(42), listOf(7, 8), PeerId(99), dropAuthor = true)
            assertTrue(sent is Outcome.Ok)
            assertEquals(listOf(7, 8), native.lastForwardMessageIds)
            assertTrue(native.lastForwardDropAuthor)
            client.close()
        }

    @Test
    fun getPinnedMessagesUsesNativeChatId() =
        runBlocking {
            val native = RecordingNative()
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "slice-pinned.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            val pinned = client.getPinnedMessages(PeerId(42))
            assertTrue(pinned is Outcome.Ok)
            assertEquals(1, (pinned as Outcome.Ok).value.size)
            assertEquals(
                11,
                pinned.value
                    .single()
                    .id.id,
            )
            assertEquals(42L, native.lastPinnedChatId)
            client.close()
        }

    @Test
    fun getForumTopicsMapsGeneralAndDropsDeleted() =
        runBlocking {
            val native = RecordingNative()
            val client =
                BridgedMtprotoClient(
                    credentials = TelegramCredentials(1, "hash"),
                    sessionPath = "slice-forum.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            val page = client.getForumTopics(PeerId(42))
            assertTrue(page is Outcome.Ok)
            val topics = (page as Outcome.Ok).value.topics
            assertEquals(1, topics.size)
            assertEquals(1, topics.single().id)
            assertEquals("General", topics.single().title)
            assertEquals(3, topics.single().unreadCount)
            assertEquals(42L, native.lastForumChatId)
            client.close()
        }

    @Test
    fun checklistTextEditsKeepNullableItemIdentityAndEntitySnapshots() =
        runBlocking {
            val captured = mutableListOf<uniffi.monogram_mtproto.ChecklistTextEditDto>()
            val native =
                object : RecordingNative() {
                    override fun removeChecklistItem(
                        handle: Long,
                        chatId: Long,
                        messageId: Int,
                        edit: uniffi.monogram_mtproto.ChecklistTextEditDto,
                    ) {
                        assertEquals(42L, chatId)
                        assertEquals(37, messageId)
                        captured += edit
                    }

                    override fun editChecklistText(
                        handle: Long,
                        chatId: Long,
                        messageId: Int,
                        edit: uniffi.monogram_mtproto.ChecklistTextEditDto,
                    ) {
                        assertEquals(42L, chatId)
                        assertEquals(37, messageId)
                        captured += edit
                    }
                }
            val client =
                BridgedMtprotoClient(
                    TelegramCredentials(1, "hash"),
                    "checklist-text.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            val entities =
                listOf(
                    org.monogram.core.models
                        .TextEntity("bold", 3, 4),
                )
            try {
                for (id in listOf(null, 0, -7, Int.MAX_VALUE)) {
                    val spec =
                        org.monogram.core.models.ChecklistTextEdit(
                            id,
                            "😀 task",
                            entities,
                            "Old",
                            emptyList(),
                        )
                    assertTrue(client.editChecklistText(PeerId(42), 37, spec) is Outcome.Ok)
                    assertEquals(
                        uniffi.monogram_mtproto.ChecklistTextEditDto(
                            id,
                            "😀 task",
                            org.monogram.core.models.TextEntities
                                .serialize(entities),
                            "Old",
                            null,
                        ),
                        captured.last(),
                    )
                }
                assertEquals(4, captured.size)
                for (id in listOf(0, -7, Int.MAX_VALUE)) {
                    val spec =
                        org.monogram.core.models.ChecklistTextEdit(
                            id,
                            "",
                            emptyList(),
                            "😀 task",
                            entities,
                        )
                    assertTrue(client.removeChecklistItem(PeerId(42), 37, spec) is Outcome.Ok)
                    assertEquals(
                        uniffi.monogram_mtproto.ChecklistTextEditDto(
                            id,
                            "",
                            null,
                            "😀 task",
                            org.monogram.core.models.TextEntities
                                .serialize(entities),
                        ),
                        captured.last(),
                    )
                }
                assertTrue(
                    client.removeChecklistItem(
                        PeerId(42),
                        37,
                        org.monogram.core.models.ChecklistTextEdit(
                            null,
                            "",
                            emptyList(),
                            "Old",
                            emptyList(),
                        ),
                    ) is Outcome.Err,
                )
                assertEquals(7, captured.size)
            } finally {
                client.close()
            }
        }

    @Test
    fun mediaReplacementPreservesTargetAndMapsFloodWait() =
        runBlocking {
            var observed: uniffi.monogram_mtproto.UploadItemDto? = null
            val native =
                object : RecordingNative() {
                    override fun replaceUploadedMedia(
                        handle: Long,
                        chatId: Long,
                        messageId: Int,
                        expectedKey: String,
                        item: uniffi.monogram_mtproto.UploadItemDto,
                    ): uniffi.monogram_mtproto.MessageDto {
                        assertEquals(42L, chatId)
                        assertEquals(
                            37,
                            messageId,
                        )
                        assertEquals("photo:fixture", expectedKey)
                        observed = item
                        throw uniffi.monogram_mtproto.MtprotoException.Message("RPC 420: FLOOD_WAIT_60")
                    }
                }
            val client =
                BridgedMtprotoClient(
                    TelegramCredentials(1, "hash"),
                    "media-replacement.session",
                    native = native,
                    refreshDcSidecar = {},
                )
            try {
                val result =
                    client.replaceUploadedMedia(
                        PeerId(42),
                        37,
                        "photo:fixture",
                        org.monogram.core.models.UploadItem(
                            "synthetic-unused",
                            "photo",
                            mimeType = "image/jpeg",
                            fileName = "fixture.jpg",
                        ),
                    )
                assertTrue(result is Outcome.Err)
                assertEquals(60, (result as Outcome.Err).telegramError.retryAfterSeconds)
                assertEquals("photo", observed!!.kind)
                assertEquals(
                    "synthetic-unused",
                    observed!!.path,
                )
                assertEquals(
                    "",
                    observed!!.caption,
                )
                org.junit.Assert.assertNull(observed!!.entitiesJson)
            } finally {
                client.close()
            }
        }

    private fun sampleChat(title: String) =
        ChatDto(
            id = 42L,
            title = title,
            isChannel = true,
            isGroup = false,
            isForum = false,
            left = false,
            unreadCount = 1,
            lastMessagePreview = "hi",
            lastMessageDate = 1L,
            archived = false,
            muted = false,
            isContact = false,
            isBot = false,
            isVerified = false,
            photoCacheKey = null,
            pinned = false,
            readInboxMaxId = 0,
            readOutboxMaxId = 0,
            peerStatus = null,
            peerStatusAt = null,
            lastMediaThumbCacheKey = null,
            lastMessageId = 3,
            canView = true,
            canSendPlain = true,
            canSendPhotos = true,
            canSendPolls = true,
            canForward = true,
            canDeleteOthers = false,
            emojiStatusDocumentId = null,
            lastMessageOutgoing = false,
            muteOverride = false,
            unreadMark = false,
            unreadMentionsCount = 0,
            unreadReactionsCount = 0,
            canManageTopics = false,
        )

    private open class RecordingNative(
        private val getChatsSleepMs: Long = 0,
        private val getHistorySleepMs: Long = 0,
        private val historyMediaKind: String? = null,
        private val historyDuration: Int? = null,
        private val historyWidth: Int? = null,
        private val historyHeight: Int? = null,
        private val chatLeft: Boolean = false,
    ) : MtprotoNative by MtprotoNative.Stub {
        override fun drainUpdateBatches(handle: Long) =
            uniffi.monogram_mtproto.UpdateDrainDto(emptyList(), drainUpdates(handle), false)

        var getChatsCalls = 0
        var getHistoryCalls = 0
        var downloadCalls = 0

        @Volatile
        var downloadDispatchClass: Int = -1

        private val classByThread = ThreadLocal.withInitial { DispatchClass.INTERACTIVE_READ }

        override fun setDispatchClass(classId: Int) {
            classByThread.set(classId)
        }

        var drainCalls = 0
        var clearActiveDialogCalls = 0
        var drainEventsOverride: List<UpdateEventDto>? = null
        var lastPhotoPath: String? = null
        var lastPhotoCaption: String? = null
        var lastPhotoEntities: String? = null
        var lastForwardFrom: Long? = null
        var lastForwardTo: Long? = null
        var lastForwardMessageId: Int? = null
        var lastForwardMessageIds: List<Int>? = null
        var lastForwardDropAuthor = false
        var lastPinnedChatId: Long? = null
        var lastForumChatId: Long? = null

        override fun createClient(
            apiId: Int,
            apiHash: String,
            sessionPath: String,
        ): Long = 1L

        override fun connect(handle: Long) = Unit

        override fun isAuthorized(handle: Long): Boolean = true

        override fun startUpdates(handle: Long) = Unit

        override fun clearActiveDialog(handle: Long) {
            clearActiveDialogCalls++
        }

        override fun getChats(handle: Long): List<ChatDto> {
            getChatsCalls++
            if (getChatsSleepMs > 0) Thread.sleep(getChatsSleepMs)
            return listOf(
                ChatDto(
                    id = 42L,
                    title = "Ada",
                    isChannel = false,
                    isGroup = chatLeft,
                    isForum = false,
                    left = chatLeft,
                    unreadCount = 0,
                    lastMessagePreview = "hi",
                    lastMessageDate = 1L,
                    archived = false,
                    muted = false,
                    isContact = true,
                    isBot = false,
                    isVerified = false,
                    photoCacheKey = null,
                    pinned = false,
                    readInboxMaxId = 0,
                    readOutboxMaxId = 0,
                    peerStatus = null,
                    peerStatusAt = null,
                    lastMediaThumbCacheKey = null,
                    lastMessageId = 0,
                    canView = true,
                    canSendPlain = true,
                    canSendPhotos = true,
                    canSendPolls = false,
                    canForward = true,
                    canDeleteOthers = false,
                    emojiStatusDocumentId = null,
                    lastMessageOutgoing = false,
                    muteOverride = false,
                    unreadMark = false,
                    unreadMentionsCount = 2,
                    unreadReactionsCount = 1,
                    canManageTopics = false,
                ),
            )
        }

        override fun getHistoryPage(
            handle: Long,
            chatId: Long,
            limit: Int,
            offsetId: Int,
            offsetDate: Int,
            addOffset: Int,
        ): HistoryPageDto {
            val messages = getHistory(handle, chatId, limit)
            return HistoryPageDto(messages, messages.size.toULong(), messages.lastOrNull()?.id)
        }

        override fun getHistory(
            handle: Long,
            chatId: Long,
            limit: Int,
        ): List<MessageDto> {
            getHistoryCalls++
            if (getHistorySleepMs > 0) Thread.sleep(getHistorySleepMs)
            return listOf(
                MessageDto(
                    chatId = chatId,
                    id = 7,
                    senderId = 1L,
                    text = "hello",
                    date = 1L,
                    editDate = null,
                    outgoing = false,
                    mediaKind = historyMediaKind,
                    mediaCacheKey = if (historyMediaKind != null) "doc:1" else null,
                    thumbCacheKey = if (historyMediaKind != null) "doc:1:thumb" else null,
                    mediaDuration = historyDuration,
                    mediaWidth = historyWidth,
                    mediaHeight = historyHeight,
                    replyQuote = null,
                    replyQuoteEntitiesJson = null,
                    entitiesJson = null,
                    noforwards = false,
                    replyToMsgId = null,
                    replyToTopId = null,
                    fwdFrom = null,
                    fwdFromId = null,
                    fwdDate = null,
                    viaBot = null,
                    senderName = null,
                    senderEmojiStatusDocumentId = null,
                    groupedId = null,
                    fileName = null,
                    fileSize = null,
                    supportsStreaming = false,
                    reactionsJson = null,
                    repliesCount = 0,
                    discussionPeerId = null,
                    replyMarkupJson = null,
                    forumTopic = false,
                    senderPhotoCacheKey = null,
                ),
            )
        }

        override fun getPinnedMessages(
            handle: Long,
            chatId: Long,
            limit: Int,
        ): List<MessageDto> {
            lastPinnedChatId = chatId
            return listOf(
                MessageDto(
                    chatId = chatId,
                    id = 11,
                    senderId = 1L,
                    text = "pin",
                    date = 1L,
                    editDate = null,
                    outgoing = false,
                    mediaKind = null,
                    mediaCacheKey = null,
                    thumbCacheKey = null,
                    mediaDuration = null,
                    mediaWidth = null,
                    mediaHeight = null,
                    replyQuote = null,
                    replyQuoteEntitiesJson = null,
                    entitiesJson = null,
                    noforwards = false,
                    replyToMsgId = null,
                    replyToTopId = null,
                    fwdFrom = null,
                    fwdFromId = null,
                    fwdDate = null,
                    viaBot = null,
                    senderName = null,
                    senderEmojiStatusDocumentId = null,
                    groupedId = null,
                    fileName = null,
                    fileSize = null,
                    supportsStreaming = false,
                    reactionsJson = null,
                    repliesCount = 0,
                    discussionPeerId = null,
                    replyMarkupJson = null,
                    forumTopic = false,
                    senderPhotoCacheKey = null,
                ),
            )
        }

        override fun getForumTopics(
            handle: Long,
            chatId: Long,
            offsetDate: Int,
            offsetId: Int,
            offsetTopic: Int,
            limit: Int,
        ): ForumTopicsPageDto {
            lastForumChatId = chatId
            return ForumTopicsPageDto(
                count = 2,
                topics =
                    listOf(
                        ForumTopicDto(
                            id = 1,
                            title = "General",
                            iconColor = 0x6FB9F0,
                            iconEmojiId = null,
                            topMessage = 10,
                            date = 1,
                            unreadCount = 3,
                            unreadMentionsCount = 0,
                            unreadReactionsCount = 0,
                            readInboxMaxId = 8,
                            pinned = true,
                            closed = false,
                            hidden = false,
                            short = false,
                            deleted = false,
                            lastMessagePreview = "hi",
                        ),
                        ForumTopicDto(
                            id = 9,
                            title = "",
                            iconColor = 0,
                            iconEmojiId = null,
                            topMessage = 0,
                            date = 0,
                            unreadCount = 0,
                            unreadMentionsCount = 0,
                            unreadReactionsCount = 0,
                            readInboxMaxId = 0,
                            pinned = false,
                            closed = false,
                            hidden = false,
                            short = false,
                            deleted = true,
                            lastMessagePreview = null,
                        ),
                    ),
            )
        }

        override fun drainUpdates(handle: Long): List<UpdateEventDto> {
            drainCalls++
            drainEventsOverride?.let { return it }
            return listOf(
                UpdateEventDto.NewMessage(
                    MessageDto(
                        chatId = 42L,
                        id = 7,
                        senderId = 1L,
                        text = "hello",
                        date = 1L,
                        editDate = null,
                        outgoing = false,
                        mediaKind = null,
                        mediaCacheKey = null,
                        thumbCacheKey = null,
                        mediaDuration = null,
                        mediaWidth = null,
                        mediaHeight = null,
                        replyQuote = null,
                        replyQuoteEntitiesJson = null,
                        entitiesJson = null,
                        noforwards = false,
                        replyToMsgId = null,
                        replyToTopId = null,
                        fwdFrom = null,
                        fwdFromId = null,
                        fwdDate = null,
                        viaBot = null,
                        senderName = null,
                        senderEmojiStatusDocumentId = null,
                        groupedId = null,
                        fileName = null,
                        fileSize = null,
                        supportsStreaming = false,
                        reactionsJson = null,
                        repliesCount = 0,
                        discussionPeerId = null,
                        replyMarkupJson = null,
                        forumTopic = false,
                        senderPhotoCacheKey = null,
                    ),
                ),
            )
        }

        override fun downloadMessageMedia(
            handle: Long,
            chatId: Long,
            messageId: Int,
            destPath: String,
        ): String {
            downloadCalls++
            downloadDispatchClass = classByThread.get()
            return destPath
        }

        override fun sendPhotoMessage(
            handle: Long,
            chatId: Long,
            path: String,
            caption: String,
            replyToMsgId: Int,
            topMsgId: Int,
            entitiesJson: String?,
        ): MessageDto {
            lastPhotoPath = path
            lastPhotoCaption = caption
            lastPhotoEntities = entitiesJson
            return MessageDto(
                chatId = chatId,
                id = 9,
                senderId = 1L,
                text = caption.ifEmpty { null },
                date = 1L,
                editDate = null,
                outgoing = true,
                mediaKind = "photo",
                mediaCacheKey = "photo:9",
                thumbCacheKey = "photo:9:thumb",
                mediaDuration = null,
                mediaWidth = 100,
                mediaHeight = 80,
                replyQuote = null,
                replyQuoteEntitiesJson = null,
                entitiesJson = null,
                noforwards = false,
                replyToMsgId = null,
                replyToTopId = null,
                fwdFrom = null,
                fwdFromId = null,
                fwdDate = null,
                viaBot = null,
                senderName = null,
                senderEmojiStatusDocumentId = null,
                groupedId = null,
                fileName = null,
                fileSize = null,
                supportsStreaming = false,
                reactionsJson = null,
                repliesCount = 0,
                discussionPeerId = null,
                replyMarkupJson = null,
                forumTopic = false,
                senderPhotoCacheKey = null,
            )
        }

        override fun forwardMessages(
            handle: Long,
            fromChatId: Long,
            messageIds: List<Int>,
            toChatId: Long,
            dropAuthor: Boolean,
        ): List<MessageDto> {
            lastForwardFrom = fromChatId
            lastForwardTo = toChatId
            lastForwardMessageIds = messageIds
            lastForwardMessageId = messageIds.singleOrNull()
            lastForwardDropAuthor = dropAuthor
            return listOf(
                MessageDto(
                    chatId = toChatId,
                    id = 11,
                    senderId = 1L,
                    text = "fwd",
                    date = 1L,
                    editDate = null,
                    outgoing = true,
                    mediaKind = null,
                    mediaCacheKey = null,
                    thumbCacheKey = null,
                    mediaDuration = null,
                    mediaWidth = null,
                    mediaHeight = null,
                    replyQuote = null,
                    replyQuoteEntitiesJson = null,
                    entitiesJson = null,
                    noforwards = false,
                    replyToMsgId = null,
                    replyToTopId = null,
                    fwdFrom = "Ada",
                    fwdFromId = 42L,
                    fwdDate = 123L,
                    viaBot = null,
                    senderName = null,
                    senderEmojiStatusDocumentId = null,
                    groupedId = null,
                    fileName = null,
                    fileSize = null,
                    supportsStreaming = false,
                    reactionsJson = null,
                    repliesCount = 0,
                    discussionPeerId = null,
                    replyMarkupJson = null,
                    forumTopic = false,
                    senderPhotoCacheKey = null,
                ),
            )
        }
    }
}
