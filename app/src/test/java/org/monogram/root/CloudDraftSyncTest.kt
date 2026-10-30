package org.monogram.root

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.CloudDraft
import org.monogram.core.models.CloudDraftState
import org.monogram.core.models.PeerId

class CloudDraftSyncTest {
    private val peer = PeerId(7)

    private class Cache(initial: CloudDraftState) : OfflineWarmup() {
        val states = MutableStateFlow(listOf(initial))
        override suspend fun cloudDraftStates() = states.value
        override suspend fun cloudDraftState(chatId: PeerId, threadId: Int) =
            states.value.firstOrNull { it.draft.chatId == chatId && it.draft.threadId == threadId }
                ?: CloudDraftState(CloudDraft(chatId, threadId))

        override fun observeCloudDraftStates() = states
        fun edit(text: String) {
            states.value = states.value.map { it.localEdit(it.draft.copy(text = text)) }
        }

        override suspend fun applyCloudDraft(draft: CloudDraft, expectedRevision: Long?) {
            states.value = states.value.map {
                if (it.draft.chatId == draft.chatId && it.draft.threadId == draft.threadId &&
                    (expectedRevision == null || expectedRevision == it.revision)
                ) it.mergeRemote(draft) else it
            }
        }

        override suspend fun acknowledgeCloudDraft(chatId: PeerId, threadId: Int, revision: Long) {
            states.value = states.value.map {
                if (it.draft.chatId == chatId && it.draft.threadId == threadId) it.acknowledge(
                    revision
                ) else it
            }
        }
    }

    @Test
    fun anEditDuringFetchIsSavedInsteadOfOverwritten() = runBlocking {
        val cache = Cache(CloudDraftState(CloudDraft(peer, text = "old", date = 10)))
        val saved = mutableListOf<String>()
        val sync = CloudDraftSync(cache, fetch = {
            cache.edit("new local")
            Outcome.Ok(listOf(CloudDraft(peer, text = "stale snapshot", date = 20)))
        }, save = { saved += it.text; Outcome.Ok(Unit) })
        assertEquals(0L, sync.syncOnce())
        assertEquals(listOf("new local"), saved)
        assertEquals("new local", cache.states.value.single().draft.text)
        assertFalse(cache.states.value.single().dirty)
    }

    @Test
    fun anEditDuringSaveRemainsPendingUntilItsOwnAcknowledgment() = runBlocking {
        val cache =
            Cache(CloudDraftState(CloudDraft(peer, text = "first"), revision = 1, dirty = true))
        val saved = mutableListOf<String>()
        val sync = CloudDraftSync(cache, { Outcome.Ok(emptyList()) }, {
            saved += it.text
            if (saved.size == 1) cache.edit("second")
            Outcome.Ok(Unit)
        })
        sync.syncOnce()
        assertTrue(cache.states.value.single().dirty)
        sync.syncOnce()
        assertEquals(listOf("first", "second"), saved)
        assertFalse(cache.states.value.single().dirty)
    }

    @Test
    fun aRestartRetriesPersistedEditsAndHonorsFloodWait() = runBlocking {
        val logging = org.monogram.core.common.DebugLog.enabled
        org.monogram.core.common.DebugLog.resetForTests()
        try {
            val cache = Cache(
                CloudDraftState(
                    CloudDraft(peer, text = "pending"),
                    revision = 1,
                    dirty = true
                )
            )
            val first =
                CloudDraftSync(cache, { Outcome.Ok(emptyList()) }, { Outcome.Err("FLOOD_WAIT_72") })
            assertEquals(72_000L, first.syncOnce())
            assertTrue(cache.states.value.single().dirty)
            var count = 0
            val restored =
                CloudDraftSync(cache, { Outcome.Ok(emptyList()) }, { count++; Outcome.Ok(Unit) })
            assertEquals(0L, restored.syncOnce())
            assertEquals(1, count)
            assertFalse(cache.states.value.single().dirty)
            val logs = org.monogram.core.common.DebugLog.exportText(text = "draft-sync")
            assertTrue(logs.contains("save failed kind=Flood"))
            assertTrue(logs.contains("save completed"))
            assertFalse(logs.contains("pending"))
        } finally {
            org.monogram.core.common.DebugLog.resetForTests(logging)
        }
    }

    @Test
    fun missingServerDraftsClearCleanCachedDraftsWithoutSending() = runBlocking {
        val cache = Cache(CloudDraftState(CloudDraft(peer, text = "old remote", date = 10)))
        val sync = CloudDraftSync(
            cache,
            { Outcome.Ok(emptyList()) },
            { error("clean drafts must not send") })
        sync.syncOnce()
        assertEquals("", cache.states.value.single().draft.text)
        assertFalse(cache.states.value.single().dirty)
    }

