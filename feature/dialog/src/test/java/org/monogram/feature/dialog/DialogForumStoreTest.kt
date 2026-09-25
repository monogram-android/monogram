package org.monogram.feature.dialog

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.database.draftMetaKey
import org.monogram.core.markup.FakeMarkupParser
import org.monogram.core.models.AuthState
import org.monogram.core.models.Chat
import org.monogram.core.models.ContactsSearch
import org.monogram.core.models.Folder
import org.monogram.core.models.ForumTopic
import org.monogram.core.models.ForumTopicsPage
import org.monogram.core.models.GENERAL_FORUM_TOPIC_ID
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.ProfileMember
import org.monogram.core.models.ProfileMemberPage
import org.monogram.core.models.SearchPeer
import org.monogram.core.models.StickerCatalog
import org.monogram.core.models.StickerPack
import org.monogram.core.models.StyledText
import org.monogram.core.models.TextEntity
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.bridge.UpdatesCursor

@OptIn(ExperimentalCoroutinesApi::class)
class DialogForumStoreTest {

    @Before
    fun resetBefore() = resetMemory()

    @After
    fun reset() {
        Dispatchers.resetMain()
        resetMemory()
    }

    private fun resetMemory() {
        SenderTagMemory.clear()
        TopicListMemory.clear()
        PinnedBarMemory.clear()
        SavedGifMemory.clear()
        StickerCatalogMemory.clear()
        StickerPackMemory.clear()
    }

