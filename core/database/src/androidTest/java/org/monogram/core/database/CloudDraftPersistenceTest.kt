package org.monogram.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.monogram.core.database.entity.MetaEntity
import org.monogram.core.models.CloudDraft
import org.monogram.core.models.PeerId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CloudDraftPersistenceTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var database: MonogramDatabase
    private lateinit var warmup: OfflineWarmup
    private val peer = PeerId(7)

    @Before
    fun open() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "draft-test-${UUID.randomUUID()}.db"
        reopen()
    }

    private fun reopen() {
        database = Room.databaseBuilder(context, MonogramDatabase::class.java, name).build()
        warmup = OfflineWarmup(database)
    }

    @After
    fun close() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test
    fun legacyTextAndStructuredPendingEditsSurviveReopenWithoutChangingIdentity() = runBlocking {
        database.metaDao().upsert(MetaEntity("fixture.identity", "7"))
        database.metaDao().upsert(MetaEntity(draftMetaKey(peer.value, 99), "legacy text"))
        assertEquals("legacy text", warmup.cloudDraftState(peer, 99).draft.text)
        assertTrue(warmup.cloudDraftStates().single().dirty)
        val payload = CloudDraft(
            peer,
            99,
            "local",
            """[{"kind":"bold","offset":0,"length":5}]""",
            42,
            noWebpage = true
        )
        val saved = warmup.saveLocalCloudDraft(payload, markdown = true)
        database.close()
        reopen()
        assertEquals(saved, warmup.cloudDraftState(peer, 99))
        assertTrue(warmup.cloudDraftState(peer, 99).markdown)
        assertTrue(warmup.cloudDraftState(peer, 99).dirty)
        assertFalse(warmup.cloudDraftState(peer, 0).dirty)
        assertEquals("7", database.metaDao().get("fixture.identity")?.value)
        assertNull(database.metaDao().get(draftMetaKey(peer.value, 99)))
    }

    @Test
    fun localEditsAndNewEventsWinAgainstStaleAcknowledgmentsAndFetches() = runBlocking {
        val first = warmup.saveLocalCloudDraft(CloudDraft(peer, text = "first"))
        val second = warmup.saveLocalCloudDraft(CloudDraft(peer, text = "second"))
        warmup.acknowledgeCloudDraft(peer, 0, first.revision)
        assertEquals(second, warmup.cloudDraftState(peer))
        warmup.applyCloudDraft(CloudDraft(peer, text = "remote", date = 30))
        assertEquals("second", warmup.cloudDraftState(peer).draft.text)
        assertTrue(warmup.cloudDraftState(peer).dirty)
        warmup.acknowledgeCloudDraft(peer, 0, second.revision)
        assertFalse(warmup.cloudDraftState(peer).dirty)
        val beforeFetch = warmup.cloudDraftState(peer).revision
        warmup.applyCloudDraft(CloudDraft(peer, text = "new event", date = 40))
        warmup.applyCloudDraft(CloudDraft(peer, text = "stale fetch", date = 40), beforeFetch)
        assertEquals("new event", warmup.cloudDraftState(peer).draft.text)
    }

    @Test
    fun orderedWritesSurviveDisposalAndFinishBeforeAccountCacheClearing() = runBlocking {
        database.metaDao().upsert(MetaEntity("fixture.identity", "7"))
        val writes = (1..20).map { number ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) { warmup.setDraft(peer, text = number.toString()) }
            }
        }
        writes.joinAll()
        assertEquals("20", warmup.draft(peer))
        val disposed = launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { warmup.setDraft(peer, text = "after disposal") }
        }
        disposed.cancel()
        disposed.join()
        assertEquals("after disposal", warmup.draft(peer))
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { warmup.setDraft(peer, text = "pending before logout") }
        }
        val clear = launch(start = CoroutineStart.UNDISPATCHED) { warmup.clearAccountCache() }
        pending.cancel()
        pending.join()
        clear.join()
        assertTrue(warmup.cloudDraftStates().isEmpty())
        assertEquals("7", database.metaDao().get("fixture.identity")?.value)
    }

    @Test
    fun explicitUnsupportedDraftDiscardSurvivesReopenAndRemoteRefresh() = runBlocking {
        val remote =
            CloudDraft(peer, text = "unsupported content", date = 20, hasUnsupportedContent = true)
        warmup.applyCloudDraft(remote)
        val guarded = warmup.saveLocalCloudDraft(CloudDraft(peer, text = "local typing"))
        assertTrue(guarded.draft.hasUnsupportedContent)
        val discarded = warmup.discardLocalCloudDraft(peer)
        assertFalse(discarded.draft.hasUnsupportedContent)
        database.close()
        reopen()
        assertEquals(discarded, warmup.cloudDraftState(peer))
        warmup.applyCloudDraft(remote)
        assertFalse(warmup.cloudDraftState(peer).draft.hasUnsupportedContent)
        assertTrue(warmup.cloudDraftState(peer).discardingUnsupported)
        warmup.acknowledgeCloudDraft(peer, 0, discarded.revision)
        assertFalse(warmup.cloudDraftState(peer).discardingUnsupported)
        assertFalse(warmup.cloudDraftState(peer).dirty)
    }

    @Test
    fun remoteDraftAndReceiptCommitOrRollBackTogether() = runBlocking {
        val remote = CloudDraft(peer, text = "remote", date = 30)
        try {
            warmup.applyDurableBatch(5, 8, null, emptyMap()) {
                warmup.applyCloudDraft(remote)
                error("fixture rollback")
            }
            fail("transaction must fail")
        } catch (_: IllegalStateException) {
            assertFalse(database.updateCursorDao().isBatchApplied(5, 8))
            assertEquals("", warmup.cloudDraftState(peer).draft.text)
        }
        assertTrue(
            warmup.applyDurableBatch(
                5,
                8,
                null,
                emptyMap()
            ) { warmup.applyCloudDraft(remote) })
        assertTrue(database.updateCursorDao().isBatchApplied(5, 8))
        assertEquals(remote, warmup.cloudDraftState(peer).draft)
        assertTrue(
            warmup.applyDurableBatch(
                5,
                8,
                null,
                emptyMap()
            ) { error("replay must not apply twice") })
    }
}