    @Test
    fun oneRejectedPeerDoesNotBlockOthersOrRetryUntilEditedOrRequested() = runBlocking {
        val other = PeerId(8)
        val cache =
            Cache(CloudDraftState(CloudDraft(peer, text = "first"), revision = 1, dirty = true))
        cache.states.value += CloudDraftState(
            CloudDraft(other, text = "second"),
            revision = 1,
            dirty = true
        )
        val attempted = mutableListOf<PeerId>()
        var allow = false
        val sync = CloudDraftSync(cache, { Outcome.Ok(emptyList()) }, {
            attempted += it.chatId
            if (it.chatId == peer && !allow) Outcome.Err("CHAT_WRITE_FORBIDDEN") else Outcome.Ok(
                Unit
            )
        })
        assertEquals(0L, sync.syncOnce())
        assertEquals(listOf(peer, other), attempted)
        assertNotNull(cache.draftSync.observe(peer).first().error)
        assertNull(cache.draftSync.observe(other).first().error)
        assertFalse(cache.states.value.first { it.draft.chatId == other }.dirty)
        sync.syncOnce()
        assertEquals(2, attempted.size)
        cache.states.value = cache.states.value.map {
            if (it.draft.chatId == peer) it.localEdit(it.draft.copy(text = "edited")) else it
        }
        sync.syncOnce()
        assertEquals(listOf(peer, other, peer), attempted)
        allow = true
        sync.requestRefresh()
        sync.syncOnce()
        assertEquals(listOf(peer, other, peer, peer), attempted)
        assertNull(cache.draftSync.observe(peer).first().error)
        assertFalse(cache.states.value.first { it.draft.chatId == peer }.dirty)
    }

    @Test
    fun accountFloodWaitStopsTheBatchAndIsReportedToOtherDrafts() = runBlocking {
        val other = PeerId(8)
        val cache =
            Cache(CloudDraftState(CloudDraft(peer, text = "first"), revision = 1, dirty = true))
        cache.states.value += CloudDraftState(
            CloudDraft(other, text = "second"),
            revision = 1,
            dirty = true
        )
        val attempted = mutableListOf<PeerId>()
        val sync = CloudDraftSync(cache, { Outcome.Ok(emptyList()) }, {
            attempted += it.chatId
            Outcome.Err("FLOOD_WAIT_72")
        })
        assertEquals(72_000L, sync.syncOnce())
        assertEquals(listOf(peer), attempted)
        assertEquals(
            org.monogram.core.common.telegram.TelegramError.Kind.Flood,
            cache.draftSync.observe(other).first().error?.kind
        )
        assertTrue(cache.states.value.all { it.dirty })
    }

    @Test
    fun anotherDraftEditedDuringASaveUsesItsLatestRevision() = runBlocking {
        val other = PeerId(8)
        val cache =
            Cache(CloudDraftState(CloudDraft(peer, text = "first"), revision = 1, dirty = true))
        cache.states.value += CloudDraftState(
            CloudDraft(other, text = "old"),
            revision = 1,
            dirty = true
        )
        val saved = mutableListOf<String>()
        val sync = CloudDraftSync(cache, { Outcome.Ok(emptyList()) }, {
            saved += it.text
            if (it.chatId == peer) cache.states.value = cache.states.value.map { state ->
                if (state.draft.chatId == other) state.localEdit(state.draft.copy(text = "new")) else state
            }
            Outcome.Ok(Unit)
        })
        sync.syncOnce()
        assertEquals(listOf("first", "new"), saved)
    }

    @Test
    fun unsupportedContentDetectedDuringAnotherSaveIsNotOverwritten() = runBlocking {
        val other = PeerId(8)
        val cache =
            Cache(CloudDraftState(CloudDraft(peer, text = "first"), revision = 1, dirty = true))
        cache.states.value += CloudDraftState(
            CloudDraft(other, text = "old"),
            revision = 1,
            dirty = true
        )
        val saved = mutableListOf<PeerId>()
        val sync = CloudDraftSync(cache, { Outcome.Ok(emptyList()) }, {
            saved += it.chatId
            cache.states.value = cache.states.value.map { state ->
                if (state.draft.chatId == other) state.mergeRemote(
                    state.draft.copy(
                        date = 20,
                        hasUnsupportedContent = true
                    )
                ) else state
            }
            Outcome.Ok(Unit)
        })
        sync.syncOnce()
        assertEquals(listOf(peer), saved)
        assertTrue(cache.states.value.first { it.draft.chatId == other }.draft.hasUnsupportedContent)
    }

    @Test
    fun unknownServerFailuresRemainGlobalAndRetryable() = runBlocking {
        val cache =
            Cache(CloudDraftState(CloudDraft(peer, text = "pending"), revision = 1, dirty = true))
        val error = org.monogram.core.common.telegram.TelegramError.parse("FUTURE_ERROR").copy(
            httpCode = 500, kind = org.monogram.core.common.telegram.TelegramError.Kind.Generic
        )
        val sync = CloudDraftSync(
            cache,
            { Outcome.Ok(emptyList()) },
            { Outcome.Err("fixture", telegram = error) })
        assertEquals(2_000L, sync.syncOnce())
        assertEquals(error, cache.draftSync.observe(PeerId(8)).first().error)
        assertTrue(cache.states.value.single().dirty)
    }

    @Test
    fun stoppingAnInFlightSaveDoesNotAcknowledgeOrLeakItsJob() = runBlocking {
        val cache =
            Cache(CloudDraftState(CloudDraft(peer, text = "pending"), revision = 1, dirty = true))
        val entered = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = CloudDraftSync(cache, { Outcome.Ok(emptyList()) }, {
            entered.complete(Unit)
            awaitCancellation()
        })
        try {
            sync.start(scope)
            withTimeout(5_000) { entered.await() }
            sync.stop()
            assertTrue(cache.states.value.single().dirty)
        } finally {
            scope.cancel()
        }
    }
}