    @Test
    fun refreshForumLoadsTopicList() = runBlocking {
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = null,
            sessionStore = null,
            chatId = PeerId(5),
            seedIsForum = true,
            mainContext = Dispatchers.Unconfined,
            markupContext = Dispatchers.Unconfined,
        ).create()
        assertEquals(1, client.forumCalls)
        assertEquals(0, client.historyCalls)
        assertTrue(store.state.showTopicList)
        assertEquals("General", store.state.topics.single().title)
        assertEquals(3, store.state.topics.single().unreadCount)
        store.dispose()
    }

    @Test
    fun generalHistoryOmitsOtherTopicMessagesFromServerPage() = runBlocking {
        val client = FakeClient()
        client.history = listOf(
            Message(
                MessageId(PeerId(5), 10), PeerId(1), "general", 1, outgoing = false,
            ),
            Message(
                MessageId(PeerId(5), 11), null, "там вроде не бета", 2, outgoing = true,
                replyToMsgId = 9,
                replyToTopId = 42,
                forumTopic = true,
            ),
        )
        val store = DialogStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = null,
            sessionStore = null,
            chatId = PeerId(5),
            threadTopMsgId = GENERAL_FORUM_TOPIC_ID,
            seedIsForum = true,
            mainContext = Dispatchers.Unconfined,
            markupContext = Dispatchers.Unconfined,
        ).create()
        yield()
        assertEquals(listOf(10), store.state.messages.map { it.id.id })
        store.dispose()
    }

    @Test
    fun rejectedHideDoesNotPersistHiddenGeneral() = runBlocking {
        val warmup = FakeWarmup()
        warmup.storedChats[5] = Chat(
            id = PeerId(5),
            title = "Forum",
            isGroup = true,
            isForum = true,
            canManageTopics = true,
        )
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = warmup,
            sessionStore = null,
            chatId = PeerId(5),
            seedIsForum = true,
            mainContext = Dispatchers.Unconfined,
            markupContext = Dispatchers.Unconfined,
        ).create()
        yield()
        assertTrue(store.state.canManageTopics)
        store.accept(DialogStore.Intent.ToggleTopicHidden(1, true))
        yield()
        assertEquals(1, client.hideCalls)
        assertEquals(false, store.state.topics.single { it.id == 1 }.hidden)
        assertEquals(false, TopicListMemory.get(5).single { it.id == 1 }.hidden)
        store.dispose()
    }

    @Test
    fun ordinaryMegagroupLoadsHistoryWithoutProbingTopics() = runBlocking {
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = null,
            sessionStore = null,
            chatId = PeerId(-(1_000_000_000_000L + 5)),
            seedIsForum = false,
            mainContext = Dispatchers.Unconfined,
            markupContext = Dispatchers.Unconfined,
        ).create()
        assertEquals(0, client.forumCalls)
        assertEquals(1, client.historyCalls)
        assertFalse(store.state.isForum)
        assertFalse(store.state.showTopicList)
        store.dispose()
    }

    @Test
    fun liveEdgeHistoryKeepsCachedOlderMessages() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val warmup = FakeWarmup()
        val chatId = PeerId(5)
        warmup.storedChats[5] = Chat(chatId, "Saved", lastMessageId = 20)
        warmup.storedMessages[5] = (1..20).map { id ->
            Message(
                id = MessageId(chatId, id),
                senderId = PeerId(1),
                text = "m$id",
                date = id.toLong(),
                outgoing = false,
            )
        }
        val client = FakeClient().apply {
            history = (11..20).map { id ->
                Message(
                    id = MessageId(chatId, id),
                    senderId = PeerId(1),
                    text = "m$id",
                    date = id.toLong(),
                    outgoing = false,
                )
            }
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = warmup,
            sessionStore = null,
            chatId = chatId,
            seedIsForum = false,
            mainContext = Dispatchers.Unconfined,
            markupContext = Dispatchers.Unconfined,
        ).create()
        try {
            kotlinx.coroutines.withTimeout(2000) {
                while (store.state.messages.none { it.id.id == 20 }) {
                    delay(10)
                }
            }
            assertTrue(store.state.messages.any { it.id.id == 20 })
            assertFalse(store.state.messages.any { it.id.id == 1 })
            assertEquals(16, store.state.messages.map { it.id.id }.toSet().size)
            assertTrue(store.state.hasOlder)
            store.accept(DialogStore.Intent.LoadOlder)
            kotlinx.coroutines.withTimeout(2000) {
                while (store.state.messages.none { it.id.id == 1 }) {
                    delay(10)
                }
            }
            assertEquals(20, store.state.messages.map { it.id.id }.toSet().size)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun dialogShowsCachedMessagesNewerThanChatLastId() = runBlocking {
        val warmup = FakeWarmup()
        val chatId = PeerId(5)
        warmup.storedChats[5] = Chat(chatId, "Saved", lastMessageId = 10)
        warmup.storedMessages[5] = (8..12).map { id ->
            Message(
                id = MessageId(chatId, id),
                senderId = PeerId(1),
                text = "m$id",
                date = id.toLong(),
                outgoing = false,
            )
        }
        val client = FakeClient().apply {
            history = (9..12).map { id ->
                Message(
                    id = MessageId(chatId, id),
                    senderId = PeerId(1),
                    text = "m$id",
                    date = id.toLong(),
                    outgoing = false,
                )
            }
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = warmup,
            sessionStore = null,
            chatId = chatId,
            seedIsForum = false,
            mainContext = Dispatchers.Unconfined,
            markupContext = Dispatchers.Unconfined,
        ).create()
        try {
            assertTrue(
                "cache newer than lastMessageId must stay visible",
                store.state.messages.any { it.id.id == 12 },
            )
            assertEquals(emptyList<Int>(), warmup.deletedIds)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun pinnedBarHydratesFromMetaBeforeNetwork() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val warmup = FakeWarmup()
        val chatId = PeerId(5)
        val pin = Message(
            id = MessageId(chatId, 9),
            senderId = PeerId(1),
            text = "pin",
            date = 9L,
            outgoing = false,
        )
        warmup.storedChats[5] = Chat(chatId, "Saved", lastMessageId = 9)
        warmup.storedMessages[5] = listOf(pin)
        val meta = FakeMeta().apply { values[pinnedMetaKey(5)] = "9" }
        val client = FakeClient().apply { pinned = listOf(pin) }
        val store = DialogStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = warmup,
            sessionStore = meta,
            chatId = chatId,
            seedIsForum = false,
            mainContext = dispatcher,
            markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            assertEquals("pin", store.state.pinnedMessages.single().text)
            assertEquals(0, client.pinnedCalls)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun staleForumFlagRecoversToHistoryAndDoesNotProbeAgain() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val client = FakeClient().apply { forumError = "RPC 400: CHANNEL_FORUM_MISSING" }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(-1_000_000_000_005), seedIsForum = true, mainContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            assertEquals(1, client.forumCalls)
            assertEquals(1, client.historyCalls)
            assertFalse(store.state.isForum)
            assertFalse(store.state.loadingTopics)
            store.accept(DialogStore.Intent.Refresh)
            advanceUntilIdle()
            assertEquals(1, client.forumCalls)
            assertEquals(2, client.historyCalls)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun channelOpenedWithoutForumFlagProbesTopicsAndShowsTheList() = runBlocking {
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = null,
            sessionStore = null,
            chatId = PeerId(-(1_000_000_000_000L + 5)),
            seedIsForum = null,
            mainContext = Dispatchers.Unconfined,
            markupContext = Dispatchers.Unconfined,
        ).create()
        assertEquals(1, client.forumCalls)
        assertEquals(0, client.historyCalls)
        assertTrue(store.state.isForum)
        assertTrue(store.state.showTopicList)
        assertEquals("General", store.state.topics.single().title)
        store.dispose()
    }

    @Test
    fun channelOpenedWithoutForumFlagFallsBackToHistoryWhenNotAForum() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val client = FakeClient().apply { forumError = "RPC 400: CHANNEL_FORUM_MISSING" }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(-1_000_000_000_005), seedIsForum = null, mainContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            assertEquals(1, client.forumCalls)
            assertEquals(1, client.historyCalls)
            assertFalse(store.state.isForum)
            assertFalse(store.state.showTopicList)
            assertFalse(store.state.loadingTopics)
            store.accept(DialogStore.Intent.Refresh)
            advanceUntilIdle()
            assertEquals(1, client.forumCalls)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun probedNonForumChannelDoesNotInventACachedDialog() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val warmup = FakeWarmup()
        val client = FakeClient().apply { forumError = "RPC 400: CHANNEL_FORUM_MISSING" }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup, sessionStore = null,
            chatId = PeerId(-1_000_000_000_005), seedIsForum = null, mainContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            assertFalse(store.state.isForum)
            assertTrue(warmup.upsertedChats.isEmpty())
        } finally {
            store.dispose()
        }
    }

    @Test
    fun cachedNonForumRowSkipsTheTopicProbe() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val chatId = PeerId(-1_000_000_000_005)
        val warmup = FakeWarmup().apply {
            storedChats[chatId.value] = Chat(id = chatId, title = "News", isChannel = true)
        }
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup, sessionStore = null,
            chatId = chatId, seedIsForum = null, mainContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            assertEquals(0, client.forumCalls)
            assertEquals(1, client.historyCalls)
            assertFalse(store.state.showTopicList)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun topicRefreshUsesRepliesAndHeader() = runBlocking {
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = null,
            sessionStore = null,
            chatId = PeerId(5),
            threadTopMsgId = 42,
            seedIsForum = true,
            mainContext = Dispatchers.Unconfined,
            markupContext = Dispatchers.Unconfined,
        ).create()
        assertEquals(1, client.repliesCalls)
        assertEquals(42, client.lastRepliesTop)
        assertEquals(0, client.historyCalls)
        assertEquals(listOf(42), client.topicsById)
        assertEquals("Bugs", store.state.title)
        assertEquals(0x6FB9F0, store.state.topicIconColor)
        assertFalse(store.state.showTopicList)
        store.dispose()
    }

    @Test
    fun commentThreadUsesRepliesWithoutForumHeader() = runBlocking {
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = null,
            sessionStore = null,
            chatId = PeerId(5),
            threadTopMsgId = 42,
            seedIsForum = false,
            mainContext = Dispatchers.Unconfined,
            markupContext = Dispatchers.Unconfined,
        ).create()
        assertEquals(1, client.repliesCalls)
        assertEquals(42, client.lastRepliesTop)
        assertEquals(0, client.historyCalls)
        assertEquals(0, client.forumCalls)
        assertEquals(0, client.topicsByIdCalls)
        assertTrue(store.state.isCommentThread)
        assertFalse(store.state.isForum)
        assertEquals(null, store.state.error)
        store.dispose()
    }

    @Test
    fun topicHeaderForumMissingDoesNotSurfaceError() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val client = FakeClient().apply { topicsByIdError = "RPC 400: CHANNEL_FORUM_MISSING" }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), threadTopMsgId = 42, seedIsForum = true, mainContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            assertEquals(1, client.repliesCalls)
            assertEquals(1, client.topicsByIdCalls)
            assertEquals(null, store.state.error)
            assertTrue(store.state.messages.isNotEmpty())
        } finally {
            store.dispose()
        }
    }

    @Test
    fun sendInTopicPassesTopMsgIdOnReply() = runBlocking {
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = null,
            sessionStore = null,
            chatId = PeerId(5),
            threadTopMsgId = 42,
            seedIsForum = true,
            mainContext = Dispatchers.Unconfined,
            markupContext = Dispatchers.Unconfined,
        ).create()
        store.accept(DialogStore.Intent.ReplyTo(client.topicMessage(9)))
        store.accept(DialogStore.Intent.DraftChanged("hi"))
        store.accept(DialogStore.Intent.Send())
        assertEquals(9, client.lastSendReply)
        assertEquals(42, client.lastSendTop)
        store.dispose()
    }

    @Test
    fun sendDropsUnsendableHeadingEntity() = runBlocking {
        val client = FakeClient()
        val markup = FakeMarkupParser(
            parse = {
                StyledText(
                    text = "Title",
                    entities = listOf(
                        TextEntity("heading", 0, 5, url = "1"),
                        TextEntity("bold", 0, 5),
                    ),
                )
            },
        )
        val store = DialogStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = null,
            sessionStore = null,
            chatId = PeerId(5),
            mainContext = Dispatchers.Unconfined,
            markupContext = Dispatchers.Unconfined,
            markup = markup,
        ).create()
        store.accept(DialogStore.Intent.DraftChanged("x"))
        store.accept(DialogStore.Intent.Send())
        val json = client.lastSendEntities
        assertTrue(json != null && json.contains("bold"))
        assertFalse(json!!.contains("heading"))
        assertEquals(0, client.emojiConfigCalls)
        assertTrue(client.emojiFreeCalls.isEmpty())
        store.dispose()
    }

    @Test
    fun customEmojiChecksDeduplicateDocumentsAndSkipFreeChecksForPremium() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        for (premium in listOf(false, true)) {
            val client = FakeClient()
            val store = DialogStoreFactory(
                DefaultStoreFactory(), client, warmup = null, sessionStore = null,
                chatId = PeerId(5), mainContext = dispatcher, markupContext = dispatcher,
                isPremium = { premium },
                markup = FakeMarkupParser(parse = {
                    StyledText("xx", listOf(
                        TextEntity("custom_emoji", 0, 1, "42"),
                        TextEntity("custom_emoji", 1, 1, "42"),
                    ))
                }),
            ).create()
            try {
                store.accept(DialogStore.Intent.DraftChanged("x"))
                store.accept(DialogStore.Intent.Send())
                assertEquals(1, client.emojiConfigCalls)
                assertEquals(if (premium) emptyList<Long>() else listOf(42L), client.emojiFreeCalls)
                assertEquals(2, org.monogram.core.models.TextEntities.parse(client.lastSendEntities).size)
            } finally {
                store.dispose()
            }
        }
    }

    @Test
    fun markReadUsesDiscussion() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = null,
            sessionStore = null,
            chatId = PeerId(5),
            threadTopMsgId = 42,
            seedIsForum = true,
            mainContext = dispatcher,
        ).create()
        store.accept(DialogStore.Intent.Refresh)
        advanceUntilIdle()
        store.accept(DialogStore.Intent.MarkRead)
        advanceTimeBy(3_000)
        advanceUntilIdle()
        assertEquals(42 to 9, client.lastDiscussionRead)
        assertEquals(0, client.readHistoryCalls)
        store.dispose()
    }

    @Test
    fun loadMoreTopicsPagesOffsets() = runBlocking {
        val client = FakeClient(topicCount = 3)
        val store = DialogStoreFactory(
            DefaultStoreFactory(),
            client,
            warmup = null,
            sessionStore = null,
            chatId = PeerId(5),
            seedIsForum = true,
            mainContext = Dispatchers.Unconfined,
            markupContext = Dispatchers.Unconfined,
        ).create()
        assertTrue(store.state.hasMoreTopics)
        store.accept(DialogStore.Intent.LoadMoreTopics)
        assertEquals(2, client.forumCalls)
        assertTrue(client.lastForumOffsetTopic > 0)
        store.dispose()
    }

    @Test
    fun incomingMessagesAreReadWithZeroUnreadCountAndDeduplicated() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            client.events.emit(MtprotoUpdate.NewMessage(client.topicMessage(10)))
            yield()
            advanceUntilIdle()
            assertEquals(0, store.state.unreadCount)
            store.accept(DialogStore.Intent.MarkRead)
            advanceUntilIdle()
            assertEquals(listOf(10), client.readIds)
            assertEquals(10, store.state.readInboxMaxId)
            store.accept(DialogStore.Intent.MarkRead)
            advanceUntilIdle()
            repeat(3) {
                client.events.emit(MtprotoUpdate.NewMessage(client.topicMessage(11)))
                yield()
                advanceUntilIdle()
                store.accept(DialogStore.Intent.MarkRead)
                advanceUntilIdle()
            }
            assertEquals(listOf(10, 11), client.readIds)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun failedReceiptRollsBackOptimisticReadAndCanBeRetried() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient().apply { failRead = true }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            client.events.emit(MtprotoUpdate.NewMessage(client.topicMessage(10)))
            store.accept(DialogStore.Intent.MarkRead)
            advanceUntilIdle()
            assertEquals(listOf(10), client.readIds)
            assertEquals(0, store.state.readInboxMaxId)
            client.failRead = false
            store.accept(DialogStore.Intent.MarkRead)
            advanceUntilIdle()
            assertEquals(listOf(10, 10), client.readIds)
            assertEquals(10, store.state.readInboxMaxId)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun newReceiptWaitsForInFlightRequestWithoutCancellingIt() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        val client = FakeClient().apply {
            readGate = gate
            history = listOf(topicMessage(10))
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(DialogStore.Intent.MarkRead)
            client.events.emit(MtprotoUpdate.NewMessage(client.topicMessage(11)))
            advanceUntilIdle()
            store.accept(DialogStore.Intent.MarkRead)
            // The first receipt is in flight and already applied optimistically (badge cleared).
            assertEquals(listOf(10), client.readIds)
            assertEquals(10, store.state.readInboxMaxId)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(10, 11), client.readIds)
            assertEquals(11, store.state.readInboxMaxId)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun visibleReadAcknowledgesOnlyMessagesOnScreen() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient().apply {
            history = listOf(topicMessage(12), topicMessage(11), topicMessage(10))
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            // 12 is loaded but off screen (the user scrolled up): only 11 may be acknowledged.
            store.accept(DialogStore.Intent.VisibleRead(11))
            advanceUntilIdle()
            assertEquals("reads=${client.readIds} messages=${store.state.messages.map { it.id.id }}", listOf(11), client.readIds)
            assertEquals(11, store.state.readInboxMaxId)
            assertEquals(1, store.state.unreadCount)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun visibleReadAtLiveEdgeAcknowledgesTheWholePageEvenWhenClipped() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            client.events.emit(MtprotoUpdate.NewMessage(client.topicMessage(10).copy(senderId = null)))
            client.events.emit(MtprotoUpdate.NewMessage(client.topicMessage(11).copy(senderId = null)))
            client.events.emit(MtprotoUpdate.NewMessage(client.topicMessage(12).copy(senderId = null)))
            store.accept(DialogStore.Intent.VisibleRead(11, atLiveEdge = true))
            advanceUntilIdle()
            assertEquals("reads=${client.readIds} messages=${store.state.messages.map { it.id.id }}", listOf(12), client.readIds)
            assertEquals(12, store.state.readInboxMaxId)
            assertEquals(0, store.state.unreadCount)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun visibleReadAtLiveEdgeAcknowledgesNewestAndSkipsDebounce() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            client.events.emit(MtprotoUpdate.NewMessage(client.topicMessage(10)))
            client.events.emit(MtprotoUpdate.NewMessage(client.topicMessage(11)))
            yield()
            advanceUntilIdle()
            // Partial receipt first, then the user reaches the bottom before it is sent.
            store.accept(DialogStore.Intent.VisibleRead(10))
            assertTrue(client.readIds.isEmpty())
            store.accept(DialogStore.Intent.VisibleRead(11))
            advanceUntilIdle()
            assertEquals(listOf(11), client.readIds)
            assertEquals(11, store.state.readInboxMaxId)
            assertEquals(0, store.state.unreadCount)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun visibleReadDebouncesWhileScrolling() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient().apply {
            history = listOf(topicMessage(11), topicMessage(10))
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(DialogStore.Intent.VisibleRead(10))
            advanceTimeBy(200)
            assertTrue(client.readIds.isEmpty())
            // A later message extends the pending receipt instead of sending a stale one.
            store.accept(DialogStore.Intent.VisibleRead(11))
            advanceUntilIdle()
            assertEquals("reads=${client.readIds} messages=${store.state.messages.map { it.id.id }}", listOf(11), client.readIds)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun draftChangedSkipsIdenticalStateAndSendsTypingOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.DraftChanged("a"))
            store.accept(DialogStore.Intent.DraftChanged("ab"))
            assertEquals("ab", store.state.draft)
            val after = store.state
            store.accept(DialogStore.Intent.DraftChanged("ab"))
            assertTrue(store.state === after)
            assertTrue(client.typingSent.isEmpty())
            advanceTimeBy(400)
            advanceUntilIdle()
            assertEquals(listOf(true), client.typingSent)
            store.accept(DialogStore.Intent.DraftChanged(""))
            advanceUntilIdle()
            assertEquals(listOf(true, false), client.typingSent)
            assertEquals("", store.state.draft)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun replyKeyboardTextSendsAndCallbackStoresAnswer() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val keyboard = org.monogram.core.models.ReplyMarkup(
            kind = org.monogram.core.models.ReplyMarkupKind.Keyboard,
            rows = listOf(
                listOf(
                    org.monogram.core.models.ReplyButton(
                        org.monogram.core.models.ReplyButtonType.Text,
                        "Yes",
                    ),
                ),
            ),
            singleUse = true,
        )
        val client = FakeClient().apply {
            history = listOf(
                topicMessage(4).copy(replyMarkup = keyboard),
            )
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            assertEquals(
                "Yes",
                org.monogram.core.models.ReplyMarkups.latestBotKeyboard(store.state.messages)
                    ?.rows?.single()?.single()?.text,
            )
            store.accept(
                DialogStore.Intent.BotButton(
                    messageId = 0,
                    button = keyboard.rows.single().single(),
                    fromKeyboard = true,
                ),
            )
            advanceUntilIdle()
            assertEquals("Yes", client.lastSendText)
            assertEquals(
                org.monogram.core.models.ReplyMarkups.serialize(keyboard),
                store.state.keyboardDismissedKey,
            )
            store.accept(
                DialogStore.Intent.BotButton(
                    messageId = 4,
                    button = org.monogram.core.models.ReplyButton(
                        org.monogram.core.models.ReplyButtonType.Callback,
                        "Go",
                        dataHex = "6162",
                    ),
                    fromKeyboard = false,
                ),
            )
            advanceUntilIdle()
            assertEquals(4 to "6162", client.lastCallback)
            assertEquals("pong", store.state.botNotice)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun toggleChecklistCompletesItem() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val checklist = org.monogram.core.models.Checklist(
            title = "Shop",
            othersCanComplete = true,
            items = listOf(
                org.monogram.core.models.ChecklistItem(1, "Milk", done = false),
                org.monogram.core.models.ChecklistItem(2, "Eggs", done = true),
            ),
        )
        val client = FakeClient().apply {
            history = listOf(topicMessage(8).copy(mediaKind = "todo", checklist = checklist))
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(DialogStore.Intent.ToggleChecklist(8, 1))
            advanceUntilIdle()
            assertEquals(8, client.lastTodoToggle?.first)
            assertEquals(listOf(1), client.lastTodoToggle?.second)
            assertEquals(emptyList<Int>(), client.lastTodoToggle?.third)
            val updated = store.state.messages.single { it.id.id == 8 }.checklist
            requireNotNull(updated)
            assertTrue(updated.items[0].done)
            store.accept(DialogStore.Intent.ToggleChecklist(8, 2))
            advanceUntilIdle()
            assertEquals(8, client.lastTodoToggle?.first)
            assertEquals(emptyList<Int>(), client.lastTodoToggle?.second)
            assertEquals(listOf(2), client.lastTodoToggle?.third)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun attachAndEmojiPanelIntentsSwitchTabsAndInsertEmoji() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.ToggleAttachSheet)
            assertEquals(ComposerPanels.ATTACH, store.state.composerPanel)
            // Closing is idempotent: a second close must not re-open the panel behind a scrim.
            store.accept(DialogStore.Intent.CloseAttachSheet)
            assertEquals(null, store.state.composerPanel)
            store.accept(DialogStore.Intent.CloseAttachSheet)
            assertEquals(null, store.state.composerPanel)
            store.accept(DialogStore.Intent.ToggleAttachSheet)
            assertEquals(ComposerPanels.ATTACH, store.state.composerPanel)
            store.accept(DialogStore.Intent.CloseAttachSheet)
            assertEquals(null, store.state.composerPanel)
            store.accept(DialogStore.Intent.SetEmojiTab(ComposerPanels.TAB_GIFS))
            advanceUntilIdle()
            assertEquals(ComposerPanels.EMOJI, store.state.composerPanel)
            assertEquals(ComposerPanels.TAB_GIFS, store.state.emojiTab)
            assertTrue(store.state.gifPickerOpen)
            store.accept(DialogStore.Intent.SetEmojiTab(ComposerPanels.TAB_STICKERS))
            advanceUntilIdle()
            assertEquals(ComposerPanels.TAB_STICKERS, store.state.emojiTab)
            store.accept(DialogStore.Intent.InsertEmoji("😀"))
            assertTrue(store.state.draft.endsWith("😀"))
            val clip = kotlin.io.path.createTempFile(suffix = ".mp4").toFile().apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            store.accept(DialogStore.Intent.SendUpload(clip.absolutePath, "video"))
            advanceUntilIdle()
            assertEquals(clip.absolutePath, client.lastUpload?.path)
            assertEquals("video", client.lastUpload?.kind)
            assertEquals(null, store.state.composerPanel)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun savedGifsUseProcessCacheOnSecondOpen() = runTest {
        SavedGifMemory.clear()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.SetEmojiTab(ComposerPanels.TAB_GIFS))
            advanceUntilIdle()
            assertEquals(1, client.savedGifCalls)
            assertEquals(11L, store.state.savedGifs.single().documentId)
            assertEquals(false, store.state.savedGifsError)
            store.accept(DialogStore.Intent.SetEmojiTab(ComposerPanels.TAB_STICKERS))
            store.accept(DialogStore.Intent.SetEmojiTab(ComposerPanels.TAB_GIFS))
            advanceUntilIdle()
            assertEquals(1, client.savedGifCalls)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun stickerCatalogUsesProcessCacheOnSecondOpen() = runTest {
        StickerCatalogMemory.clear()
        StickerPackMemory.clear()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient()
        val first = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            first.accept(DialogStore.Intent.SetEmojiTab(ComposerPanels.TAB_STICKERS))
            advanceUntilIdle()
            assertEquals(1, client.stickerCatalogCalls)
            assertEquals(0L, client.lastStickerHash)
            assertEquals(listOf(9L), first.state.stickerSets.map { it.id })
            first.accept(DialogStore.Intent.OpenStickerPack(9L, 11L))
            advanceUntilIdle()
            assertEquals(1, client.stickerSetCalls)
            assertEquals(listOf(42L), first.state.loadedStickerPacks[9L]?.previewDocumentIds)
        } finally {
            first.dispose()
        }
        val second = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(6), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            assertEquals(listOf(9L), second.state.stickerSets.map { it.id })
            assertEquals(listOf(42L), second.state.loadedStickerPacks[9L]?.previewDocumentIds)
            second.accept(DialogStore.Intent.SetEmojiTab(ComposerPanels.TAB_STICKERS))
            second.accept(DialogStore.Intent.OpenStickerPack(9L, 11L))
            advanceUntilIdle()
            assertEquals(1, client.stickerCatalogCalls)
            assertEquals(1, client.stickerSetCalls)
        } finally {
            second.dispose()
        }
    }

    @Test
    fun openedStickerPackTracksLoadingAndLoadsEmojiOrStickers() = runTest {
        StickerCatalogMemory.clear()
        StickerPackMemory.clear()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        val client = FakeClient().apply { stickerSetGate = gate }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.OpenStickerPack(9L, 11L))
            advanceUntilIdle()
            // While the pack is in flight the menu has to show a placeholder, not an empty grid.
            assertTrue(9L in store.state.loadingStickerPackIds)
            assertTrue(store.state.failedStickerPackIds.isEmpty())

            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(9L in store.state.loadingStickerPackIds)
            assertTrue(store.state.failedStickerPackIds.isEmpty())
            assertEquals(listOf(42L), store.state.loadedStickerPacks[9L]?.previewDocumentIds)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun failedStickerPackLeavesThePlaceholderAndOffersRetry() = runTest {
        StickerCatalogMemory.clear()
        StickerPackMemory.clear()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient().apply { stickerSetError = "STICKER_SET_INVALID" }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.OpenStickerPack(9L, 11L))
            advanceUntilIdle()
            assertFalse(9L in store.state.loadingStickerPackIds)
            // A failed pack must stop shimmering forever and become retryable.
            assertTrue(9L in store.state.failedStickerPackIds)
            assertFalse(store.state.loadedStickerPacks.containsKey(9L))

            // Retrying the same pack clears the failure and loads it.
            client.stickerSetError = null
            store.accept(DialogStore.Intent.OpenStickerPack(9L, 11L))
            advanceUntilIdle()
            assertTrue(store.state.failedStickerPackIds.isEmpty())
            assertEquals(listOf(42L), store.state.loadedStickerPacks[9L]?.previewDocumentIds)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun inlineGifQueryResolvesDebouncesAndSends() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.DraftChanged("@gif cats"))
            testScheduler.runCurrent()
            assertEquals(null, client.lastInlineQuery)
            assertEquals(null, store.state.inlineQuery)
            assertEquals(false, store.state.inlineLoading)
            advanceTimeBy(300)
            advanceUntilIdle()
            assertEquals("gif", client.lastResolve)
            assertEquals("cats", client.lastInlineQuery)
            assertEquals(true, store.state.inlineResults?.gallery)
            assertEquals("gif-1", store.state.inlineResults?.results?.single()?.id)
            store.accept(DialogStore.Intent.SendInlineResult("gif-1"))
            advanceUntilIdle()
            assertEquals("gif-1", client.lastInlineSend)
            assertEquals("", store.state.draft)
            assertEquals(null, store.state.inlineQuery)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun inlinePicGallerySendsWithoutDocumentId() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = FakeClient().apply { inlineGalleryKind = "photo" }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.DraftChanged("@pic hi"))
            advanceTimeBy(300)
            advanceUntilIdle()
            assertEquals("pic", client.lastResolve)
            assertEquals("photo", store.state.inlineResults?.results?.single()?.kind)
            assertEquals("photo:88", store.state.inlineResults?.results?.single()?.thumbCacheKey)
            store.accept(DialogStore.Intent.SendInlineResult("p1"))
            advanceUntilIdle()
            assertEquals("p1", client.lastInlineSend)
            assertEquals("", store.state.draft)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun inlineResultsLoadNextPageAndDeduplicateResults() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = FakeClient().apply { inlinePaging = true }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.DraftChanged("@gif cats"))
            advanceTimeBy(300)
            advanceUntilIdle()
            assertEquals(listOf(""), client.inlineOffsets)
            assertEquals(listOf("gif-1"), store.state.inlineResults?.results?.map { it.id })
            assertEquals("more", store.state.inlineResults?.nextOffset)

            store.accept(DialogStore.Intent.LoadMoreInlineResults)
            advanceUntilIdle()
            assertEquals(listOf("", "more"), client.inlineOffsets)
            assertEquals(listOf("gif-1", "gif-2"), store.state.inlineResults?.results?.map { it.id })
            assertEquals(null, store.state.inlineResults?.nextOffset)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun inlineEmptyPageWithOffsetStopsAfterOneRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = FakeClient().apply { inlineEmptyPage = true }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.DraftChanged("@gif cats"))
            advanceTimeBy(300)
            advanceUntilIdle()
            assertEquals(listOf(""), client.inlineOffsets)
            assertEquals(true, store.state.inlineResults?.results?.isEmpty())
            assertEquals("more", store.state.inlineResults?.nextOffset)

            store.accept(DialogStore.Intent.LoadMoreInlineResults)
            advanceUntilIdle()
            assertEquals(listOf("", "more"), client.inlineOffsets)
            assertEquals(null, store.state.inlineResults?.nextOffset)

            store.accept(DialogStore.Intent.LoadMoreInlineResults)
            advanceUntilIdle()
            assertEquals(listOf("", "more"), client.inlineOffsets)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun atBotWithoutSpaceOpensInlineAfterConfirm() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.DraftChanged("@pic"))
            testScheduler.runCurrent()
            assertEquals(null, store.state.inlineQuery)
            assertEquals(null, store.state.mentionToken)
            advanceTimeBy(300)
            advanceUntilIdle()
            assertEquals("pic", client.lastResolve)
            assertEquals("", client.lastInlineQuery)
            assertEquals("pic", store.state.inlineQuery?.username)
            assertEquals(null, store.state.mentionToken)
            assertEquals(false, store.state.inlineError)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun nonBotAtQueryNeverOpensInlineOverlay() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = FakeClient().apply { resolveIsBot = false }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.DraftChanged("@user query"))
            advanceTimeBy(300)
            advanceUntilIdle()
            assertEquals("user", client.lastResolve)
            assertEquals(null, client.lastInlineQuery)
            assertEquals(null, store.state.inlineQuery)
            assertEquals(false, store.state.inlineLoading)
            assertEquals(false, store.state.inlineError)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun mentionMenuLoadsMembersAndGlobalHandle() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = FakeClient().apply { resolveIsBot = false }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            assertTrue(store.state.isGroup)
            store.accept(DialogStore.Intent.DraftChanged("@ada"))
            advanceTimeBy(300)
            advanceUntilIdle()
            assertEquals("ada", client.lastMembersQuery)
            assertEquals("ada", client.lastContactsQuery)
            assertEquals("ada", client.lastResolve)
            assertEquals(listOf(7L, 11L, 99L), store.state.mentionCandidates.map { it.peerId.value })
            assertEquals(null, store.state.inlineQuery)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun selectingUsernameMentionInsertsHandle() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = FakeClient().apply { resolveIsBot = false }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(DialogStore.Intent.DraftChanged("@ad"))
            advanceTimeBy(300)
            advanceUntilIdle()
            val ada = store.state.mentionCandidates.first { it.username == "ada" }
            store.accept(DialogStore.Intent.SelectMention(ada))
            assertEquals("@ada ", store.state.draft)
            assertTrue(store.state.draftMentions.isEmpty())
        } finally {
            store.dispose()
        }
    }

    @Test
    fun selectingNamelessMentionSendsMentionNameEntity() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = FakeClient().apply { resolveIsBot = false }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(DialogStore.Intent.DraftChanged("@No"))
            advanceTimeBy(300)
            advanceUntilIdle()
            val member = store.state.mentionCandidates.first { it.username == null }
            store.accept(DialogStore.Intent.SelectMention(member))
            assertEquals("No Handle ", store.state.draft)
            store.accept(DialogStore.Intent.Send())
            advanceUntilIdle()
            val entities = org.monogram.core.models.TextEntities.parse(client.lastSendEntities)
            assertEquals("mention_name", entities.single().kind)
            assertEquals("8", entities.single().url)
            assertEquals(0, entities.single().offset)
            assertEquals(9, entities.single().length)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun inlineQueryClearsWhenDraftLeavesAtMention() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.DraftChanged("@gif cats"))
            advanceTimeBy(300)
            advanceUntilIdle()
            assertEquals("gif-1", store.state.inlineResults?.results?.single()?.id)
            store.accept(DialogStore.Intent.DraftChanged("hello"))
            testScheduler.runCurrent()
            assertEquals(null, store.state.inlineQuery)
            assertEquals(null, store.state.inlineResults)
            assertEquals(false, store.state.inlineLoading)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun switchInlineSamePeerBuildsBotMentionDraft() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient().apply {
            profileUsername = "samplebot"
            history = listOf(topicMessage(4).copy(senderId = PeerId(99)))
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(
                DialogStore.Intent.BotButton(
                    messageId = 4,
                    button = org.monogram.core.models.ReplyButton(
                        org.monogram.core.models.ReplyButtonType.SwitchInline,
                        "Search",
                        query = "cats",
                        samePeer = true,
                    ),
                    fromKeyboard = false,
                ),
            )
            advanceUntilIdle()
            assertEquals("@samplebot cats", store.state.draft)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun failedOlderPageKeepsPaginationRetryable() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient().apply {
            history = (10..109).reversed().map { topicMessage(it) }
            historyPage = (1..9).reversed().map { topicMessage(it) }
            historyPageError = "offline"
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            assertTrue(store.state.hasOlder)
            store.accept(DialogStore.Intent.LoadOlder)
            advanceUntilIdle()
            assertTrue("a transient page failure must remain retryable", store.state.hasOlder)
            client.historyPageError = null
            store.accept(DialogStore.Intent.LoadOlder)
            advanceUntilIdle()
            assertTrue(store.state.messages.any { it.id.id == 1 })
        } finally {
            store.dispose()
        }
    }

    @Test
    fun failedNewerPageKeepsPaginationRetryable() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient().apply {
            history = (1..80).reversed().map { topicMessage(it) }
            historyPage = (81..120).reversed().map { topicMessage(it) }
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(DialogStore.Intent.JumpToDate(42))
            advanceUntilIdle()
            assertTrue(store.state.hasNewer)
            client.historyPageError = "offline"
            store.accept(DialogStore.Intent.LoadNewer)
            advanceUntilIdle()
            assertTrue("a transient page failure must remain retryable", store.state.hasNewer)
            client.historyPageError = null
            store.accept(DialogStore.Intent.LoadNewer)
            advanceUntilIdle()
            assertTrue(store.state.messages.any { it.id.id == 120 })
        } finally {
            store.dispose()
        }
    }

    @Test
    fun inWindowJumpCancelsObsoleteJumpLoading() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val obsoleteGate = CompletableDeferred<Unit>()
        val client = FakeClient().apply {
            history = (161..240).reversed().map { topicMessage(it) }
            historyPage = (1..40).reversed().map { topicMessage(it) }
            historyPageGate = obsoleteGate
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(DialogStore.Intent.JumpToMessage(999))
            advanceUntilIdle()
            assertTrue(store.state.loading)
            val visibleId = store.state.messages.last().id.id
            store.accept(DialogStore.Intent.JumpToMessage(visibleId))
            advanceUntilIdle()
            assertEquals(visibleId, store.state.anchorMessageId)
            assertFalse(store.state.loading)
            assertFalse(store.state.loadingOlder)
            assertFalse(store.state.loadingNewer)
            obsoleteGate.complete(Unit)
            advanceUntilIdle()
            assertFalse(store.state.loading)
            assertTrue(store.state.messages.all { it.id.id in 161..240 })
        } finally {
            store.dispose()
        }
    }

    @Test
    fun dateJumpInvalidatesInFlightNewerRequest() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val newerGate = CompletableDeferred<Unit>()
        val client = FakeClient().apply {
            history = (1..80).reversed().map { topicMessage(it) }
            historyPage = (401..440).reversed().map { topicMessage(it) }
            historyPageGate = newerGate
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(DialogStore.Intent.JumpToDate(42))
            advanceUntilIdle()
            store.accept(DialogStore.Intent.LoadNewer)
            advanceUntilIdle()
            assertTrue(store.state.loadingNewer)
            store.accept(DialogStore.Intent.JumpToDate(43))
            advanceUntilIdle()
            newerGate.complete(Unit)
            advanceUntilIdle()
            assertFalse(store.state.loadingNewer)
            assertEquals(401, store.state.messages.minOf { it.id.id })
        } finally {
            store.dispose()
        }
    }

    @Test
    fun dateJumpInvalidatesOlderRequestAndContinuationCursor() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val olderGate = CompletableDeferred<Unit>()
        val client = FakeClient().apply {
            history = (101..180).reversed().map { topicMessage(it) }
            historyPage = (61..100).reversed().map { topicMessage(it) }
            historyPageGate = olderGate
            jumpPage = (401..440).reversed().map { topicMessage(it) }
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(DialogStore.Intent.LoadOlder)
            advanceUntilIdle()
            store.accept(DialogStore.Intent.JumpToDate(42))
            advanceUntilIdle()
            assertEquals(401, store.state.messages.minOf { it.id.id })
            olderGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(401, store.state.messages.minOf { it.id.id })
            store.accept(DialogStore.Intent.LoadOlder)
            advanceUntilIdle()
            assertEquals(401, client.lastHistoryOffsetId)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun refreshInvalidatesInFlightPaginationOwners() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val olderGate = CompletableDeferred<Unit>()
        val client = FakeClient().apply {
            history = (101..180).reversed().map { topicMessage(it) }
            historyPage = (61..100).reversed().map { topicMessage(it) }
            historyPageGate = olderGate
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(DialogStore.Intent.LoadOlder)
            advanceUntilIdle()
            assertTrue(store.state.loadingOlder)
            store.accept(DialogStore.Intent.Refresh)
            advanceUntilIdle()
            assertFalse(store.state.loadingOlder)
            olderGate.complete(Unit)
            advanceUntilIdle()
            assertFalse(store.state.loadingOlder)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun searchInvalidatesInFlightHistoryPagination() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val olderGate = CompletableDeferred<Unit>()
        val client = FakeClient().apply {
            history = (101..180).reversed().map { topicMessage(it) }
            historyPage = (61..100).reversed().map { topicMessage(it) }
            historyPageGate = olderGate
            searchResults = listOf(topicMessage(999))
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(DialogStore.Intent.LoadOlder)
            advanceUntilIdle()
            store.accept(DialogStore.Intent.Search("needle"))
            advanceTimeBy(500)
            advanceUntilIdle()
            assertEquals(listOf(999), store.state.messages.map { it.id.id })
            olderGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(999), store.state.messages.map { it.id.id })
        } finally {
            store.dispose()
        }
    }

    @Test
    fun clearingSearchCancelsBusyState() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val searchGate = CompletableDeferred<Unit>()
        val client = FakeClient().apply {
            history = (101..180).reversed().map { topicMessage(it) }
            this.searchGate = searchGate
            searchResults = listOf(topicMessage(999))
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(DialogStore.Intent.Search("needle"))
            advanceTimeBy(500)
            advanceUntilIdle()
            assertTrue(store.state.searching)
            store.accept(DialogStore.Intent.Search(""))
            advanceUntilIdle()
            assertFalse(store.state.searching)
            searchGate.complete(Unit)
            advanceUntilIdle()
            assertFalse(store.state.searching)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun jumpLatestCancelsSearchBusyState() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val searchGate = CompletableDeferred<Unit>()
        val client = FakeClient().apply { this.searchGate = searchGate }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(DialogStore.Intent.Search("needle"))
            advanceTimeBy(500)
            advanceUntilIdle()
            assertTrue(store.state.searching)
            store.accept(DialogStore.Intent.JumpLatest)
            advanceUntilIdle()
            assertFalse(store.state.searching)
            searchGate.complete(Unit)
            advanceUntilIdle()
            assertFalse(store.state.searching)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun cachedHistoryPaintsBeforeNetwork() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val cached = Message(
            id = MessageId(PeerId(5), 4),
            senderId = PeerId(1),
            text = "cached",
            date = 1L,
            outgoing = false,
        )
        val warmup = FakeWarmup().apply { storedMessages[5L] = listOf(cached) }
        val gate = CompletableDeferred<Unit>()
        val client = FakeClient().apply {
            historyGate = gate
            history = listOf(cached.copy(text = "network"))
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = Dispatchers.Unconfined, markupContext = Dispatchers.Unconfined,
        ).create()
        try {
            assertEquals("cached", store.state.messages.single().text)
            assertTrue(store.state.fromCache)
            assertFalse(store.state.loading)
        } finally {
            gate.complete(Unit)
            store.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cachedHistoryStaysWhenNetworkFails() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val cached = Message(
            id = MessageId(PeerId(5), 4),
            senderId = PeerId(1),
            text = "cached",
            date = 1L,
            outgoing = false,
        )
        val warmup = FakeWarmup().apply { storedMessages[5L] = listOf(cached) }
        val client = FakeClient().apply { historyError = "offline" }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = Dispatchers.Unconfined, markupContext = Dispatchers.Unconfined,
        ).create()
        try {
            assertEquals("cached", store.state.messages.single().text)
            assertTrue(store.state.fromCache)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun draftSurvivesStoreRecreate() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val warmup = FakeWarmup()
        val first = DialogStoreFactory(
            DefaultStoreFactory(), FakeClient(), warmup, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = Dispatchers.Unconfined, markupContext = Dispatchers.Unconfined,
        ).create()
        try {
            first.accept(DialogStore.Intent.DraftChanged("hello"))
            assertEquals("hello", warmup.draft(PeerId(5)))
        } finally {
            first.dispose()
        }
        val second = DialogStoreFactory(
            DefaultStoreFactory(), FakeClient(), warmup, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = Dispatchers.Unconfined, markupContext = Dispatchers.Unconfined,
        ).create()
        try {
            assertEquals("hello", second.state.draft)
        } finally {
            second.dispose()
        }
    }

    @Test
    fun sendUsesComposerTextEvenIfDraftStale() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.DraftChanged("old"))
            store.accept(DialogStore.Intent.Send("hello"))
            advanceUntilIdle()
            assertEquals("hello", client.lastSendText)
            assertEquals("", store.state.draft)
            assertFalse(store.state.sending)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun overlappingTextSendsStayPendingUntilEachRpcFinishes() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        val client = FakeClient().apply { sendGate = gate }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.Send("one"))
            store.accept(DialogStore.Intent.Send("two"))
            assertEquals(listOf("one", "two"), client.sentTexts)
            assertEquals(2, store.state.messages.count { it.outgoing && it.pending })
            assertFalse(store.state.sending)
            assertEquals("", store.state.draft)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                setOf("one", "two"),
                store.state.messages.filter { it.outgoing }.map { it.text }.toSet(),
            )
            assertTrue(store.state.messages.none { it.pending })
        } finally {
            store.dispose()
        }
    }

    @Test
    fun sendAppendsPendingBeforeMarkupAndRpc() = runTest {
        val main = UnconfinedTestDispatcher(testScheduler)
        val markupDispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        val client = FakeClient().apply { sendGate = gate }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = main, markup = FakeMarkupParser(), markupContext = markupDispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.Send("hello"))
            assertTrue(store.state.messages.any { it.outgoing && it.pending && it.text == "hello" })
            assertEquals(null, client.lastSendText)
            advanceUntilIdle()
            assertEquals("hello", client.lastSendText)
            assertTrue(store.state.messages.any { it.outgoing && it.pending })
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(store.state.messages.none { it.pending })
        } finally {
            store.dispose()
        }
    }

    @Test
    fun sendClearsDraftAndResetsSending() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.DraftChanged("hello"))
            store.accept(DialogStore.Intent.Send())
            advanceUntilIdle()
            assertEquals("hello", client.lastSendText)
            assertEquals("", store.state.draft)
            assertFalse(store.state.sending)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun sendResetsSendingWhenMarkupFails() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = DialogStoreFactory(
            DefaultStoreFactory(), FakeClient(), warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
            markup = FakeMarkupParser(parse = { error("parse") }),
        ).create()
        try {
            store.accept(DialogStore.Intent.DraftChanged("hello"))
            store.accept(DialogStore.Intent.Send())
            advanceUntilIdle()
            assertFalse(store.state.sending)
            assertEquals("hello", store.state.draft)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun pendingSendIsWrittenToCache() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val warmup = FakeWarmup()
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = Dispatchers.Unconfined, markupContext = Dispatchers.Unconfined,
        ).create()
        try {
            store.accept(DialogStore.Intent.DraftChanged("ping"))
            store.accept(DialogStore.Intent.Send())
            val pending = warmup.upserted.filter { it.pending && it.text == "ping" }
            assertEquals(1, pending.size)
            assertTrue(pending.single().randomId != null && pending.single().randomId != 0L)
            assertTrue(warmup.deletedIds.contains(pending.single().id.id))
            assertTrue(warmup.upserted.any { !it.pending && it.text == "ping" })
        } finally {
            store.dispose()
        }
    }

    @Test
    fun pendingSurvivesStoreRecreateAndHistoryReplace() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val pending = Message(
            id = MessageId(PeerId(5), -7),
            senderId = null,
            text = "unsent",
            date = 2L,
            outgoing = true,
            pending = true,
            randomId = 99L,
        )
        val warmup = FakeWarmup().apply { storedMessages[5L] = listOf(pending) }
        val client = FakeClient().apply {
            history = listOf(
                Message(
                    id = MessageId(PeerId(5), 4),
                    senderId = PeerId(1),
                    text = "server",
                    date = 1L,
                    outgoing = false,
                ),
            )
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = Dispatchers.Unconfined, markupContext = Dispatchers.Unconfined,
        ).create()
        try {
            assertTrue(store.state.messages.any { it.pending && it.text == "unsent" })
            assertTrue(store.state.messages.any { it.text == "server" })
        } finally {
            store.dispose()
        }
    }

    @Test
    fun cachedOlderPageDoesNotMakeMatchingNetworkPageLookExhausted() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient().apply {
            history = (161..240).reversed().map { topicMessage(it) }
            historyPage = (81..160).reversed().map { topicMessage(it) }
        }
        val warmup = FakeWarmup().apply { olderPage = client.historyPage!! }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = warmup, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            assertEquals(225, store.state.messages.minOf { it.id.id })
            assertTrue(store.state.hasOlder)
            // The first older request merges the cached 81..160 page with the matching
            // network page and must retain a continuation cursor for the next request.
            store.accept(DialogStore.Intent.LoadOlder)
            advanceUntilIdle()
            assertEquals(81, store.state.messages.minOf { it.id.id })
            assertTrue(store.state.hasOlder)
            client.historyPage = (1..80).reversed().map { client.topicMessage(it) }
            warmup.olderPage = emptyList()
            store.accept(DialogStore.Intent.LoadOlder)
            advanceUntilIdle()
            assertEquals(1, store.state.messages.minOf { it.id.id })
        } finally {
            store.dispose()
        }
    }

    @Test
    fun jumpReplacesTheServerHistoryCursorBeforePagingOlder() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient().apply {
            history = (161..240).reversed().map { topicMessage(it) }
            historyPage = (61..100).reversed().map { topicMessage(it) }
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(DialogStore.Intent.JumpToMessage(80))
            advanceUntilIdle()
            assertEquals(61, store.state.messages.minOf { it.id.id })
            store.accept(DialogStore.Intent.LoadOlder)
            advanceUntilIdle()
            assertEquals(61, client.lastHistoryOffsetId)
            assertTrue(store.state.messages.none { it.id.id in 161..240 })
        } finally {
            store.dispose()
        }
    }

    @Test
    fun cachedIdOneDoesNotStopGapRecoveryBeforeTheServerCursor() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient().apply {
            history = (101..180).reversed().map { topicMessage(it) }
            historyPage = (61..100).reversed().map { topicMessage(it) }
        }
        val warmup = FakeWarmup().apply {
            olderPage = listOf(client.topicMessage(1))
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = warmup, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(DialogStore.Intent.LoadOlder)
            advanceUntilIdle()
            assertEquals(101, client.lastHistoryOffsetId)
            assertTrue(store.state.messages.any { it.id.id == 61 })
            assertFalse(store.state.loadingOlder)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun sparseCachedOlderRowsDoNotAdvanceTheNetworkCursor() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient().apply {
            history = (101..180).reversed().map { topicMessage(it) }
            historyPage = (61..100).reversed().map { topicMessage(it) }
        }
        val warmup = FakeWarmup().apply {
            olderPage = (50..60).reversed().map { client.topicMessage(it) }
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = warmup, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            store.accept(DialogStore.Intent.LoadOlder)
            advanceUntilIdle()
            // 50..60 is a sparse cache hit; the server must still start below its
            // previous page at 101 so it fills the uncached 61..100 interval.
            assertEquals(101, client.lastHistoryOffsetId)
            assertTrue(store.state.messages.any { it.id.id == 61 })
            assertTrue(store.state.messages.any { it.id.id == 100 })
        } finally {
            store.dispose()
        }
    }

    @Test
    fun sendUploadShowsPendingLocalBubbleThenReplaces() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient()
        val file = kotlin.io.path.createTempFile(suffix = ".jpg").toFile().apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.SendUpload(file.absolutePath, "photo"))
            advanceUntilIdle()
            assertEquals(file.absolutePath, client.lastUpload?.path)
            assertTrue(client.lastUpload?.randomId != 0L)
            assertTrue(store.state.messages.any { it.outgoing && it.mediaKind == "photo" && !it.pending })
            assertFalse(store.state.sending)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun sendUploadTimeoutKeepsPendingUntilMessageIdMaps() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient().apply { uploadError = "rpc timeout" }
        val file = kotlin.io.path.createTempFile(suffix = ".jpg").toFile().apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.SendUpload(file.absolutePath, "photo"))
            advanceUntilIdle()
            val pending = store.state.messages.single { it.pending && it.mediaKind == "photo" }
            assertEquals(localMediaCacheKey(file.absolutePath), pending.mediaCacheKey)
            assertFalse(pending.failed)
            val randomId = pending.randomId!!
            client.events.emit(MtprotoUpdate.Ignored("UpdateMessageId:$randomId:44"))
            advanceUntilIdle()
            val bound = store.state.messages.single { it.id.id == 44 }
            assertFalse(bound.pending)
            assertFalse(bound.failed)
            assertEquals(localMediaCacheKey(file.absolutePath), bound.mediaCacheKey)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun sendAlbumCreatesGroupedPendingThenReplaces() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient()
        val first = kotlin.io.path.createTempFile(suffix = ".jpg").toFile().apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val second = kotlin.io.path.createTempFile(suffix = ".jpg").toFile().apply {
            writeBytes(byteArrayOf(4, 5, 6))
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(
                DialogStore.Intent.SendAlbum(
                    listOf(
                        org.monogram.core.models.UploadItem(
                            path = first.absolutePath,
                            kind = "photo",
                            fileName = "a.jpg",
                        ),
                        org.monogram.core.models.UploadItem(
                            path = second.absolutePath,
                            kind = "photo",
                            fileName = "b.jpg",
                        ),
                    ),
                ),
            )
            advanceUntilIdle()
            assertEquals(2, client.lastAlbum?.size)
            assertEquals(2, store.state.messages.count { it.outgoing && it.mediaKind == "photo" && it.groupedId == 5L })
            assertTrue(store.state.messages.none { it.pending })
        } finally {
            store.dispose()
        }
    }

    @Test
    fun sendAlbumAcceptsMixedPhotoAndVideo() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient()
        val photo = kotlin.io.path.createTempFile(suffix = ".jpg").toFile().apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val video = kotlin.io.path.createTempFile(suffix = ".mp4").toFile().apply {
            writeBytes(byteArrayOf(4, 5, 6, 7))
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(
                DialogStore.Intent.SendAlbum(
                    listOf(
                        org.monogram.core.models.UploadItem(
                            path = photo.absolutePath,
                            kind = "photo",
                            fileName = "a.jpg",
                        ),
                        org.monogram.core.models.UploadItem(
                            path = video.absolutePath,
                            kind = "video",
                            fileName = "b.mp4",
                            duration = 3,
                            width = 1280,
                            height = 720,
                        ),
                    ),
                ),
            )
            advanceUntilIdle()
            assertEquals(listOf("photo", "video"), client.lastAlbum?.map { it.kind })
            assertEquals(2, store.state.messages.count { it.outgoing && it.groupedId == 5L })
        } finally {
            store.dispose()
        }
    }

    @Test
    fun attachPhotoSendUsesUploadedMediaAndLocalPreview() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient()
        val file = kotlin.io.path.createTempFile(suffix = ".jpg").toFile().apply {
            writeBytes(byteArrayOf(9, 8, 7, 6))
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.AttachPhoto(file.absolutePath))
            store.accept(DialogStore.Intent.DraftChanged("hi"))
            store.accept(DialogStore.Intent.Send())
            advanceUntilIdle()
            assertEquals(file.absolutePath, client.lastUpload?.path)
            assertEquals("photo", client.lastUpload?.kind)
            assertEquals("hi", client.lastUpload?.caption)
            assertTrue(client.lastUpload?.randomId != 0L)
            assertTrue(store.state.messages.any { it.outgoing && it.mediaKind == "photo" && !it.pending })
        } finally {
            store.dispose()
        }
    }

    @Test
    fun jumpMentionLoadsNextAndReadsWhenCaughtUp() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient()
        client.unreadMentions = listOf(client.topicMessage(44))
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            client.events.emit(
                MtprotoUpdate.ChatsChanged(
                    listOf(Chat(id = PeerId(5), title = "Group", unreadMentionsCount = 1)),
                ),
            )
            advanceUntilIdle()
            assertEquals(1, store.state.unreadMentionsCount)
            store.accept(DialogStore.Intent.JumpMention)
            advanceUntilIdle()
            assertEquals(0, client.lastMentionAddOffset)
            assertEquals(44, store.state.anchorMessageId)
            assertEquals(1, store.state.unreadMentionsCount)
            assertEquals(0, client.readMentionsCalls)
            store.accept(DialogStore.Intent.VisibleWindow(setOf(44)))
            advanceUntilIdle()
            assertEquals(0, store.state.unreadMentionsCount)
            assertEquals(0, client.readMentionsCalls)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun jumpMentionEmptyPathReconcilesWithoutReadingUnseenMessages() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient()
        client.unreadMentions = emptyList()
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            client.events.emit(
                MtprotoUpdate.ChatsChanged(
                    listOf(Chat(id = PeerId(5), title = "Group", unreadMentionsCount = 2)),
                ),
            )
            advanceUntilIdle()
            store.accept(DialogStore.Intent.JumpMention)
            advanceUntilIdle()
            assertEquals(0, client.lastMentionAddOffset)
            assertEquals(0, client.readMentionsCalls)
            assertEquals(0, store.state.unreadMentionsCount)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun jumpUnreadReactionLoadsNextAndReadsWhenCaughtUp() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient()
        client.unreadReactions = listOf(client.topicMessage(21))
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            client.events.emit(
                MtprotoUpdate.ChatsChanged(
                    listOf(Chat(id = PeerId(5), title = "Group", unreadReactionsCount = 1)),
                ),
            )
            advanceUntilIdle()
            assertEquals(1, store.state.unreadReactionsCount)
            store.accept(DialogStore.Intent.JumpUnreadReaction)
            advanceUntilIdle()
            assertEquals(21, store.state.anchorMessageId)
            assertEquals(1, store.state.unreadReactionsCount)
            assertEquals(0, client.readReactionsCalls)
            store.accept(DialogStore.Intent.VisibleWindow(setOf(21)))
            advanceUntilIdle()
            assertEquals(0, store.state.unreadReactionsCount)
            assertEquals(0, client.readReactionsCalls)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun jumpMentionErrorDoesNotMarkRead() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient()
        client.unreadMentionsError = "offline"
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            client.events.emit(
                MtprotoUpdate.ChatsChanged(
                    listOf(Chat(id = PeerId(5), title = "Group", unreadMentionsCount = 3)),
                ),
            )
            advanceUntilIdle()
            store.accept(DialogStore.Intent.JumpMention)
            advanceUntilIdle()
            assertEquals(0, client.readMentionsCalls)
            assertEquals(3, store.state.unreadMentionsCount)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun seeingUnreadMentionMarksItRead() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient()
        client.unreadMentions = listOf(client.topicMessage(44))
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            client.events.emit(
                MtprotoUpdate.ChatsChanged(
                    listOf(Chat(id = PeerId(5), title = "Group", unreadMentionsCount = 1)),
                ),
            )
            advanceUntilIdle()
            store.accept(DialogStore.Intent.VisibleWindow(setOf(44)))
            advanceUntilIdle()
            assertEquals(0, store.state.unreadMentionsCount)
            assertEquals(0, client.readMentionsCalls)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun jumpUnreadReactionErrorDoesNotMarkRead() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient()
        client.unreadReactionsError = "offline"
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            client.events.emit(
                MtprotoUpdate.ChatsChanged(
                    listOf(Chat(id = PeerId(5), title = "Group", unreadReactionsCount = 4)),
                ),
            )
            advanceUntilIdle()
            store.accept(DialogStore.Intent.JumpUnreadReaction)
            advanceUntilIdle()
            assertEquals(0, client.readReactionsCalls)
            assertEquals(4, store.state.unreadReactionsCount)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun readingOneOfManyMentionsKeepsOthersAndJumpAdvances() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient().apply {
            unreadMentions = (1..150).map { topicMessage(it) }
            unreadReactions = listOf(topicMessage(1), topicMessage(150))
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            client.events.emit(MtprotoUpdate.ChatsChanged(listOf(
                Chat(id = PeerId(5), title = "Group", unreadMentionsCount = 150, unreadReactionsCount = 2),
            )))
            store.accept(DialogStore.Intent.JumpMention)
            advanceUntilIdle()
            assertEquals(1, store.state.anchorMessageId)
            store.accept(DialogStore.Intent.VisibleWindow(setOf(1)))
            advanceUntilIdle()
            assertEquals(listOf(listOf(1)), client.readContentIds)
            assertEquals(149, store.state.unreadMentionsCount)
            assertEquals(1, store.state.unreadReactionsCount)
            assertEquals(0, client.readMentionsCalls)
            assertEquals(0, client.readReactionsCalls)
            store.accept(DialogStore.Intent.JumpMention)
            advanceUntilIdle()
            assertEquals(2, store.state.anchorMessageId)
            // A normal scroll to the newest message must work independently of the jump page.
            store.accept(DialogStore.Intent.VisibleWindow(setOf(150)))
            advanceUntilIdle()
            assertEquals(148, store.state.unreadMentionsCount)
            assertEquals(0, store.state.unreadReactionsCount)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun failedContentReadKeepsBadgesAndCanBeRetried() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient().apply {
            unreadMentions = listOf(topicMessage(44))
            failReadContents = true
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            client.events.emit(MtprotoUpdate.UnreadMentions(PeerId(5), 1))
            store.accept(DialogStore.Intent.VisibleWindow(setOf(44)))
            advanceUntilIdle()
            assertEquals(1, store.state.unreadMentionsCount)
            client.failReadContents = false
            store.accept(DialogStore.Intent.VisibleWindow(setOf(44)))
            advanceUntilIdle()
            assertEquals(0, store.state.unreadMentionsCount)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun overlappingVisibleWindowsReadEachMessageOnce() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val client = FakeClient().apply {
            unreadMentions = listOf(topicMessage(44), topicMessage(45))
            readContentsGate = gate
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            client.events.emit(MtprotoUpdate.UnreadMentions(PeerId(5), 2))
            store.accept(DialogStore.Intent.VisibleWindow(setOf(44)))
            store.accept(DialogStore.Intent.VisibleWindow(setOf(44, 45)))
            assertEquals(listOf(listOf(44)), client.readContentIds)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(listOf(44), listOf(45)), client.readContentIds)
            assertEquals(0, store.state.unreadMentionsCount)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun counterUpdateConsumesReactionAlreadyOnScreen() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient()
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            store.accept(DialogStore.Intent.VisibleWindow(setOf(44)))
            advanceUntilIdle()
            client.unreadReactions = listOf(client.topicMessage(44))
            client.events.emit(MtprotoUpdate.UnreadReactionsDelta(PeerId(5), 1))
            advanceUntilIdle()
            assertEquals(listOf(listOf(44)), client.readContentIds)
            assertEquals(0, store.state.unreadReactionsCount)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun topicUsesItsOwnCountersAndRefreshesAfterRemoteRead() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeClient().apply {
            topicUnreadMentions = 2
            topicUnreadReactions = 3
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), threadTopMsgId = 42, seedIsForum = true,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            assertEquals(2, store.state.unreadMentionsCount)
            assertEquals(3, store.state.unreadReactionsCount)
            client.topicUnreadMentions = 0
            client.topicUnreadReactions = 1
            client.events.emit(MtprotoUpdate.ChatsChanged(listOf(
                Chat(id = PeerId(5), title = "Group", unreadMentionsCount = 20, unreadReactionsCount = 30),
            )))
            advanceUntilIdle()
            assertEquals(0, store.state.unreadMentionsCount)
            assertEquals(1, store.state.unreadReactionsCount)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun openingUnreadDialogAnchorsAtFirstUnreadWithKnownReadBoundary() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        listOf(1, 20, 80).forEach { unread ->
            val chatId = PeerId(5)
            val warmup = FakeWarmup().apply {
                storedChats[chatId.value] = Chat(
                    id = chatId,
                    title = "Dialog",
                    lastMessageId = 100,
                    unreadCount = unread,
                    readInboxMaxId = 100 - unread,
                )
            }
            val client = FakeClient().apply { configureUnreadHistory() }
            val store = DialogStoreFactory(
                DefaultStoreFactory(), client, warmup, sessionStore = null,
                chatId = chatId, seedIsForum = false,
                mainContext = dispatcher, markupContext = dispatcher,
            ).create()
            try {
                advanceUntilIdle()
                assertEquals(101 - unread, store.state.anchorMessageId)
                assertTrue(store.state.anchorAtTop)
                assertEquals(if (unread <= 20) 1 else 3, client.historyCalls)
            } finally {
                store.dispose()
            }
        }
    }

    @Test
    fun openingUnreadDialogAnchorsAtFirstUnreadWithoutReadBoundary() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        listOf(1, 20, 80).forEach { unread ->
            val chatId = PeerId(5)
            val warmup = FakeWarmup().apply {
                storedChats[chatId.value] = Chat(
                    id = chatId,
                    title = "Dialog",
                    lastMessageId = 100,
                    unreadCount = unread,
                )
            }
            val client = FakeClient().apply { configureUnreadHistory() }
            val store = DialogStoreFactory(
                DefaultStoreFactory(), client, warmup, sessionStore = null,
                chatId = chatId, seedIsForum = false,
                mainContext = dispatcher, markupContext = dispatcher,
            ).create()
            try {
                advanceUntilIdle()
                assertEquals(101 - unread, store.state.anchorMessageId)
                assertTrue(store.state.anchorAtTop)
                assertEquals(if (unread <= 20) 1 else 3, client.historyCalls)
            } finally {
                store.dispose()
            }
        }
    }

    @Test
    fun explicitInitialJumpOutranksUnreadAnchor() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val chatId = PeerId(5)
        val warmup = FakeWarmup().apply {
            storedChats[chatId.value] = Chat(
                id = chatId,
                title = "Dialog",
                lastMessageId = 100,
                unreadCount = 20,
            )
        }
        val client = FakeClient().apply { configureUnreadHistory() }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup, sessionStore = null,
            chatId = chatId, jumpToMessageId = 90, seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            assertEquals(90, store.state.anchorMessageId)
            assertFalse(store.state.anchorAtTop)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun openingReadDialogKeepsSavedScrollAnchor() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val chatId = PeerId(5)
        val warmup = FakeWarmup().apply {
            storedChats[chatId.value] = Chat(
                id = chatId,
                title = "Dialog",
                lastMessageId = 100,
                dialogScrollMessageId = 90,
                unreadCount = 0,
                readInboxMaxId = 100,
            )
        }
        val client = FakeClient().apply { configureUnreadHistory() }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup, sessionStore = null,
            chatId = chatId, seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            assertEquals(90, store.state.anchorMessageId)
            assertFalse(store.state.anchorAtTop)
            assertEquals(1, client.historyCalls)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun unreadAlbumCanBeAcknowledgedWhenItsHeadIsVisible() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val chatId = PeerId(5)
        val warmup = FakeWarmup().apply {
            storedChats[chatId.value] = Chat(
                id = chatId, title = "Channel", lastMessageId = 100,
                unreadCount = 2, readInboxMaxId = 98,
            )
        }
        val client = FakeClient().apply {
            history = listOf(
                topicMessage(100).copy(groupedId = 7),
                topicMessage(99).copy(groupedId = 7),
                topicMessage(98),
            )
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup, sessionStore = null,
            chatId = chatId, seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            advanceUntilIdle()
            assertEquals(99, store.state.anchorMessageId)
            assertTrue(store.state.anchorAtTop)
            store.accept(DialogStore.Intent.VisibleRead(100, atLiveEdge = true))
            advanceUntilIdle()
            assertEquals(100, store.state.readInboxMaxId)
            assertEquals(0, store.state.unreadCount)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun serverCountersDuringContentReceiptAreNotDecrementedTwice() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        val client = FakeClient().apply {
            unreadMentions = listOf(topicMessage(44), topicMessage(45))
            readContentsGate = gate
        }
        val store = DialogStoreFactory(
            DefaultStoreFactory(), client, warmup = null, sessionStore = null,
            chatId = PeerId(5), seedIsForum = false,
            mainContext = dispatcher, markupContext = dispatcher,
        ).create()
        try {
            client.events.emit(MtprotoUpdate.UnreadMentions(PeerId(5), 2))
            store.accept(DialogStore.Intent.VisibleWindow(setOf(44)))
            client.events.emit(MtprotoUpdate.UnreadMentions(PeerId(5), 1))
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, store.state.unreadMentionsCount)
            assertEquals(listOf(listOf(44)), client.readContentIds)
        } finally {
            store.dispose()
        }
    }

    private class FakeMeta : SessionMetadataStore() {
        val values = mutableMapOf<String, String>()
        override suspend fun readMeta(key: String): String? = values[key]
        override suspend fun writeMeta(key: String, value: String) {
            values[key] = value
        }
    }

    private class FakeWarmup : OfflineWarmup() {
        var olderPage: List<Message> = emptyList()
        override suspend fun olderMessages(chatId: PeerId, beforeId: Int, limit: Int) =
            olderPage.filter { it.id.id < beforeId }.take(limit)
        val storedMessages = mutableMapOf<Long, List<Message>>()
        val storedChats = mutableMapOf<Long, Chat>()
        val upsertedChats = mutableListOf<Chat>()
        override suspend fun chats(): List<Chat> = storedChats.values.toList()
        override suspend fun upsertChats(chats: List<Chat>) {
            upsertedChats += chats
            chats.forEach { storedChats[it.id.value] = it }
        }
        val drafts = mutableMapOf<String, String>()
        val upserted = mutableListOf<Message>()
        val deletedIds = mutableListOf<Int>()
        override suspend fun messages(chatId: PeerId, limit: Int) =
            storedMessages[chatId.value].orEmpty()
        override suspend fun messagesByIds(chatId: PeerId, ids: List<Int>): List<Message> {
            val byId = storedMessages[chatId.value].orEmpty().associateBy { it.id.id }
            return ids.mapNotNull { byId[it] }
        }
        override suspend fun draft(chatId: PeerId, threadId: Int) =
            drafts[draftMetaKey(chatId.value, threadId)].orEmpty()
        override suspend fun setDraft(chatId: PeerId, threadId: Int, text: String) {
            val key = draftMetaKey(chatId.value, threadId)
            if (text.isEmpty()) drafts.remove(key) else drafts[key] = text
        }
        override suspend fun upsertMessages(messages: List<Message>) {
            upserted += messages
            messages.forEach { message ->
                val rows = storedMessages[message.id.chatId.value].orEmpty()
                    .filter { it.id.id != message.id.id }
                storedMessages[message.id.chatId.value] = rows + message
            }
        }
        override suspend fun deleteMessage(chatId: PeerId, messageId: Int) {
            deletedIds += messageId
            storedMessages[chatId.value] =
                storedMessages[chatId.value].orEmpty().filter { it.id.id != messageId }
        }
    }

    private class FakeClient(
        private val topicCount: Int = 1,
    ) : MtprotoClient {
        var forumCalls = 0
        var emojiConfigCalls = 0
        val emojiFreeCalls = mutableListOf<Long>()
        override suspend fun animatedEmojiMax(): Outcome<Int> {
            emojiConfigCalls++
            return Outcome.Ok(10)
        }
        override suspend fun customEmojiIsFree(documentId: Long): Outcome<Boolean> {
            emojiFreeCalls += documentId
            return Outcome.Ok(true)
        }
        var forumError: String? = null
        var historyCalls = 0
        var repliesCalls = 0
        var readHistoryCalls = 0
        var unreadMentions: List<Message> = emptyList()
        var unreadReactions: List<Message> = emptyList()
        var lastMentionAddOffset = 0
        var lastReactionAddOffset = 0
        val readContentIds = mutableListOf<List<Int>>()
        var failReadContents = false
        var readContentsGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var topicUnreadMentions = 0
        var topicUnreadReactions = 0
        var readMentionsCalls = 0
        var readReactionsCalls = 0
        var unreadMentionsError: String? = null
        var unreadReactionsError: String? = null
        val events = MutableSharedFlow<MtprotoUpdate>(replay = 64, extraBufferCapacity = 64)
        val readIds = mutableListOf<Int>()
        val typingSent = mutableListOf<Boolean>()
        var failRead = false
        var readGate: CompletableDeferred<Unit>? = null
        var lastRepliesTop = 0
        var lastSendReply = 0
        var lastSendTop = 0
        var lastSendEntities: String? = null
        var lastSendText: String? = null
        val sentTexts = mutableListOf<String>()
        var nextSendId = 200
        var sendGate: CompletableDeferred<Unit>? = null
        var lastCallback: Pair<Int, String>? = null
        var lastTodoToggle: Triple<Int, List<Int>, List<Int>>? = null
        var history: List<Message> = emptyList()
        var lastUpload: org.monogram.core.models.UploadItem? = null
        var lastAlbum: List<org.monogram.core.models.UploadItem>? = null
        var uploadGate: CompletableDeferred<Unit>? = null
        var uploadError: String? = null
        var lastResolve: String? = null
        var resolveIsBot: Boolean = true
        var lastMembersQuery: String? = null
        var lastContactsQuery: String? = null
        var members: List<ProfileMember> = listOf(
            ProfileMember(id = PeerId(7), title = "Ada", username = "ada"),
            ProfileMember(id = PeerId(8), title = "No Handle", username = null),
        )
        var contactsPeople: List<SearchPeer> = listOf(
            SearchPeer(
                id = PeerId(11),
                title = "Ada Global",
                username = "ada_global",
                kind = "user",
            ),
        )
        var lastInlineQuery: String? = null
        var lastInlineSend: String? = null
        val inlineOffsets = mutableListOf<String>()
        var inlinePaging = false
        var inlineEmptyPage = false
        var inlineGalleryKind: String = "gif"
        var savedGifCalls = 0
        var savedGifs: List<org.monogram.core.models.SavedGif> = listOf(
            org.monogram.core.models.SavedGif(documentId = 11L, cacheKey = "gif:11"),
        )
        var stickerCatalogCalls = 0
        var stickerSetCalls = 0
        var stickerSetError: String? = null
        var stickerSetGate: CompletableDeferred<Unit>? = null
        var lastStickerHash = -1L
        var stickerSets = listOf(
            StickerPack(
                id = 9L,
                title = "Cats",
                shortName = "cats",
                count = 1,
                isEmoji = false,
                previewDocumentIds = emptyList(),
                accessHash = 11L,
            ),
        )
        var loadedPack = StickerPack(
            id = 9L,
            title = "Cats",
            shortName = "cats",
            count = 1,
            isEmoji = false,
            previewDocumentIds = listOf(42L),
            accessHash = 11L,
        )
        var profileUsername: String? = null
        var lastDiscussionRead: Pair<Int, Int>? = null
        var lastForumOffsetTopic = 0
        var topicsById: List<Int> = emptyList()
        var topicsByIdCalls = 0
        var topicsByIdError: String? = null

        fun topicMessage(id: Int) = Message(
            id = MessageId(PeerId(5), id),
            senderId = PeerId(1),
            text = "msg$id",
            date = 1L,
            outgoing = false,
        )

        override suspend fun connect(): Outcome<Unit> = Outcome.Ok(Unit)
        override suspend fun sendAuthCode(phone: String) = unused<AuthState.AwaitingCode>()
        override suspend fun signIn(phone: String, phoneCodeHash: String, code: String) =
            unused<AuthState>()
        override suspend fun checkPassword(password: String) = unused<AuthState.Authorized>()
        override suspend fun getChats(): Outcome<List<Chat>> = Outcome.Ok(emptyList())
        override suspend fun loadMoreChats(offsetDate: Int, offsetId: Int, offsetPeerId: Long) =
            Outcome.Ok(emptyList<Chat>())
        override suspend fun getFolders(): Outcome<List<Folder>> = Outcome.Ok(emptyList())
        var historyError: String? = null
        var historyPage: List<Message>? = null
        var historyPagesByOffset: Map<Int, List<Message>> = emptyMap()
        var historyPageError: String? = null
        var historyPageGate: CompletableDeferred<Unit>? = null
        var searchGate: CompletableDeferred<Unit>? = null
        var searchResults: List<Message> = emptyList()
        var jumpPage: List<Message>? = null
        var jumpPageGate: CompletableDeferred<Unit>? = null
        var lastHistoryOffsetId = 0
        var lastHistoryOffsetDate = 0
        var historyGate: CompletableDeferred<Unit>? = null
        override suspend fun getHistory(chatId: PeerId, limit: Int): Outcome<List<Message>> {
            historyCalls++
            historyGate?.await()
            historyError?.let { return Outcome.Err(it) }
            return Outcome.Ok(history)
        }
        override suspend fun getHistoryPage(
            chatId: PeerId,
            limit: Int,
            offsetId: Int,
            offsetDate: Int,
            addOffset: Int,
        ): Outcome<List<Message>> {
            historyCalls++
            lastHistoryOffsetId = offsetId
            lastHistoryOffsetDate = offsetDate
            if (offsetDate != 0) {
                jumpPageGate?.await()
                return Outcome.Ok(jumpPage ?: historyPage ?: history)
            }
            if (offsetId != 0) historyPageGate?.await()
            historyPageError?.let { return Outcome.Err(it) }
            historyError?.let { return Outcome.Err(it) }
            return Outcome.Ok(historyPagesByOffset[offsetId] ?: historyPage ?: history)
        }

        fun configureUnreadHistory() {
            history = (69..100).reversed().map(::topicMessage)
            historyPagesByOffset = mapOf(
                69 to (29..68).reversed().map(::topicMessage),
                29 to (1..28).reversed().map(::topicMessage),
            )
        }
        override suspend fun searchMessages(chatId: PeerId, query: String, limit: Int): Outcome<List<Message>> {
            searchGate?.await()
            return Outcome.Ok(searchResults)
        }
        var pinned: List<Message> = emptyList()
        var pinnedCalls = 0
        override suspend fun getPinnedMessages(chatId: PeerId, limit: Int): Outcome<List<Message>> {
            pinnedCalls++
            return Outcome.Ok(pinned)
        }
        override suspend fun sendText(
            chatId: PeerId,
            text: String,
            replyToMsgId: Int,
            entitiesJson: String?,
            topMsgId: Int,
            webpageUrl: String?,
        ): Outcome<Message> {
            lastSendReply = replyToMsgId
            lastSendTop = topMsgId
            lastSendEntities = entitiesJson
            lastSendText = text
            sentTexts += text
            sendGate?.await()
            return Outcome.Ok(topicMessage(nextSendId++).copy(text = text, outgoing = true))
        }
        override suspend fun getProfileMembers(
            peerId: PeerId,
            filter: String,
            query: String,
            offset: Int,
            limit: Int,
        ): Outcome<ProfileMemberPage> {
            lastMembersQuery = query
            return Outcome.Ok(ProfileMemberPage(count = members.size, members = members))
        }
        override suspend fun contactsSearch(
            query: String,
            limit: Int,
        ): Outcome<ContactsSearch> {
            lastContactsQuery = query
            val people = contactsPeople.filter { peer ->
                peer.title.contains(query, ignoreCase = true) ||
                    peer.username.orEmpty().contains(query, ignoreCase = true)
            }
            return Outcome.Ok(ContactsSearch(people = people))
        }
        override suspend fun resolveUsername(
            username: String,
        ): Outcome<org.monogram.core.models.ResolvedPeer> {
            lastResolve = username
            return Outcome.Ok(
                org.monogram.core.models.ResolvedPeer(
                    peerId = PeerId(99),
                    username = username,
                    title = username,
                    isBot = resolveIsBot,
                ),
            )
        }
        override suspend fun getInlineBotResults(
            chatId: PeerId,
            botId: PeerId,
            query: String,
            offset: String,
        ): Outcome<org.monogram.core.models.InlineBotResults> {
            lastInlineQuery = query
            inlineOffsets += offset
            if (inlineEmptyPage) {
                return Outcome.Ok(
                    org.monogram.core.models.InlineBotResults(
                        queryId = 7L,
                        gallery = true,
                        nextOffset = "more",
                        cacheTime = 30,
                        results = emptyList(),
                    ),
                )
            }
            if (inlinePaging && offset == "more") {
                return Outcome.Ok(
                    org.monogram.core.models.InlineBotResults(
                        queryId = 7L,
                        gallery = true,
                        cacheTime = 30,
                        results = listOf(
                            org.monogram.core.models.InlineBotResult(id = "gif-1", kind = "gif"),
                            org.monogram.core.models.InlineBotResult(id = "gif-2", kind = "gif"),
                        ),
                    ),
                )
            }
            val result = if (inlineGalleryKind == "photo") {
                org.monogram.core.models.InlineBotResult(
                    id = "p1",
                    kind = "photo",
                    thumbCacheKey = "photo:88",
                )
            } else {
                org.monogram.core.models.InlineBotResult(id = "gif-1", kind = "gif")
            }
            return Outcome.Ok(
                org.monogram.core.models.InlineBotResults(
                    queryId = 7L,
                    gallery = true,
                    nextOffset = if (inlinePaging) "more" else null,
                    cacheTime = 30,
                    results = listOf(result),
                ),
            )
        }
        override suspend fun sendInlineBotResult(
            chatId: PeerId,
            queryId: Long,
            resultId: String,
            replyToMsgId: Int,
            topMsgId: Int,
        ): Outcome<Message> {
            lastInlineSend = resultId
            return Outcome.Ok(topicMessage(70).copy(outgoing = true, mediaKind = "gif"))
        }
        override suspend fun sendUploadedMedia(
            chatId: PeerId,
            item: org.monogram.core.models.UploadItem,
            replyToMsgId: Int,
            topMsgId: Int,
            entitiesJson: String?,
        ): Outcome<Message> {
            lastUpload = item
            uploadGate?.await()
            uploadError?.let { return Outcome.Err(it) }
            return Outcome.Ok(
                topicMessage(80).copy(
                    outgoing = true,
                    mediaKind = item.kind,
                    text = item.caption.ifBlank { null },
                ),
            )
        }
        override suspend fun sendUploadedAlbum(
            chatId: PeerId,
            items: List<org.monogram.core.models.UploadItem>,
            replyToMsgId: Int,
            topMsgId: Int,
        ): Outcome<List<Message>> {
            lastAlbum = items
            uploadGate?.await()
            uploadError?.let { return Outcome.Err(it) }
            return Outcome.Ok(
                items.mapIndexed { index, item ->
                    topicMessage(90 + index).copy(
                        outgoing = true,
                        mediaKind = item.kind,
                        groupedId = 5L,
                        text = item.caption.ifBlank { null },
                    )
                },
            )
        }
        override suspend fun getBotCallbackAnswer(
            chatId: PeerId,
            messageId: Int,
            dataHex: String,
        ): Outcome<org.monogram.core.models.BotCallbackAnswer> {
            lastCallback = messageId to dataHex
            return Outcome.Ok(org.monogram.core.models.BotCallbackAnswer(message = "pong"))
        }
        override suspend fun toggleTodoCompleted(
            chatId: PeerId,
            messageId: Int,
            completed: List<Int>,
            incompleted: List<Int>,
        ): Outcome<Unit> {
            lastTodoToggle = Triple(messageId, completed, incompleted)
            return Outcome.Ok(Unit)
        }
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
        override suspend fun deleteMessage(chatId: PeerId, messageId: Int, revoke: Boolean) =
            Outcome.Ok(Unit)
        override suspend fun forwardMessage(
            fromChatId: PeerId,
            messageId: Int,
            toChatId: PeerId,
        ) = Outcome.Ok(emptyList<Message>())
        override suspend fun getUnreadMentions(
            chatId: PeerId,
            offsetId: Int,
            addOffset: Int,
            limit: Int,
            topMsgId: Int,
        ): Outcome<List<Message>> {
            lastMentionAddOffset = addOffset
            unreadMentionsError?.let { return Outcome.Err(it) }
            return Outcome.Ok(unreadMentions.filter { offsetId == 0 || it.id.id < offsetId }.sortedByDescending { it.id.id }.drop(addOffset).take(limit))
        }
        override suspend fun readMessageContents(chatId: PeerId, messageIds: List<Int>): Outcome<Unit> {
            readContentIds += messageIds
            readContentsGate?.await()
            if (failReadContents) return Outcome.Err("offline")
            unreadMentions = unreadMentions.filterNot { it.id.id in messageIds }
            unreadReactions = unreadReactions.filterNot { it.id.id in messageIds }
            return Outcome.Ok(Unit)
        }
        override suspend fun readMentions(chatId: PeerId, topMsgId: Int): Outcome<Unit> {
            readMentionsCalls++
            return Outcome.Ok(Unit)
        }
        override suspend fun getUnreadReactions(
            chatId: PeerId,
            offsetId: Int,
            addOffset: Int,
            limit: Int,
            topMsgId: Int,
        ): Outcome<List<Message>> {
            lastReactionAddOffset = addOffset
            unreadReactionsError?.let { return Outcome.Err(it) }
            return Outcome.Ok(unreadReactions.filter { offsetId == 0 || it.id.id < offsetId }.sortedByDescending { it.id.id }.drop(addOffset).take(limit))
        }
        override suspend fun readReactions(chatId: PeerId, topMsgId: Int): Outcome<Unit> {
            readReactionsCalls++
            return Outcome.Ok(Unit)
        }
        override suspend fun readHistory(chatId: PeerId, maxId: Int): Outcome<Unit> {
            readHistoryCalls++
            readIds += maxId
            readGate?.await()
            if (failRead) return Outcome.Err("offline")
            return Outcome.Ok(Unit)
        }
        override suspend fun readDiscussion(
            chatId: PeerId,
            msgId: Int,
            readMaxId: Int,
        ): Outcome<Unit> {
            lastDiscussionRead = msgId to readMaxId
            return Outcome.Ok(Unit)
        }
        override suspend fun setTyping(chatId: PeerId, typing: Boolean): Outcome<Unit> {
            typingSent += typing
            return Outcome.Ok(Unit)
        }
        override suspend fun getSavedGifs(): Outcome<List<org.monogram.core.models.SavedGif>> {
            savedGifCalls++
            return Outcome.Ok(savedGifs)
        }
        override suspend fun getAllStickers(hash: Long): Outcome<StickerCatalog> {
            stickerCatalogCalls++
            lastStickerHash = hash
            return Outcome.Ok(StickerCatalog(hash = 99L, notModified = false, sets = stickerSets))
        }
        override suspend fun getStickerSet(setId: Long, accessHash: Long): Outcome<StickerPack> {
            stickerSetCalls++
            stickerSetGate?.await()
            stickerSetError?.let { return Outcome.Err(it) }
            return Outcome.Ok(loadedPack)
        }
        override suspend fun getProfile(peerId: PeerId): Outcome<Profile> = Outcome.Ok(
            Profile(
                id = peerId,
                kind = if (profileUsername == null) "group" else "bot",
                title = profileUsername ?: "Group Name",
                username = profileUsername,
                isBot = profileUsername != null,
            ),
        )
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
        override suspend fun getReplies(
            chatId: PeerId,
            msgId: Int,
            limit: Int,
            offsetId: Int,
            addOffset: Int,
        ): Outcome<List<Message>> {
            repliesCalls++
            lastRepliesTop = msgId
            return Outcome.Ok(listOf(topicMessage(9).copy(replyToMsgId = 42, replyToTopId = 42)))
        }
        override suspend fun getForumTopics(
            chatId: PeerId,
            offsetDate: Int,
            offsetId: Int,
            offsetTopic: Int,
            limit: Int,
        ): Outcome<ForumTopicsPage> {
            forumCalls++
            forumError?.let { return Outcome.Err(it) }
            lastForumOffsetTopic = offsetTopic
            val topics = if (offsetTopic == 0) {
                listOf(
                    ForumTopic(id = 1, title = "General", unreadCount = 3, date = 10, topMessageId = 8),
                )
            } else {
                listOf(ForumTopic(id = 8, title = "Bugs", date = 9, topMessageId = 7))
            }
            return Outcome.Ok(ForumTopicsPage(count = topicCount, topics = topics))
        }
        var hideError: String? = "denied"
        var hideCalls = 0
        var lastHide: Pair<Int, Boolean>? = null
        override suspend fun editForumTopicHidden(
            chatId: PeerId,
            topicId: Int,
            hidden: Boolean,
        ): Outcome<Unit> {
            hideCalls++
            lastHide = topicId to hidden
            hideError?.let { return Outcome.Err(it) }
            return Outcome.Ok(Unit)
        }
        override suspend fun getForumTopicsById(
            chatId: PeerId,
            topicIds: List<Int>,
        ): Outcome<ForumTopicsPage> {
            topicsByIdCalls++
            topicsById = topicIds
            topicsByIdError?.let { return Outcome.Err(it) }
            return Outcome.Ok(
                ForumTopicsPage(
                    count = 1,
                    topics = listOf(
                        ForumTopic(
                            id = 42,
                            title = "Bugs",
                            iconColor = 0x6FB9F0,
                            unreadCount = 2,
                            unreadMentionsCount = topicUnreadMentions,
                            unreadReactionsCount = topicUnreadReactions,
                            readInboxMaxId = 8,
                        ),
                    ),
                ),
            )
        }
        override suspend fun getUpdatesState(): Outcome<UpdatesCursor> =
            Outcome.Ok(UpdatesCursor(0, 0, 0, 0))
        override fun updates(): Flow<MtprotoUpdate> = events
        override fun sessionLost(): Flow<Unit> = emptyFlow()
        override fun libraryVersion(): String = "test"
        override suspend fun logout(): Outcome<Unit> = Outcome.Ok(Unit)
        override fun close() = Unit

        private fun <T> unused(): Outcome<T> = Outcome.Err("unused")
    }
}
