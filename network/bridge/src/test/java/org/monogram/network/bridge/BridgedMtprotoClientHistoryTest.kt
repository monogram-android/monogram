package org.monogram.network.bridge

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.common.TelegramCredentials
import org.monogram.core.models.PeerId
import org.monogram.mtproto.MtprotoNative
import org.monogram.network.bridge.session.DispatchClass
import org.monogram.network.http.MediaPriority
import uniffi.monogram_mtproto.ChatDto
import uniffi.monogram_mtproto.ForumTopicDto
import uniffi.monogram_mtproto.ForumTopicsPageDto
import uniffi.monogram_mtproto.MessageDto
import uniffi.monogram_mtproto.UpdateEventDto

class BridgedMtprotoClientHistoryTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun metadataUpdatesPublishChatsAndAnEmptyFolderList() = runTest {
        val native = object : RecordingNative() {
            override fun getFolders(handle: Long): List<uniffi.monogram_mtproto.FolderDto> = emptyList()
        }.apply { drainEventsOverride = listOf(UpdateEventDto.FoldersChanged, UpdateEventDto.ChatsChanged) }
        val client = BridgedMtprotoClient(
            credentials = TelegramCredentials(1, "hash"), sessionPath = "metadata.session",
            native = native, nativeDispatcher = StandardTestDispatcher(testScheduler), refreshDcSidecar = {},
        )
        try {
            val events = mutableListOf<MtprotoUpdate>()
            backgroundScope.async { client.updates().toList(events) }
            runCurrent()
            client.connect()
            advanceTimeBy(1_600)
            runCurrent()
            assertEquals(emptyList<org.monogram.core.models.Folder>(), events.filterIsInstance<MtprotoUpdate.FoldersChanged>().first().folders)
            val refreshed = events.filterIsInstance<MtprotoUpdate.ChatsChanged>().first().chats.single()
            assertEquals(PeerId(42), refreshed.id)
            assertEquals(2, refreshed.unreadMentionsCount)
            assertEquals(1, refreshed.unreadReactionsCount)
        } finally { client.close() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun remoteReadReceiptsReachSubscribersWithExactUnreadCounts() = runTest {
        val native = RecordingNative().apply {
            drainEventsOverride = listOf(
                UpdateEventDto.ReadInbox(42L, 18, 2),
                UpdateEventDto.ReadInbox(-1_000_000_000_042L, 50, 0),
            )
        }
        val client = BridgedMtprotoClient(
            credentials = TelegramCredentials(1, "hash"), sessionPath = "read.session",
            native = native, nativeDispatcher = StandardTestDispatcher(testScheduler), refreshDcSidecar = {},
        )
        try {
            client.connect()
            val events = mutableListOf<MtprotoUpdate.ReadInbox>()
            backgroundScope.async { client.updates().filterIsInstance<MtprotoUpdate.ReadInbox>().take(2).toList(events) }
            runCurrent()
            advanceTimeBy(1_600)
            runCurrent()
            assertEquals(listOf(
                MtprotoUpdate.ReadInbox(PeerId(42), 18, 2),
                MtprotoUpdate.ReadInbox(PeerId(-1_000_000_000_042L), 50, 0),
            ), events)
        } finally {
            client.close()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun onlySuccessfulWholeChatReadPublishesLocalConfirmation() = runTest {
        var fail = false
        val native = object : RecordingNative() {
            override fun readHistory(handle: Long, chatId: Long, maxId: Int) {
                if (fail) throw IllegalStateException("offline")
            }
            override fun readDiscussion(handle: Long, chatId: Long, msgId: Int, readMaxId: Int) = Unit
        }.apply { drainEventsOverride = emptyList() }
        val client = BridgedMtprotoClient(
            credentials = TelegramCredentials(1, "hash"), sessionPath = "read.session",
            native = native, nativeDispatcher = StandardTestDispatcher(testScheduler), refreshDcSidecar = {},
        )
        try {
            val events = mutableListOf<MtprotoUpdate>()
            backgroundScope.async { client.updates().toList(events) }
            runCurrent()
            assertTrue(client.readHistory(PeerId(42), 18) is Outcome.Ok)
            runCurrent()
            assertEquals(listOf(MtprotoUpdate.ReadHistoryConfirmed(PeerId(42), 18)), events)
            client.readDiscussion(PeerId(42), 10, 20)
            fail = true
            assertTrue(client.readHistory(PeerId(42), 20) is Outcome.Err)
            runCurrent()
            assertEquals(listOf(
                MtprotoUpdate.ReadHistoryConfirmed(PeerId(42), 18),
                MtprotoUpdate.DiscussionInbox(PeerId(42), 10, 20),
            ), events)
        } finally {
            client.close()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun chatMetadataBurstsCoalescePremiumRefreshes() = runTest {
        var profileCalls = 0
        val native = object : RecordingNative() {
            init {
                drainEventsOverride = listOf(UpdateEventDto.ChatsChanged)
            }
            override fun getProfile(handle: Long, peerId: Long): uniffi.monogram_mtproto.ProfileDto {
                profileCalls++
                assertEquals(0L, peerId)
                return uniffi.monogram_mtproto.ProfileDto(
                    id = 1, kind = "user", title = "Self", username = null,
                    about = null, avatarCacheKey = null, isSelf = true,
                    status = null, statusAt = null, extraJson = """{"premium":true}""",
                )
            }
        }
        val client = BridgedMtprotoClient(
            credentials = TelegramCredentials(1, "hash"), sessionPath = "premium.session",
            native = native, nativeDispatcher = StandardTestDispatcher(testScheduler),
            refreshDcSidecar = {},
        )
        try {
            val premiums = mutableListOf<MtprotoUpdate.AccountPremium>()
            val collector = backgroundScope.async {
                client.updates().filterIsInstance<MtprotoUpdate.AccountPremium>().toList(premiums)
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
    fun slowDrainCountsTowardRefreshCooldown() = runTest {
        val extra = ExtraClock(testScheduler)
        var chatsCalls = 0
        val native = object : RecordingNative() {
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
        val client = BridgedMtprotoClient(
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
            assertTrue("drain loops should stay far below the old 80-sleep cooldown, drains=${native.drainCalls}", native.drainCalls < 20)
        } finally {
            client.close()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun folderFloodRetryUsesMonotonicClock() = runTest {
        val extra = ExtraClock(testScheduler)
        var folderCalls = 0
        val native = object : RecordingNative() {
            override fun drainUpdates(handle: Long): List<UpdateEventDto> {
                drainCalls++
                return listOf(UpdateEventDto.FoldersChanged)
            }
            override fun getFolders(handle: Long): List<uniffi.monogram_mtproto.FolderDto> {
                folderCalls++
                if (folderCalls == 1) {
                    throw uniffi.monogram_mtproto.MtprotoException.Message("FLOOD_WAIT_5")
                }
                return emptyList()
            }
        }
        val client = BridgedMtprotoClient(
            credentials = TelegramCredentials(1, "hash"),
            sessionPath = "flood-refresh.session",
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
            assertEquals(1, folderCalls)
            extra.extraMs += 4_000L
            advanceTimeBy(50)
            runCurrent()
            assertEquals(1, folderCalls)
            extra.extraMs += 1_000L
            advanceTimeBy(50)
            runCurrent()
            assertEquals(2, folderCalls)
        } finally {
            client.close()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun handleChangeResetsRefreshCooldown() = runTest {
        val extra = ExtraClock(testScheduler)
        var chatsCalls = 0
        var nextHandle = 0L
        val native = object : RecordingNative() {
            override fun createClient(apiId: Int, apiHash: String, sessionPath: String): Long = ++nextHandle
            override fun drainUpdates(handle: Long): List<UpdateEventDto> {
                drainCalls++
                return listOf(UpdateEventDto.ChatsChanged)
            }
            override fun getChats(handle: Long): List<ChatDto> {
                chatsCalls++
                return super.getChats(handle)
            }
        }
        val client = BridgedMtprotoClient(
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

    private class ExtraClock(
        private val scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ) : org.monogram.network.bridge.session.MonotonicClock {
        @Volatile
        var extraMs = 0L
        override fun elapsedMs(): Long = scheduler.currentTime + extraMs
    }

    @Test
    fun streamingChunkPreservesOffsetAndMapsMediaErrors() = runBlocking {
        var observedOffset = -1L
        val native = object : RecordingNative() {
            override fun downloadMessageMediaChunk(handle: Long, chatId: Long, messageId: Int, destPath: String, offset: Long): String {
                observedOffset = offset
                assertEquals(42L, chatId)
                assertEquals(7, messageId)
                if (offset > 0) throw uniffi.monogram_mtproto.MtprotoException.Message("FILE_REFERENCE_EXPIRED")
                return destPath
            }
        }
        val client = BridgedMtprotoClient(
            credentials = TelegramCredentials(1, "hash"), sessionPath = "stream.session",
            native = native, refreshDcSidecar = {},
        )
        try {
            client.setDialogForeground(true)
            val first = client.downloadMessageMediaChunk(PeerId(42), 7, "part.bin", 0)
            assertTrue(first is Outcome.Ok)
            assertEquals("part.bin", (first as Outcome.Ok).value)
            val second = client.downloadMessageMediaChunk(PeerId(42), 7, "part.bin", 524288)
            assertEquals(524288L, observedOffset)
            assertTrue(second is Outcome.Err)
            assertEquals(org.monogram.core.common.telegram.TelegramError.Kind.FileReference, (second as Outcome.Err).telegramError.kind)
        } finally {
            client.close()
        }
    }

    @Test
    fun getHistoryReturnsMessagesWithoutDialogFreeze() = runBlocking {
        val native = RecordingNative()
        val client = BridgedMtprotoClient(
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
    fun getChatsCarriesMembershipForTheChatListToFilter() = runBlocking {
        val joined = BridgedMtprotoClient(
            credentials = TelegramCredentials(1, "hash"),
            sessionPath = "membership.session",
            native = RecordingNative(),
            refreshDcSidecar = {},
        )
        val joinedChat = (joined.getChats() as Outcome.Ok).value.single()
        assertFalse(joinedChat.left)
        assertFalse(joinedChat.isGroup)
        assertEquals(2, joinedChat.unreadMentionsCount)
        assertEquals(1, joinedChat.unreadReactionsCount)
        joined.close()

        val left = BridgedMtprotoClient(
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
    fun getHistoryMapsMediaMetrics() = runBlocking {
        val native = RecordingNative(
            historyMediaKind = "video",
            historyDuration = 12,
            historyWidth = 1280,
            historyHeight = 720,
        )
        val client = BridgedMtprotoClient(
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
    fun idleAvatarDownloadsAreBackgroundMediaAndSurviveAnActiveHistory() = runBlocking {
        val native = RecordingNative(getHistorySleepMs = 300)
        val client = BridgedMtprotoClient(
            credentials = TelegramCredentials(1, "hash"),
            sessionPath = "slice-history-avatar.session",
            native = native,
            refreshDcSidecar = {},
        )
        val history = async { client.getHistory(PeerId(42)) }
        delay(50)
        val avatar = client.downloadMessageMedia(PeerId(42), 0, "avatar.bin", MediaPriority.IDLE)
        assertTrue(avatar is Outcome.Ok)
        assertEquals(1, native.downloadCalls)
        assertEquals(DispatchClass.BACKGROUND_MEDIA, native.downloadDispatchClass)
        assertTrue(history.await() is Outcome.Ok)
        client.close()
    }

    @Test
    fun activeHistoryStillDownloadsMessagePhotos() = runBlocking {
        val native = RecordingNative(getHistorySleepMs = 300)
        val client = BridgedMtprotoClient(
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
    fun dialogForegroundDownloadsAvatarsAndMessagePhotos() = runBlocking {
        val native = RecordingNative()
        val client = BridgedMtprotoClient(
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
    fun clearingDialogForegroundReleasesNativeChannelWatcher() = runBlocking {
        val native = RecordingNative()
        val client = BridgedMtprotoClient(
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
    fun missingThumbIsNotFakeRpc400() = runBlocking {
        val native = object : RecordingNative() {
            override fun downloadMessageThumb(
                handle: Long,
                chatId: Long,
                messageId: Int,
                destPath: String,
            ): String {
                throw uniffi.monogram_mtproto.MtprotoException.Message("no downloadable thumb")
            }
        }
        val client = BridgedMtprotoClient(
            credentials = TelegramCredentials(1, "hash"),
            sessionPath = "slice-no-thumb.session",
            native = native,
            refreshDcSidecar = {},
        )
        val thumb = client.downloadMessageThumb(PeerId(42), 7, "thumb.bin")
        assertTrue(thumb is Outcome.Err)
        val err = thumb as Outcome.Err
        assertEquals("no downloadable thumb", err.message)
        assertEquals(org.monogram.core.common.telegram.TelegramError.Kind.Media, err.telegramError.kind)
        assertEquals(0, err.telegramError.httpCode)
        client.close()
    }

    @Test
    fun fileIdInvalidThumbMapsToNoDownloadableThumb() = runBlocking {
        val native = object : RecordingNative() {
            override fun downloadMessageThumb(
                handle: Long,
                chatId: Long,
                messageId: Int,
                destPath: String,
            ): String {
                throw uniffi.monogram_mtproto.MtprotoException.Message("RPC 400: FILE_ID_INVALID")
            }
        }
        val client = BridgedMtprotoClient(
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
    fun fileMigrateThumbDefersInsteadOfHardFail() = runBlocking {
        val native = object : RecordingNative() {
            override fun downloadMessageThumb(
                handle: Long,
                chatId: Long,
                messageId: Int,
                destPath: String,
            ): String {
                throw uniffi.monogram_mtproto.MtprotoException.Message("FILE_MIGRATE_2")
            }
        }
        val client = BridgedMtprotoClient(
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
    fun getHistoryProceedsWhileGetChatsIsBusy() = runBlocking {
        val native = RecordingNative(getChatsSleepMs = 400)
        val client = BridgedMtprotoClient(
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
    fun drainRunsWhileDialogIsForeground() = runBlocking {
        val native = RecordingNative()
        val client = BridgedMtprotoClient(
            credentials = TelegramCredentials(1, "hash"),
            sessionPath = "slice2-drain.session",
            native = native,
            refreshDcSidecar = {},
        )
        client.connect()
        client.setDialogForeground(true)
        val event = withTimeout(4_000) {
            client.updates().filterIsInstance<MtprotoUpdate.NewMessage>().first()
        }
        assertEquals("hello", event.message.text)
        assertTrue(native.drainCalls >= 1)
        client.close()
    }

    @Test
    fun drainRunsWhileHistoryIsInFlight() = runBlocking {
        val native = RecordingNative(getHistorySleepMs = 2_500)
        val client = BridgedMtprotoClient(
            credentials = TelegramCredentials(1, "hash"),
            sessionPath = "slice2-drain-history.session",
            native = native,
            refreshDcSidecar = {},
        )
        client.connect()
        val history = async { client.getHistory(PeerId(42)) }
        delay(50)
        val event = withTimeout(4_000) {
            client.updates().filterIsInstance<MtprotoUpdate.NewMessage>().first()
        }
        assertEquals("hello", event.message.text)
        assertTrue(native.drainCalls >= 1)
        val historyResult = history.await()
        assertTrue(historyResult is Outcome.Ok)
        client.close()
    }

    @Test
    fun mapsEditedAndDeletedUpdateEvents() = runBlocking {
        val native = RecordingNative()
        native.drainEventsOverride = listOf(
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
                ),
            ),
            UpdateEventDto.MessagesDeleted(chatId = 42L, messageIds = listOf(7)),
        )
        val client = BridgedMtprotoClient(
            credentials = TelegramCredentials(1, "hash"),
            sessionPath = "slice-update-mutations.session",
            native = native,
            refreshDcSidecar = {},
        )
        client.connect()

        val events = withTimeout(4_000) {
            client.updates()
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
    fun sendPhotoUsesNativePathAndCaption() = runBlocking {
        val native = RecordingNative()
        val client = BridgedMtprotoClient(
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
    fun forwardMessageUsesNativeIds() = runBlocking {
        val native = RecordingNative()
        val client = BridgedMtprotoClient(
            credentials = TelegramCredentials(1, "hash"),
            sessionPath = "slice-forward.session",
            native = native,
            refreshDcSidecar = {},
        )
        val sent = client.forwardMessage(PeerId(42), 7, PeerId(99))
        assertTrue(sent is Outcome.Ok)
        assertEquals(99L, (sent as Outcome.Ok).value.single().id.chatId.value)
        assertEquals(42L, sent.value.single().fwdFromId)
        assertEquals(123L, sent.value.single().fwdDate)
        assertEquals(42L, native.lastForwardFrom)
        assertEquals(99L, native.lastForwardTo)
        assertEquals(7, native.lastForwardMessageId)
        client.close()
    }

    @Test
    fun forwardMessagesUsesNativeIdsAndDropAuthor() = runBlocking {
        val native = RecordingNative()
        val client = BridgedMtprotoClient(
            credentials = TelegramCredentials(1, "hash"),
            sessionPath = "slice-forward-batch.session",
            native = native,
            refreshDcSidecar = {},
        )
        val sent = client.forwardMessages(PeerId(42), listOf(7, 8), PeerId(99), dropAuthor = true)
        assertTrue(sent is Outcome.Ok)
        assertEquals(listOf(7, 8), native.lastForwardMessageIds)
        assertTrue(native.lastForwardDropAuthor)
        client.close()
    }

    @Test
    fun getPinnedMessagesUsesNativeChatId() = runBlocking {
        val native = RecordingNative()
        val client = BridgedMtprotoClient(
            credentials = TelegramCredentials(1, "hash"),
            sessionPath = "slice-pinned.session",
            native = native,
            refreshDcSidecar = {},
        )
        val pinned = client.getPinnedMessages(PeerId(42))
        assertTrue(pinned is Outcome.Ok)
        assertEquals(1, (pinned as Outcome.Ok).value.size)
        assertEquals(11, pinned.value.single().id.id)
        assertEquals(42L, native.lastPinnedChatId)
        client.close()
    }

    @Test
    fun getForumTopicsMapsGeneralAndDropsDeleted() = runBlocking {
        val native = RecordingNative()
        val client = BridgedMtprotoClient(
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

    private open class RecordingNative(
        private val getChatsSleepMs: Long = 0,
        private val getHistorySleepMs: Long = 0,
        private val historyMediaKind: String? = null,
        private val historyDuration: Int? = null,
        private val historyWidth: Int? = null,
        private val historyHeight: Int? = null,
        private val chatLeft: Boolean = false,
    ) : MtprotoNative by MtprotoNative.Stub {
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

        override fun createClient(apiId: Int, apiHash: String, sessionPath: String): Long = 1L

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
                    canForward = true,
                    canDeleteOthers = false,
                    emojiStatusDocumentId = null,
                    lastMessageOutgoing = false,
                    muteOverride = false,
                    unreadMark = false,
                    unreadMentionsCount = 2,
                    unreadReactionsCount = 1,
                ),
            )
        }

        override fun getHistory(handle: Long, chatId: Long, limit: Int): List<MessageDto> {
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
                topics = listOf(
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
                ),
            )
        }
    }
}
