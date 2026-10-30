package org.monogram.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.monogram.core.database.entity.*
import org.monogram.core.models.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OutgoingMediaPersistenceTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var db: MonogramDatabase
    private lateinit var filesDirectory: java.io.File
    @Before
    fun open() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "media-outbox-${UUID.randomUUID()}.db"
        filesDirectory =
            java.io.File(context.noBackupFilesDir, "media-fixture-${UUID.randomUUID()}")
                .apply { mkdirs() }
        reopen()
    }

    private fun reopen() {
        db = Room.databaseBuilder(context, MonogramDatabase::class.java, name).build()
    }

    @After
    fun close() {
        db.close(); context.deleteDatabase(name); filesDirectory.deleteRecursively()
    }

    private fun send() = OutgoingMediaSend(
        OutgoingMediaBatchEntity(123, 7, 100, true, 0x7ffffffe, true),
        (0..1).map { index ->
            val random = 123L + index
            OutgoingMediaItemEntity(
                random, 123, index, "image/png",
                RetainedMediaSource(UUID.randomUUID().toString() + ".bin", 3, "0".repeat(64)),
                MessageEntity(
                    chatId = 7,
                    id = -37 - index,
                    senderId = 7,
                    text = "fixture",
                    date = 100,
                    outgoing = true,
                    mediaCacheKey = "local:retained",
                    mediaKind = "photo",
                    pending = true,
                    randomId = random,
                    replyToMsgId = 42,
                    replyToTopId = 99,
                    forumTopic = true,
                    entitiesJson = """[{"kind":"bold","offset":0,"length":3}]"""
                )
            )
        })

    private suspend inline fun <reified T : Throwable> rejects(block: suspend () -> Unit) {
        try {
            block(); fail("expected rejection")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }

    @Test
    fun immutablePayloadAndClaimSurviveReopenAndNeedExplicitRetry() = runBlocking {
        val send = send()
        db.outgoingMediaSendDao().enqueue(send)
        db.outgoingMediaSendDao().enqueue(send.copy(items = send.items.reversed()))
        assertEquals(1, db.outgoingMediaSendDao().claim(123))
        assertEquals(0, db.outgoingMediaSendDao().claim(123))
        db.close(); reopen()
        val dao = db.outgoingMediaSendDao()
        val restored = dao.get(123)!!
        assertEquals(send.items, restored.items.sortedBy { it.position })
        assertTrue(restored.batch.silent)
        assertEquals(0x7ffffffe, restored.batch.scheduleDate)
        assertEquals(1, dao.recoverInterrupted())
        assertEquals(0, dao.claim(123))
        rejects<IllegalArgumentException> { dao.enqueue(send.copy(batch = send.batch.copy(silent = false))) }
        assertEquals(1, dao.retry(123))
        assertEquals(1, dao.claim(123))
        assertEquals(1, dao.markFailed(123))
        db.close(); reopen()
        val failed = db.outgoingMediaSendDao()
        assertEquals(0, failed.recoverInterrupted())
        assertEquals(OutgoingSendPhase.FAILED, failed.get(123)?.batch?.phase)
        assertEquals(send.items, failed.get(123)!!.items.sortedBy { it.position })
        assertEquals(0, failed.claim(123))
        assertEquals(1, failed.retry(123))
        assertEquals(1, failed.claim(123))
    }

    @Test
    fun collidingItemRollsBackWholeNewBatch() = runBlocking {
        val first = send()
        val dao = db.outgoingMediaSendDao()
        dao.enqueue(first)
        val other = first.copy(
            batch = first.batch.copy(batchId = 999),
            items = first.items.mapIndexed { index, item ->
                val randomId = if (index == 0) 999L else item.randomId
                item.copy(
                    batchId = 999,
                    randomId = randomId,
                    message = item.message.copy(randomId = randomId)
                )
            })
        rejects<android.database.sqlite.SQLiteConstraintException> { dao.enqueue(other) }
        assertNull(dao.get(999))
        assertEquals(1, dao.all().size)
        val duplicateSources = other.copy(
            batch = other.batch.copy(batchId = 1999),
            items = other.items.mapIndexed { index, item ->
                val randomId = 1999L + index
                item.copy(
                    batchId = 1999,
                    randomId = randomId,
                    message = item.message.copy(randomId = randomId)
                )
            })
        rejects<android.database.sqlite.SQLiteConstraintException> { dao.enqueue(duplicateSources) }
        assertNull(dao.get(1999))
        rejects<IllegalArgumentException> { dao.acknowledge(123, 7, setOf(123)) }
        rejects<IllegalArgumentException> { dao.acknowledge(123, 8, setOf(123, 124)) }
        assertEquals(2, dao.get(123)!!.items.size)
    }

    @Test
    fun invalidAlbumShapesLeaveNoPartialAdmission() = runBlocking {
        val send = send()
        val dao = db.outgoingMediaSendDao()
        val malformed = listOf(
            send.copy(items = send.items.map { it.copy(position = 0) }),
            send.copy(items = send.items.map { it.copy(source = send.items.first().source) }),
            send.copy(
                items = listOf(
                    send.items.first(),
                    send.items.last()
                        .copy(message = send.items.last().message.copy(mediaKind = "document"))
                )
            ),
            send.copy(batch = send.batch.copy(scheduleDate = Int.MAX_VALUE)),
        )
        malformed.forEach { rejects<IllegalArgumentException> { dao.enqueue(it) } }
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun admissionAndDraftConsumptionRespectAccountEpochAndNewerEdits() = runBlocking {
        val cache = OfflineWarmup(db)
        val draft = CloudDraft(PeerId(7), 99, text = "fixture")
        cache.saveLocalCloudDraft(draft)
        val epoch = cache.outgoingAccountEpoch()
        val captured = cache.captureOutgoingTextDraft(PeerId(7), 99, epoch)
        val send = send()
        assertTrue(cache.enqueueOutgoingMediaSend(send, epoch, captured))
        assertEquals("", cache.cloudDraftState(PeerId(7), 99).draft.text)
        cache.clearAccountCache(); cache.activateOutgoingAccount()
        rejects<IllegalStateException> { cache.enqueueOutgoingMediaSend(send, epoch) }
        rejects<IllegalStateException> { cache.applyOutgoingMediaResults(123, emptyList(), epoch) }
        assertTrue(db.outgoingMediaSendDao().all().isEmpty())
        cache.saveLocalCloudDraft(draft)
        val currentEpoch = cache.outgoingAccountEpoch()
        val older = cache.captureOutgoingTextDraft(PeerId(7), 99, currentEpoch)
        cache.saveLocalCloudDraft(draft)
        assertFalse(cache.enqueueOutgoingMediaSend(send, currentEpoch, older))
        assertEquals("fixture", cache.cloudDraftState(PeerId(7), 99).draft.text)
    }

    @Test
    fun retainedAdmissionSurvivesOriginalDeletionDatabaseReopenAndRecovery() = runBlocking {
        val original = send()
        val sources = original.items.mapIndexed { index, _ ->
            java.io.File(filesDirectory, "original-$index").apply { writeText("abc") }
        }
        val owned = java.io.File(filesDirectory, "owned")
        val cache = OfflineWarmup(db, outgoingMediaFiles = OutgoingMediaFiles(owned))
        val draft = CloudDraft(PeerId(7), 99, text = "fixture")
        cache.saveLocalCloudDraft(draft)
        val epoch = cache.outgoingAccountEpoch()
        val captured = cache.captureOutgoingTextDraft(PeerId(7), 99, epoch)
        val admitted = cache.retainAndEnqueueOutgoingMedia(
            original.batch,
            original.items.mapIndexed { index, item ->
                OutgoingMediaUpload(sources[index].absolutePath, item.mimeType, item.message)
            },
            6,
            epoch,
            captured
        )
        assertTrue(admitted.draftConsumed)
        sources.forEach { it.delete() }
        db.close(); reopen()
        val files = OutgoingMediaFiles(owned)
        val restored = OfflineWarmup(db, outgoingMediaFiles = files)
        restored.recoverOutgoingMediaFiles(restored.outgoingAccountEpoch())
        val persisted = db.outgoingMediaSendDao().get(123)!!
        persisted.items.forEach { item ->
            assertEquals("abc", files.verifiedFile(item.source).readText())
            assertEquals(
                "local:" + files.verifiedFile(item.source).absolutePath,
                item.message.mediaCacheKey
            )
        }
        assertEquals("", restored.cloudDraftState(PeerId(7), 99).draft.text)
        restored.clearAccountCache()
        assertTrue(owned.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun recoveryWaitsForInProgressCopyAndAdmission() = runBlocking {
        val original = send()
        val source = java.io.File(filesDirectory, "original").apply { writeText("abc") }
        val owned = java.io.File(filesDirectory, "owned")
        val files = OutgoingMediaFiles(owned)
        val cache = OfflineWarmup(db, outgoingMediaFiles = files)
        val epoch = cache.outgoingAccountEpoch()
        val admission = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            cache.retainAndEnqueueOutgoingMedia(
                original.batch,
                original.items.map {
                    OutgoingMediaUpload(
                        source.absolutePath,
                        it.mimeType,
                        it.message
                    )
                }, 6, epoch
            )
        }
        val recovery = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            cache.recoverOutgoingMediaFiles(epoch)
        }
        assertFalse(recovery.isCompleted)
        val result = admission.await()
        recovery.await()
        result.send.items.forEach { assertEquals("abc", files.verifiedFile(it.source).readText()) }
    }

    @Test
    fun rejectedRetainedAdmissionRemovesOnlyUnreferencedCopies() = runBlocking {
        val original = send()
        val source = java.io.File(filesDirectory, "original").apply { writeText("abc") }
        val owned = java.io.File(filesDirectory, "owned")
        val cache = OfflineWarmup(db, outgoingMediaFiles = OutgoingMediaFiles(owned))
        val uploads =
            original.items.map { OutgoingMediaUpload(source.absolutePath, it.mimeType, it.message) }
        cache.retainAndEnqueueOutgoingMedia(
            original.batch,
            uploads,
            6,
            cache.outgoingAccountEpoch()
        )
        val existing = owned.listFiles()!!.map { it.name }.toSet()
        rejects<IllegalArgumentException> {
            cache.retainAndEnqueueOutgoingMedia(
                original.batch.copy(batchId = 999),
                uploads,
                6,
                cache.outgoingAccountEpoch()
            )
        }
        assertEquals(existing, owned.listFiles()!!.map { it.name }.toSet())
        assertEquals("abc", source.readText())
        assertEquals(1, db.outgoingMediaSendDao().all().size)
    }

    @Test
    fun settlementRequiresCompletePeerMatchedResultsAndCommitsWithQueue() = runBlocking {
        val cache = OfflineWarmup(db)
        val send = send()
        cache.enqueueOutgoingMediaSend(send, cache.outgoingAccountEpoch())
        val results = send.items.mapIndexed { index, item ->
            item.randomId to
                    OutgoingMessageResult.Scheduled(
                        ScheduledMessage(
                            item.message.copy(
                                id = 37 + index,
                                pending = false
                            ).toModel(), false
                        )
                    )
        }
        rejects<IllegalArgumentException> {
            cache.applyOutgoingMediaResults(
                123,
                results.take(1),
                cache.outgoingAccountEpoch()
            )
        }
        assertTrue(db.scheduledMessageDao().forChat(7).isEmpty())
        val foreign = results.map { (randomId, result) ->
            randomId to result.copy(
                message = result.message.copy(
                    message = result.message.message.copy(
                        id = result.message.message.id.copy(
                            chatId = PeerId(
                                8
                            )
                        )
                    )
                )
            )
        }
        rejects<IllegalArgumentException> {
            cache.applyOutgoingMediaResults(
                123,
                foreign,
                cache.outgoingAccountEpoch()
            )
        }
        assertTrue(db.scheduledMessageDao().forChat(7).isEmpty())
        rejects<IllegalArgumentException> {
            cache.applyOutgoingMediaResults(
                123,
                listOf(results[0], results[0]),
                cache.outgoingAccountEpoch()
            )
        }
        assertNotNull(db.outgoingMediaSendDao().get(123))
        assertTrue(kotlinx.coroutines.withTimeout(5_000) {
            cache.applyDurableBatch(5, 1, null, emptyMap()) {
                cache.applyOutgoingMediaResults(123, results, cache.outgoingAccountEpoch(), 5, 1)
            }
        })
        assertNull(db.outgoingMediaSendDao().get(123))
        assertNull(db.outgoingMediaSendDao().item(123))
        assertEquals(listOf(37, 38), db.scheduledMessageDao().forChat(7).map { it.message.id })
        cache.applyOutgoingMediaResults(123, results, cache.outgoingAccountEpoch())
        assertEquals(2, db.scheduledMessageDao().forChat(7).size)
    }
}
