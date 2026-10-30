package org.monogram.feature.dialog.scheduled

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.*
import org.monogram.network.bridge.message.MessageOps
import java.lang.reflect.Proxy

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScheduledQueueStoreTest {
    private val peer = PeerId(7)
    private fun message(id: Int, date: Long = 100) = ScheduledMessage(
        Message(
            id = MessageId(peer, id),
            senderId = null,
            text = "fixture",
            date = date,
            outgoing = true,
        )
    )

    private class Cache : OfflineWarmup() {
        val rows = MutableStateFlow<List<ScheduledMessage>>(emptyList())
        var revision = 0L
        var blockedUntil = 0L
        override suspend fun outgoingSendBlockedUntil() = blockedUntil
        override suspend fun blockScheduledQueueUntil(deadline: Long, expectedEpoch: Long) {
            blockedUntil = maxOf(blockedUntil, deadline)
        }

        override fun observeScheduledMessages(chatId: PeerId) = rows
        override suspend fun scheduledQueueRevision(chatId: PeerId, expectedEpoch: Long) = revision
        override suspend fun replaceScheduledMessagesIfCurrent(
            chatId: PeerId, expectedRevision: Long,
            messages: List<ScheduledMessage>, expectedEpoch: Long
        ): Boolean {
            if (expectedRevision != revision) return false
            revision++
            rows.value = messages
            return true
        }
    }

    private fun client(
        send: suspend (List<Int>) -> Outcome<Unit> = { error("Unexpected send") },
        cancel: suspend (List<Int>) -> Outcome<Unit> = { error("Unexpected cancellation") },
        edit: suspend (Int, Int, String?, String?, Boolean) -> Outcome<Unit> = { _, _, _, _, _ ->
            error(
                "Unexpected edit"
            )
        },
        query: suspend () -> Outcome<List<ScheduledMessage>>,
    ): MessageOps {
        val unused = Proxy.newProxyInstance(
            MessageOps::class.java.classLoader,
            arrayOf(MessageOps::class.java)
        ) { _, method, _ ->
            error("Unexpected API: ${method.name}")
        } as MessageOps
        return object : MessageOps by unused {
            override suspend fun getScheduledHistory(chatId: PeerId) = query()
            override suspend fun sendScheduledMessages(chatId: PeerId, ids: List<Int>) = send(ids)
            override suspend fun deleteScheduledMessages(chatId: PeerId, ids: List<Int>) =
                cancel(ids)

            override suspend fun editScheduledMessage(
                chatId: PeerId, messageId: Int, scheduleDate: Int,
                text: String?, entitiesJson: String?, noWebpage: Boolean
            ) = edit(messageId, scheduleDate, text, entitiesJson, noWebpage)
        }
    }

    @Test
    fun previewCancellationDeletionAndLateCompletionNeverRestoreAStaleSelection() = runTest {
        val row = message(8).copy(
            message = message(8).message.copy(
                mediaKind = "photo",
                mediaCacheKey = "queue-photo"
            )
        )
        val cache = Cache().apply { rows.value = listOf(row) }
        val gate = CompletableDeferred<Outcome<java.io.File>>()
        var loads = 0
        val store = ScheduledQueueStoreFactory(
            DefaultStoreFactory(), client { Outcome.Ok(listOf(row)) }, cache, peer,
            UnconfinedTestDispatcher(testScheduler), loadPreview = {
                assertEquals(8, it.message.id.id)
                loads++
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            }).create()
        try {
            store.accept(ScheduledQueueStore.Intent.Resume); advanceUntilIdle()
            store.accept(ScheduledQueueStore.Intent.Preview(8)); store.accept(
                ScheduledQueueStore.Intent.Preview(
                    8
                )
            )
            assertEquals(1, loads); assertTrue(store.state.preview!!.loading)
            cache.rows.value = emptyList(); advanceUntilIdle()
            assertNull(store.state.preview)
            gate.complete(Outcome.Ok(java.io.File("synthetic-preview"))); advanceUntilIdle()
            assertNull(store.state.preview)
        } finally {
            gate.complete(Outcome.Err("cancelled")); store.dispose()
        }
    }

    @Test
    fun resumingPreviewRefetchesAnEvictedFile() = runTest {
        val row = message(8).copy(
            message = message(8).message.copy(
                mediaKind = "photo",
                mediaCacheKey = "queue-photo"
            )
        )
        val file = java.io.File.createTempFile("scheduled-preview-", ".png")
        var calls = 0
        val store = ScheduledQueueStoreFactory(
            DefaultStoreFactory(), client { Outcome.Ok(listOf(row)) }, null, peer,
            UnconfinedTestDispatcher(testScheduler), loadPreview = {
                calls++; file.writeBytes(byteArrayOf(1)); Outcome.Ok(file)
            }).create()
        try {
            store.accept(ScheduledQueueStore.Intent.Resume); advanceUntilIdle()
            store.accept(ScheduledQueueStore.Intent.Preview(8)); advanceUntilIdle()
            assertEquals(1, calls)
            store.accept(ScheduledQueueStore.Intent.Pause); advanceUntilIdle()
            assertTrue(file.delete())
            store.accept(ScheduledQueueStore.Intent.Resume); advanceUntilIdle()
            assertEquals(2, calls); assertTrue(store.state.preview!!.file!!.exists())
        } finally {
            store.dispose(); file.delete()
        }
    }

    @Test
    fun previewPauseResumeAndFailureRetryAreReadsOnly() = runTest {
        val row = message(8).copy(
            message = message(8).message.copy(
                mediaKind = "photo",
                mediaCacheKey = "queue-photo"
            )
        )
        val first = CompletableDeferred<Outcome<java.io.File>>()
        val file = java.io.File("synthetic-preview")
        var calls = 0
        val store = ScheduledQueueStoreFactory(
            DefaultStoreFactory(), client { Outcome.Ok(listOf(row)) }, null, peer,
            UnconfinedTestDispatcher(testScheduler), loadPreview = {
                calls++
                when (calls) {
                    1 -> first.await(); 2 -> Outcome.Err("RPC 500: INTERNAL"); else -> Outcome.Ok(
                    file
                )
                }
            }).create()
        try {
            store.accept(ScheduledQueueStore.Intent.Resume); advanceUntilIdle()
            store.accept(ScheduledQueueStore.Intent.Preview(8)); assertTrue(store.state.preview!!.loading)
            store.accept(ScheduledQueueStore.Intent.Pause); advanceUntilIdle()
            assertFalse(store.state.preview!!.loading)
            store.accept(ScheduledQueueStore.Intent.Resume); advanceUntilIdle()
            assertEquals(2, calls); assertNotNull(store.state.preview!!.error)
            store.accept(ScheduledQueueStore.Intent.RetryPreview); advanceUntilIdle()
            assertEquals(file, store.state.preview!!.file)
            store.accept(ScheduledQueueStore.Intent.ClosePreview)
            assertNull(store.state.preview)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun previewFloodCannotBeBypassedByRetryOrWallClockMovement() = runTest {
        val row = message(8).copy(
            message = message(8).message.copy(
                mediaKind = "photo",
                mediaCacheKey = "queue-photo"
            )
        )
        var wall = 1_000_000L
        var monotonic = 1000L
        var calls = 0
        val cache = Cache()
        val store = ScheduledQueueStoreFactory(
            DefaultStoreFactory(),
            client { Outcome.Ok(listOf(row)) },
            cache,
            peer,
            UnconfinedTestDispatcher(testScheduler),
            { wall },
            { monotonic },
            loadPreview = {
                calls++; Outcome.Err("FLOOD_WAIT_10")
            }).create()
        try {
            store.accept(ScheduledQueueStore.Intent.Resume); advanceUntilIdle()
            store.accept(ScheduledQueueStore.Intent.Preview(8)); advanceUntilIdle()
            assertEquals(1_010_000L, cache.blockedUntil)
            wall += 20_000
            store.accept(ScheduledQueueStore.Intent.RetryPreview); advanceUntilIdle()
            assertEquals(1, calls)
            monotonic += 10_001
            store.accept(ScheduledQueueStore.Intent.RetryPreview); advanceUntilIdle()
            assertEquals(2, calls)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun captionEditPreservesDateAndFormattingAndRejectsStaleOrUnsupportedSnapshots() = runTest {
        val original = message(8, 1900).copy(
            hasUnsupportedContent = false,
            message = message(8, 1900).message.copy(
                mediaKind = "photo",
                mediaCacheKey = "scheduled-photo"
            )
        )
        val cache = Cache().apply { rows.value = listOf(original) }
        var server = listOf(original)
        var edits = 0
        val api = client(edit = { id, date, text, json, noWebpage ->
            assertEquals(8, id); assertEquals(1900, date)
            assertEquals("new caption", text)
            assertEquals(listOf(TextEntity("bold", 0, 3)), TextEntities.parse(json))
            assertTrue(noWebpage)
            edits++
            server = listOf(
                original.copy(
                    message = original.message.copy(
                        text = text,
                        entities = TextEntities.parse(json)
                    )
                )
            )
            Outcome.Ok(Unit)
        }) { Outcome.Ok(server) }
        val store = ScheduledQueueStoreFactory(
            DefaultStoreFactory(), api, cache, peer,
            UnconfinedTestDispatcher(testScheduler)
        ).create()
        try {
            store.accept(ScheduledQueueStore.Intent.Resume); advanceUntilIdle()
            store.accept(
                ScheduledQueueStore.Intent.Edit(
                    original.copy(hasUnsupportedContent = true),
                    "new caption",
                    emptyList()
                )
            )
            store.accept(
                ScheduledQueueStore.Intent.Edit(
                    original.copy(
                        message = original.message.copy(
                            date = 1800
                        )
                    ), "new caption", emptyList()
                )
            )
            store.accept(
                ScheduledQueueStore.Intent.Edit(
                    original,
                    "new caption",
                    listOf(TextEntity("bold", 0, 99))
                )
            )
            assertEquals(0, edits)
            store.accept(
                ScheduledQueueStore.Intent.Edit(
                    original,
                    "new caption",
                    listOf(TextEntity("bold", 0, 3))
                )
            )
            advanceUntilIdle()
            assertEquals(1, edits)
            val saved = store.state.messages.single().message
            assertEquals("scheduled-photo", saved.mediaCacheKey)
            assertEquals(1900L, saved.date)
            assertEquals("new caption", saved.text)
            store.accept(ScheduledQueueStore.Intent.Edit(original, "stale", emptyList()))
            advanceUntilIdle(); assertEquals(1, edits)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun rescheduleChangesOnlyTheDateAndRejectsInvalidDatesBeforeRpc() = runTest {
        val original = ScheduledMessage(
            message(8, 1900).message.copy(
                mediaKind = "photo",
                entities = listOf(TextEntity("bold", 0, 3))
            )
        )
        var server = listOf(original)
        var edits = 0
        val api = client(edit = { id, date, text, entities, noWebpage ->
            assertEquals(8, id)
            assertEquals(2000, date)
            assertNull(text); assertNull(entities); assertFalse(noWebpage)
            edits++
            server = listOf(ScheduledMessage(original.message.copy(date = date.toLong())))
            Outcome.Ok(Unit)
        }) { Outcome.Ok(server) }
        val store = ScheduledQueueStoreFactory(
            DefaultStoreFactory(), api, null, peer,
            UnconfinedTestDispatcher(testScheduler), { 1_000_000L }).create()
        try {
            store.accept(ScheduledQueueStore.Intent.Resume)
            store.accept(ScheduledQueueStore.Intent.Reschedule(8, 1001))
            store.accept(ScheduledQueueStore.Intent.Reschedule(8, Int.MAX_VALUE))
            store.accept(ScheduledQueueStore.Intent.Reschedule(0, 2000))
            assertEquals(0, edits)
            store.accept(ScheduledQueueStore.Intent.Reschedule(8, 2000)); advanceUntilIdle()
            assertEquals(1, edits)
            assertEquals(
                listOf(ScheduledMessage(original.message.copy(date = 2000))),
                store.state.messages
            )
            assertNull(store.state.mutatingId)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun restoredCacheCannotMutateBeforeAFreshServerSnapshot() = runTest {
        val cache = Cache().apply { rows.value = listOf(message(8)) }
        val gate = CompletableDeferred<Outcome<List<ScheduledMessage>>>()
        var sends = 0
        val api = client(send = { sends++; Outcome.Ok(Unit) }) { gate.await() }
        val store = ScheduledQueueStoreFactory(
            DefaultStoreFactory(), api, cache, peer,
            UnconfinedTestDispatcher(testScheduler)
        ).create()
        try {
            store.accept(ScheduledQueueStore.Intent.Resume)
            store.accept(ScheduledQueueStore.Intent.SendNow(8))
            assertEquals(0, sends)
            gate.complete(Outcome.Ok(listOf(message(8))))
            advanceUntilIdle()
            store.accept(ScheduledQueueStore.Intent.SendNow(8)); advanceUntilIdle()
            assertEquals(1, sends)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun floodDeadlineSurvivesStoreRecreationAndRefreshCannotBypassMonotonicWait() = runTest {
        val cache = Cache().apply { rows.value = listOf(message(8)) }
        var wall = 1_000L
        var elapsed = 5_000L
        var sends = 0
        var reads = 0
        val api = client(send = { sends++; Outcome.Err("FLOOD_WAIT_10") }) {
            reads++; Outcome.Ok(listOf(message(8)))
        }

        fun create() = ScheduledQueueStoreFactory(
            DefaultStoreFactory(), api, cache, peer,
            UnconfinedTestDispatcher(testScheduler), { wall }, { elapsed }).create()

        var store = create()
        try {
            store.accept(ScheduledQueueStore.Intent.Resume)
            store.accept(ScheduledQueueStore.Intent.SendNow(8)); advanceUntilIdle()
            assertEquals(11_000L, cache.blockedUntil)
            assertEquals(1, sends)
            store.dispose(); store = create()
            store.accept(ScheduledQueueStore.Intent.Resume); advanceUntilIdle()
            assertEquals(1, reads)
            wall = 100_000L
            store.accept(ScheduledQueueStore.Intent.Refresh)
            store.accept(ScheduledQueueStore.Intent.SendNow(8)); advanceUntilIdle()
            assertEquals(1, sends)
            assertEquals(1, reads)
            wall = 11_000L; elapsed = 15_000L
            store.accept(ScheduledQueueStore.Intent.Refresh); advanceUntilIdle()
            assertEquals(2, reads)
            assertEquals(1, sends)
            assertNull(store.state.error)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun sendNowClaimsOneVisibleIdAndRefreshesWithoutOptimisticHistoryChanges() = runTest {
        val gate = CompletableDeferred<Outcome<Unit>>()
        var calls = 0
        var server = listOf(message(8))
        val api = client(send = { ids ->
            assertEquals(
                listOf(8),
                ids
            ); calls++; gate.await()
        }) { Outcome.Ok(server) }
        val store = ScheduledQueueStoreFactory(
            DefaultStoreFactory(), api, null, peer,
            UnconfinedTestDispatcher(testScheduler)
        ).create()
        try {
            store.accept(ScheduledQueueStore.Intent.Resume)
            store.accept(ScheduledQueueStore.Intent.SendNow(0))
            store.accept(ScheduledQueueStore.Intent.SendNow(99))
            assertEquals(0, calls)
            store.accept(ScheduledQueueStore.Intent.SendNow(8))
            store.accept(ScheduledQueueStore.Intent.SendNow(8))
            assertEquals(1, calls)
            assertEquals(8, store.state.mutatingId)
            assertEquals(listOf(message(8)), store.state.messages)
            server = emptyList(); gate.complete(Outcome.Ok(Unit)); advanceUntilIdle()
            assertTrue(store.state.messages.isEmpty())
            assertNull(store.state.mutatingId)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun uncertainMutationRequiresAnAuthoritativeReadAndAnotherExplicitIntent() = runTest {
        var calls = 0
        val api = client(send = { calls++; Outcome.Err("NETWORK_UNAVAILABLE") }) {
            Outcome.Ok(
                listOf(message(8))
            )
        }
        val store = ScheduledQueueStoreFactory(
            DefaultStoreFactory(), api, null, peer,
            UnconfinedTestDispatcher(testScheduler)
        ).create()
        try {
            store.accept(ScheduledQueueStore.Intent.Resume)
            store.accept(ScheduledQueueStore.Intent.SendNow(8)); advanceUntilIdle()
            assertEquals(setOf(8), store.state.uncertainIds)
            assertNotNull(store.state.error)
            store.accept(ScheduledQueueStore.Intent.SendNow(8))
            assertEquals(1, calls)
            store.accept(ScheduledQueueStore.Intent.Resume); advanceUntilIdle()
            assertTrue(store.state.uncertainIds.isEmpty())
            assertEquals(1, calls)
            store.accept(ScheduledQueueStore.Intent.SendNow(8)); advanceUntilIdle()
            assertEquals(2, calls)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun pausingCancellationDoesNotReplayItOnResume() = runTest {
        val gate = CompletableDeferred<Outcome<Unit>>()
        var cancellations = 0
        val api = client(cancel = { ids ->
            assertEquals(
                listOf(8),
                ids
            ); cancellations++; gate.await()
        }) {
            Outcome.Ok(listOf(message(8)))
        }
        val store = ScheduledQueueStoreFactory(
            DefaultStoreFactory(), api, null, peer,
            UnconfinedTestDispatcher(testScheduler)
        ).create()
        try {
            store.accept(ScheduledQueueStore.Intent.Resume)
            store.accept(ScheduledQueueStore.Intent.Cancel(8))
            store.accept(ScheduledQueueStore.Intent.Pause); advanceUntilIdle()
            assertEquals(setOf(8), store.state.uncertainIds)
            assertNull(store.state.mutatingId)
            store.accept(ScheduledQueueStore.Intent.Resume); advanceUntilIdle()
            assertEquals(1, cancellations)
            assertEquals(listOf(message(8)), store.state.messages)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun cachedQueuePaintsBeforeFetchAndPushDeletionRejectsStaleSnapshot() = runTest {
        val cache = Cache().apply { rows.value = listOf(message(1)) }
        val gate = CompletableDeferred<Outcome<List<ScheduledMessage>>>()
        var calls = 0
        val store = ScheduledQueueStoreFactory(
            DefaultStoreFactory(), client { calls++; gate.await() }, cache, peer,
            UnconfinedTestDispatcher(testScheduler)
        ).create()
        try {
            assertEquals(listOf(message(1)), store.state.messages)
            store.accept(ScheduledQueueStore.Intent.Resume)
            store.accept(ScheduledQueueStore.Intent.Refresh)
            assertEquals(1, calls)
            assertTrue(store.state.loading)
            cache.revision++; cache.rows.value = emptyList()
            gate.complete(Outcome.Ok(listOf(message(1))))
            advanceUntilIdle()
            assertTrue(store.state.messages.isEmpty())
            assertTrue(store.state.needsRefresh)
            assertFalse(store.state.loading)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun pauseCancelsFetchAndFailedRefreshPreservesCachedContent() = runTest {
        val cache = Cache().apply { rows.value = listOf(message(1)) }
        var query: suspend () -> Outcome<List<ScheduledMessage>> =
            { CompletableDeferred<Outcome<List<ScheduledMessage>>>().await() }
        val store = ScheduledQueueStoreFactory(
            DefaultStoreFactory(), client { query() }, cache, peer,
            UnconfinedTestDispatcher(testScheduler)
        ).create()
        try {
            store.accept(ScheduledQueueStore.Intent.Resume)
            store.accept(ScheduledQueueStore.Intent.Pause)
            advanceUntilIdle()
            assertFalse(store.state.loading)
            assertNull(store.state.error)
            query = { Outcome.Err("NETWORK_UNAVAILABLE") }
            store.accept(ScheduledQueueStore.Intent.Resume)
            advanceUntilIdle()
            assertNotNull(store.state.error)
            assertEquals(listOf(message(1)), store.state.messages)
            query = { Outcome.Ok(listOf(message(2))) }
            store.accept(ScheduledQueueStore.Intent.Refresh)
            advanceUntilIdle()
            assertNull(store.state.error)
            assertFalse(store.state.fromCache)
            assertEquals(listOf(message(2)), store.state.messages)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun cancelledFetchCannotClearLoadingOfAnImmediateResume() = runTest {
        val first = CompletableDeferred<Outcome<List<ScheduledMessage>>>()
        val second = CompletableDeferred<Outcome<List<ScheduledMessage>>>()
        var calls = 0
        val store = ScheduledQueueStoreFactory(DefaultStoreFactory(), client {
            calls++
            if (calls == 1) first.await() else second.await()
        }, null, peer, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)).create()
        try {
            store.accept(ScheduledQueueStore.Intent.Resume)
            testScheduler.runCurrent()
            store.accept(ScheduledQueueStore.Intent.Pause)
            store.accept(ScheduledQueueStore.Intent.Resume)
            testScheduler.runCurrent()
            assertEquals(2, calls)
            assertTrue(store.state.loading)
            second.complete(Outcome.Ok(listOf(message(2))))
            advanceUntilIdle()
            assertFalse(store.state.loading)
            assertEquals(listOf(message(2)), store.state.messages)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun uncachedQueueIsOrderedAndForeignPeerResponsesAreRejected() = runTest {
        var result: Outcome<List<ScheduledMessage>> =
            Outcome.Ok(listOf(message(2, 200), message(1)))
        val store = ScheduledQueueStoreFactory(
            DefaultStoreFactory(), client { result }, null, peer,
            UnconfinedTestDispatcher(testScheduler)
        ).create()
        try {
            store.accept(ScheduledQueueStore.Intent.Resume)
            advanceUntilIdle()
            assertEquals(listOf(1, 2), store.state.messages.map { it.message.id.id })
            assertFalse(store.state.fromCache)
            result = Outcome.Ok(
                listOf(
                    ScheduledMessage(
                        message(3).message.copy(
                            id = MessageId(
                                PeerId(8),
                                3
                            )
                        )
                    )
                )
            )
            store.accept(ScheduledQueueStore.Intent.Refresh)
            advanceUntilIdle()
            assertNotNull(store.state.error)
            assertEquals(listOf(1, 2), store.state.messages.map { it.message.id.id })
        } finally {
            store.dispose()
        }
    }
}
