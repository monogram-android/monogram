package org.monogram.root

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.monogram.core.common.Outcome
import org.monogram.core.database.*
import org.monogram.core.database.entity.*
import org.monogram.core.models.*
import org.monogram.network.bridge.message.MessageOps
import java.io.File
import java.lang.reflect.Proxy

class OutgoingMediaSyncTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private fun media(
        source: RetainedMediaSource = RetainedMediaSource(
            "00000000-0000-0000-0000-000000000001.bin",
            3,
            "0".repeat(64)
        )
    ) = OutgoingMediaSend(
        OutgoingMediaBatchEntity(123, 7, 100, true, 0x7ffffffe, false),
        listOf(
            OutgoingMediaItemEntity(
                123, 123, 0, "image/png", source,
                MessageEntity(
                    chatId = 7,
                    id = -37,
                    senderId = 7,
                    text = "fixture",
                    date = 100,
                    outgoing = true,
                    mediaCacheKey = "local:retained",
                    mediaKind = "photo",
                    fileName = "fixture.png",
                    pending = true,
                    randomId = 123,
                    replyToMsgId = 42,
                    replyToTopId = 99,
                    forumTopic = true,
                    entitiesJson = """[{"kind":"bold","offset":0,"length":3}]"""
                )
            )
        )
    )

    private class Cache(send: OutgoingMediaSend) : OfflineWarmup() {
        val rows = MutableStateFlow(listOf(send))
        val textRows = MutableStateFlow(emptyList<OutgoingTextSendEntity>())
        override fun observeOutgoingTextSends() = textRows
        override suspend fun outgoingTextSends() = textRows.value
        var deadline = 0L
        override fun observeOutgoingMediaSends() = rows
        override suspend fun outgoingMediaSends() = rows.value
        override suspend fun outgoingSendBlockedUntil() = deadline
        override suspend fun blockOutgoingSendsUntil(deadline: Long) {
            this.deadline = maxOf(this.deadline, deadline)
        }

        override suspend fun claimOutgoingMediaSend(batchId: Long): Boolean {
            val row = rows.value.singleOrNull() ?: return false
            if (row.batch.phase != OutgoingSendPhase.READY) return false
            rows.value =
                listOf(row.copy(batch = row.batch.copy(phase = OutgoingSendPhase.IN_FLIGHT)))
            return true
        }

        override suspend fun markOutgoingMediaSendUncertain(batchId: Long) {
            rows.value =
                rows.value.map { it.copy(batch = it.batch.copy(phase = OutgoingSendPhase.UNCERTAIN)) }
        }

        override suspend fun markOutgoingMediaSendFailed(batchId: Long) {
            rows.value =
                rows.value.map { it.copy(batch = it.batch.copy(phase = OutgoingSendPhase.FAILED)) }
        }

        override suspend fun recoverInterruptedMediaSends() {
            rows.value = rows.value.map {
                if (it.batch.phase == OutgoingSendPhase.IN_FLIGHT) it.copy(
                    batch = it.batch.copy(phase = OutgoingSendPhase.UNCERTAIN)
                ) else it
            }
        }
    }

    @Test
    fun missingRetainedMediaFailsLocallyWithoutCallingTelegram() = runTest {
        val cache = Cache(media())
        val files = OutgoingMediaFiles(File(temporary.root, "owned"))
        val senderCache = OfflineWarmup(outgoingMediaFiles = files)
        val client = Proxy.newProxyInstance(
            MessageOps::class.java.classLoader,
            arrayOf(MessageOps::class.java)
        ) { _, _, _ ->
            error("missing attachment must not reach Telegram")
        } as MessageOps
        val worker = OutgoingTextSync(cache, { error("text unexpected") }, sendMedia = {
            sendRetainedMedia(senderCache, client, it)
        })
        worker.syncOnce()
        assertEquals(OutgoingSendPhase.FAILED, cache.rows.value.single().batch.phase)
        worker.syncOnce()
        assertEquals(OutgoingSendPhase.FAILED, cache.rows.value.single().batch.phase)
    }

    @Test
    fun mediaSuccessIsClaimedOnceAndWaitsForDurableReceipts() = runTest {
        val cache = Cache(media())
        var calls = 0
        val worker = OutgoingTextSync(
            cache,
            { error("text unexpected") },
            sendMedia = { calls++; Outcome.Ok(Unit) })
        worker.syncOnce(); worker.requestResume(); worker.syncOnce()
        assertEquals(1, calls)
        assertEquals(OutgoingSendPhase.IN_FLIGHT, cache.rows.value.single().batch.phase)
    }

    @Test
    fun floodWaitAppliesToMediaAcrossWorkerRecreationWithoutReplay() = runTest {
        val cache = Cache(media())
        cache.textRows.value = listOf(
            OutgoingTextSendEntity(
                999,
                media().items.single().message.copy(
                    id = -99,
                    randomId = 999,
                    date = 200,
                    mediaKind = null
                ),
                silent = false, noWebpage = true, scheduleDate = null, webpageUrl = null
            )
        )
        val worker = OutgoingTextSync(
            cache,
            { error("text unexpected") },
            wallMillis = { 1000 },
            elapsedMillis = { 0 },
            sendMedia = { Outcome.Err("FLOOD_WAIT_10") })
        assertEquals(10_000L, worker.syncOnce())
        assertEquals(OutgoingSendPhase.UNCERTAIN, cache.rows.value.single().batch.phase)
        val recreated = OutgoingTextSync(
            cache,
            { error("text unexpected") },
            wallMillis = { 1000 },
            elapsedMillis = { 0 },
            sendMedia = { error("must not replay") })
        assertEquals(10_000L, recreated.syncOnce())
    }

    @Test
    fun stopAwaitsMediaCancellationAndPreservesUncertainty() = runTest {
        val cache = Cache(media())
        val entered = CompletableDeferred<Unit>()
        val worker = OutgoingTextSync(
            cache,
            { error("text unexpected") },
            sendMedia = { entered.complete(Unit); awaitCancellation() })
        worker.start(this)
        entered.await()
        worker.stop()
        assertEquals(OutgoingSendPhase.UNCERTAIN, cache.rows.value.single().batch.phase)
    }

    @Test
    fun interruptedMediaRecoveryDoesNotAutomaticallyReplay() = runTest {
        val row = media()
        val cache = Cache(row.copy(batch = row.batch.copy(phase = OutgoingSendPhase.IN_FLIGHT)))
        val worker = OutgoingTextSync(
            cache,
            { error("text unexpected") },
            sendMedia = { error("must not replay") })
        worker.start(this)
        testScheduler.runCurrent()
        assertEquals(OutgoingSendPhase.UNCERTAIN, cache.rows.value.single().batch.phase)
        worker.stop()
    }

    @Test
    fun senderUsesVerifiedRetainedBytesAndImmutableOptions() = runBlocking {
        val files = OutgoingMediaFiles(File(temporary.root, "owned"))
        val original = temporary.newFile().apply { writeText("abc") }
        val source = files.retain(listOf(original), 3).single()
        original.delete()
        var calls = 0
        val client = Proxy.newProxyInstance(
            MessageOps::class.java.classLoader,
            arrayOf(MessageOps::class.java)
        ) { _, method, args ->
            check(method.name == "sendUploadedMediaWithOptions")
            calls++
            val item = args[1] as UploadItem
            assertEquals("abc", File(item.path).readText())
            assertEquals(123L, item.randomId)
            assertEquals("fixture", item.caption)
            assertEquals(1, item.captionEntities.size)
            assertEquals(MessageSendOptions(silent = true, scheduleDate = 0x7ffffffe), args[2])
            assertEquals(42, args[3]); assertEquals(99, args[4])
            Outcome.Ok(
                OutgoingMessageResult.Sent(
                    media(source).items.single().message.copy(id = 37).toModel()
                )
            )
        } as MessageOps
        val cache = OfflineWarmup(outgoingMediaFiles = files)
        assertTrue(sendRetainedMedia(cache, client, media(source)) is Outcome.Ok)
        assertEquals(1, calls)
        File(temporary.root, "owned/${source.name}").writeText("bad")
        try {
            sendRetainedMedia(cache, client, media(source)); fail("changed payload accepted")
        } catch (_: java.io.IOException) {
        }
        assertEquals(1, calls)
        cache.deactivateOutgoingAccount()
        try {
            sendRetainedMedia(cache, client, media(source)); fail("inactive account accepted")
        } catch (_: OutgoingPreparationException) {
        }
        assertEquals(1, calls)
    }
}
