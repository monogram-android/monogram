package org.monogram.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.monogram.core.database.entity.MessageEntity
import org.monogram.core.database.entity.OutgoingSendPhase
import org.monogram.core.database.entity.OutgoingTextSendEntity
import org.monogram.core.models.OutgoingMessageResult
import org.monogram.core.models.ScheduledMessage
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OutgoingTextPersistenceTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var database: MonogramDatabase

    @Before
    fun open() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "outbox-test-${UUID.randomUUID()}.db"
        reopen()
    }

    private fun reopen() {
        database = Room.databaseBuilder(context, MonogramDatabase::class.java, name).build()
    }

    @After
    fun close() {
        database.close(); context.deleteDatabase(name)
    }

    private fun row(date: Int? = 0x7ffffffe) = OutgoingTextSendEntity(
        randomId = 123,
        message = MessageEntity(
            chatId = 7,
            id = -37,
            senderId = 7,
            text = "fixture",
            date = 100,
            outgoing = true,
            mediaCacheKey = null,
            mediaKind = null,
            pending = true,
            randomId = 123,
            entitiesJson = """[{"kind":"bold","offset":0,"length":3}]""",
            replyToMsgId = 42,
            replyToTopId = 99,
            forumTopic = true
        ),
        silent = true, noWebpage = true, scheduleDate = date, webpageUrl = "https://example.com",
    )

    @Test
    fun localFailureSurvivesReopenAndRequiresExplicitRetry() = runBlocking {
        val dao = database.outgoingTextSendDao()
        dao.enqueue(row())
        assertEquals(1, dao.claim(123))
        assertEquals(1, dao.markFailed(123))
        database.close(); reopen()
        val restored = database.outgoingTextSendDao()
        restored.recoverInterrupted()
        assertEquals(OutgoingSendPhase.FAILED, restored.get(123)?.phase)
        assertEquals(0, restored.claim(123))
        assertEquals(1, restored.retry(123))
        assertEquals(1, restored.claim(123))
        assertEquals(123L, restored.get(123)?.message?.randomId)
    }

    @Test
    fun diceOutboxRetainsIdentityAcrossReopenAndSettlesOnlyFromAuthoritativeReceipt() =
        runBlocking {
            val base = row(null)
            val dice = base.copy(
                message = base.message.copy(
                    text = null,
                    entitiesJson = null,
                    mediaKind = "dice",
                    fileName = """{"e":"🎲","v":0}"""
                ),
                noWebpage = false, webpageUrl = null
            )
            val warmup = OfflineWarmup(database)
            warmup.enqueueOutgoingTextSend(dice, consumeDraft = false)
            assertEquals(1, database.outgoingTextSendDao().claim(123))
            database.close(); reopen()
            assertEquals(
                dice.copy(phase = OutgoingSendPhase.IN_FLIGHT),
                database.outgoingTextSendDao().get(123)
            )
            database.outgoingTextSendDao().recoverInterrupted()
            assertEquals(
                OutgoingSendPhase.UNCERTAIN,
                database.outgoingTextSendDao().get(123)?.phase
            )
            assertEquals(0, database.outgoingTextSendDao().claim(123))
            assertEquals(1, database.outgoingTextSendDao().retry(123))
            assertEquals(1, database.outgoingTextSendDao().claim(123))
            val result =
                dice.message.copy(id = 37, pending = false, fileName = """{"e":"🎲","v":4}""")
                    .toModel()
            OfflineWarmup(database).applyOutgoingResult(123, OutgoingMessageResult.Sent(result))
            assertNull(database.outgoingTextSendDao().get(123))
            val delivered = database.messageDao().byIds(7, listOf(37)).single().toModel()
            assertEquals(4, org.monogram.core.models.ServiceMedia.dice(delivered.fileName)?.value)
        }

    @Test
    fun scheduledSnapshotApiRejectsPushRacesAndOldAccountEpochs() = runBlocking {
        val warmup = OfflineWarmup(database)
        val peer = org.monogram.core.models.PeerId(7)
        val first = ScheduledMessage(row().message.copy(id = 37, pending = false).toModel())
        val second = ScheduledMessage(first.message.copy(id = first.message.id.copy(id = 38)))
        warmup.applyScheduledMessage(first)
        val epoch = warmup.outgoingAccountEpoch()
        val revision = warmup.scheduledQueueRevision(peer, epoch)
        warmup.applyScheduledMessage(second)
        assertFalse(warmup.replaceScheduledMessagesIfCurrent(peer, revision, listOf(first), epoch))
        assertEquals(
            listOf(37, 38),
            database.scheduledMessageDao().forChat(7).map { it.message.id })
        val fresh = warmup.scheduledQueueRevision(peer, epoch)
        assertTrue(warmup.replaceScheduledMessagesIfCurrent(peer, fresh, listOf(second), epoch))
        warmup.clearAccountCache()
        warmup.activateOutgoingAccount()
        try {
            warmup.replaceScheduledMessagesIfCurrent(peer, fresh, listOf(first), epoch)
            fail("old account snapshot accepted")
        } catch (_: IllegalStateException) {
        }
        assertTrue(database.scheduledMessageDao().forChat(7).isEmpty())
        try {
            warmup.blockScheduledQueueUntil(1000, epoch)
            fail("old account flood deadline accepted")
        } catch (_: IllegalStateException) {
        }
        assertEquals(0L, warmup.outgoingSendBlockedUntil())
        warmup.blockScheduledQueueUntil(1000, warmup.outgoingAccountEpoch())
        assertEquals(1000L, warmup.outgoingSendBlockedUntil())
    }

    @Test
    fun enqueueAndDraftConsumptionCommitTogetherWithoutErasingNewerEdits() = runBlocking {
        val warmup = OfflineWarmup(database)
        val draft = org.monogram.core.models.CloudDraft(
            org.monogram.core.models.PeerId(7),
            99,
            text = "fixture"
        )
        val epoch = warmup.outgoingAccountEpoch()
        warmup.saveLocalCloudDraft(draft)
        val captured = warmup.captureOutgoingTextDraft(draft.chatId, 99, epoch)
        assertTrue(warmup.enqueueOutgoingTextSend(row(), epoch, captured))
        database.close(); reopen()
        val restored = OfflineWarmup(database)
        assertNotNull(database.outgoingTextSendDao().get(123))
        assertEquals("", restored.cloudDraftState(draft.chatId, 99).draft.text)
        assertTrue(restored.cloudDraftState(draft.chatId, 99).dirty)
        database.outgoingTextSendDao().acknowledge(123, 7)
        restored.saveLocalCloudDraft(draft)
        val older =
            restored.captureOutgoingTextDraft(draft.chatId, 99, restored.outgoingAccountEpoch())
        restored.saveLocalCloudDraft(draft)
        assertFalse(restored.enqueueOutgoingTextSend(row(), restored.outgoingAccountEpoch(), older))
        assertEquals("fixture", restored.cloudDraftState(draft.chatId, 99).draft.text)
        database.outgoingTextSendDao().acknowledge(123, 7)
        val supported =
            restored.captureOutgoingTextDraft(draft.chatId, 99, restored.outgoingAccountEpoch())
        restored.applyCloudDraft(draft.copy(hasUnsupportedContent = true))
        try {
            restored.enqueueOutgoingTextSend(row(), restored.outgoingAccountEpoch(), supported)
            fail("new unsupported content was sent")
        } catch (_: IllegalStateException) {
        }
        assertNull(database.outgoingTextSendDao().get(123))
        assertTrue(restored.cloudDraftState(draft.chatId, 99).draft.hasUnsupportedContent)
    }

    @Test
    fun delayedComposerWritesCannotCrossLogoutOrAccountReplacement() = runBlocking {
        val warmup = OfflineWarmup(database)
        val epoch = warmup.outgoingAccountEpoch()
        warmup.deactivateOutgoingAccount()
        suspend fun rejected(stale: Long) {
            try {
                warmup.enqueueOutgoingTextSend(row(), stale); fail("stale account write accepted")
            } catch (_: IllegalStateException) {
            }
        }
        rejected(epoch)
        warmup.activateOutgoingAccount()
        rejected(epoch)
        val active = warmup.outgoingAccountEpoch()
        warmup.enqueueOutgoingTextSend(row(), active)
        assertNotNull(database.outgoingTextSendDao().get(123))
        warmup.clearAccountCache()
        rejected(active)
        warmup.activateOutgoingAccount()
        rejected(active)
        assertNull(database.outgoingTextSendDao().get(123))
        warmup.enqueueOutgoingTextSend(row(), warmup.outgoingAccountEpoch())
        assertNotNull(database.outgoingTextSendDao().get(123))
    }

    @Test
    fun payloadOptionsAndRandomIdsSurviveRestartWithoutAutomaticUncertainReplay() = runBlocking {
        for (date in listOf(null, 0x7ffffffe)) {
            val expected = row(date)
            database.outgoingTextSendDao().enqueue(expected)
            assertEquals(1, database.outgoingTextSendDao().claim(123))
            assertEquals(0, database.outgoingTextSendDao().claim(123))
            database.close(); reopen()
            val dao = database.outgoingTextSendDao()
            assertEquals(expected.copy(phase = OutgoingSendPhase.IN_FLIGHT), dao.get(123))
            assertEquals(1, dao.recoverInterrupted())
            assertEquals(0, dao.claim(123))
            assertEquals(expected.copy(phase = OutgoingSendPhase.UNCERTAIN), dao.get(123))
            assertEquals(1, dao.retry(123))
            assertEquals(expected, dao.get(123))
            dao.acknowledge(123, 7)
        }
    }

    @Test
    fun duplicateEnqueueDoesNotResetFlightOrPermitPayloadMutation() = runBlocking {
        val dao = database.outgoingTextSendDao()
        val original = row()
        dao.enqueue(original); dao.claim(123); dao.enqueue(original)
        assertEquals(0, dao.claim(123))
        try {
            dao.enqueue(original.copy(message = original.message.copy(text = "changed")))
            fail("random ID payload changed")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(original.copy(phase = OutgoingSendPhase.IN_FLIGHT), dao.get(123))
    }

    @Test
    fun authoritativeReceiptAndOutboxRemovalRollbackAndCommitTogether() = runBlocking {
        val dao = database.outgoingTextSendDao()
        val original = row()
        dao.enqueue(original)
        val result = OutgoingMessageResult.Scheduled(
            ScheduledMessage(
                original.message.copy(
                    id = 37,
                    pending = false
                ).toModel()
            )
        )
        val warmup = OfflineWarmup(database)
        try {
            warmup.applyDurableBatch(5, 12, null, emptyMap()) {
                warmup.applyOutgoingResult(123, result)
                error("synthetic receipt rollback")
            }
        } catch (_: IllegalStateException) {
        }
        assertEquals(original, dao.get(123))
        assertTrue(database.scheduledMessageDao().forChat(7).isEmpty())
        warmup.applyDurableBatch(5, 12, null, emptyMap()) {
            warmup.applyOutgoingResult(
                123,
                result
            )
        }
        assertNull(dao.get(123))
        assertEquals(37, database.scheduledMessageDao().forChat(7).single().message.id)
        assertTrue(database.messageDao().byIds(7, listOf(37)).isEmpty())
    }

    @Test
    fun floodDeadlinePersistsAndCannotBeShortenedAndClearsWithTheAccount() = runBlocking {
        val cache = OfflineWarmup(database)
        cache.blockOutgoingSendsUntil(130_000)
        cache.blockOutgoingSendsUntil(120_000)
        database.close(); reopen()
        val restored = OfflineWarmup(database)
        assertEquals(130_000L, restored.outgoingSendBlockedUntil())
        restored.clearAccountCache()
        assertEquals(0L, restored.outgoingSendBlockedUntil())
    }

    @Test
    fun foreignPeerReceiptCannotRemoveOutboxOrInsertHistory() = runBlocking {
        val original = row(null)
        database.outgoingTextSendDao().enqueue(original)
        val result = OutgoingMessageResult.Sent(
            original.message.copy(chatId = 8, id = 37, pending = false).toModel()
        )
        try {
            OfflineWarmup(database).applyOutgoingResult(123, result)
            fail("foreign peer receipt accepted")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(original, database.outgoingTextSendDao().get(123))
        assertTrue(database.messageDao().byIds(8, listOf(37)).isEmpty())
    }
}
