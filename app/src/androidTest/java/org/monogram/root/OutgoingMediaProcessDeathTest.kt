package org.monogram.root

import android.content.Context
import android.os.Process
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.database.*
import org.monogram.core.database.entity.*
import org.monogram.core.models.*

/** Host invokes prepare, force-stops the test target, then invokes resume without reinstalling. */
class OutgoingMediaProcessDeathTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory: File get() = File(context.noBackupFilesDir, "media-process-proof")
    private fun database() =
        Room.databaseBuilder(context, MonogramDatabase::class.java, "media-process-proof.db")
            .build()

    private fun phase(expected: String) {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("mediaProcessPhase") == expected
        )
    }

    private fun attempt() {
        val file = File(directory, "attempts")
        val previous = if (file.exists()) file.readText().trim().toInt() else 0
        file.writeText((previous + 1).toString())
    }

    @Test
    fun prepareInFlightAlbum(): Unit = runBlocking {
        phase("prepare")
        context.deleteDatabase("media-process-proof.db")
        directory.deleteRecursively(); directory.mkdirs()
        val db = database()
        val files = OutgoingMediaFiles(File(directory, "owned"))
        val cache = OfflineWarmup(db, outgoingMediaFiles = files)
        val original =
            File(directory, "synthetic-source").apply { writeText("synthetic-upload-bytes") }
        val uploads = (0..1).map { index ->
            val id = 123L + index
            OutgoingMediaUpload(
                original.absolutePath, "application/octet-stream",
                MessageEntity(
                    chatId = 7,
                    id = -37 - index,
                    senderId = 7,
                    text = "synthetic caption",
                    date = 100,
                    outgoing = true,
                    pending = true,
                    randomId = id,
                    mediaCacheKey = null,
                    mediaKind = "document",
                    fileName = "fixture-$index.bin",
                    replyToMsgId = 42,
                    replyToTopId = 99,
                    forumTopic = true,
                    entitiesJson = """[{"kind":"bold","offset":0,"length":3}]"""
                )
            )
        }
        db.metaDao().upsert(MetaEntity("fixture.identity", "synthetic-account"))
        cache.retainAndEnqueueOutgoingMedia(
            OutgoingMediaBatchEntity(123, 7, 100, true, 0x7ffffffe, true),
            uploads, 128, cache.outgoingAccountEpoch()
        )
        original.delete()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val worker =
            OutgoingTextSync(cache, { error("unexpected text send") }, sendMedia = { send ->
                send.items.forEach {
                    assertEquals(
                        "synthetic-upload-bytes",
                        files.verifiedFile(it.source).readText()
                    )
                }
                assertTrue(send.batch.silent && send.batch.album)
                assertEquals(0x7ffffffe, send.batch.scheduleDate)
                attempt()
                File(directory, "ready").writeText(Process.myPid().toString())
                awaitCancellation()
            })
        worker.start(scope)
        try {
            // Host must terminate this process before cancellation can mark the claim uncertain.
            withTimeout(90_000) { awaitCancellation() }
        } finally {
            worker.stop(); scope.cancel(); db.close()
        }
    }

    @Test
    fun resumeWithoutAutomaticReplayThenExplicitlyRetryAndSettle() = runBlocking {
        phase("resume")
        val oldPid = File(directory, "ready").readText().trim().toInt()
        assertNotEquals(oldPid, Process.myPid())
        val db = database()
        val files = OutgoingMediaFiles(File(directory, "owned"))
        val cache = OfflineWarmup(db, outgoingMediaFiles = files)
        val persisted = db.outgoingMediaSendDao().get(123)!!
        assertEquals(OutgoingSendPhase.IN_FLIGHT, persisted.batch.phase)
        assertEquals(
            listOf(123L, 124L),
            persisted.items.sortedBy { it.position }.map { it.randomId })
        assertEquals("synthetic-account", db.metaDao().get("fixture.identity")?.value)
        assertFalse(File(directory, "synthetic-source").exists())
        persisted.items.forEach {
            assertEquals(
                "synthetic-upload-bytes",
                files.verifiedFile(it.source).readText()
            )
        }
        val attempted = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val worker =
            OutgoingTextSync(cache, { error("unexpected text send") }, sendMedia = { send ->
                assertEquals(
                    persisted.items.sortedBy { it.position },
                    send.items.sortedBy { it.position })
                attempt(); attempted.complete(Unit); Outcome.Ok(Unit)
            })
        try {
            worker.start(scope)
            withTimeout(5_000) {
                cache.observeOutgoingMediaSends()
                    .first { it.single().batch.phase == OutgoingSendPhase.UNCERTAIN }
            }
            worker.requestResume()
            worker.syncOnce()
            assertEquals("1", File(directory, "attempts").readText())
            assertFalse(attempted.isCompleted)
            cache.retryOutgoingMediaSend(123)
            withTimeout(5_000) { attempted.await() }
            assertEquals("2", File(directory, "attempts").readText())
            val results = persisted.items.sortedBy { it.position }.mapIndexed { index, item ->
                item.randomId to
                        OutgoingMessageResult.Scheduled(
                            ScheduledMessage(
                                item.message.copy(
                                    id = 37 + index,
                                    pending = false,
                                    mediaCacheKey = "synthetic-remote-${37 + index}",
                                    thumbCacheKey = null
                                ).toModel(), false
                            )
                        )
            }
            assertTrue(cache.applyDurableBatch(5, 1, null, emptyMap()) {
                cache.applyOutgoingMediaResults(123, results, cache.outgoingAccountEpoch(), 5, 1)
            })
            files.release(persisted.items.map { it.source })
            assertNull(db.outgoingMediaSendDao().get(123))
            assertEquals(listOf(37, 38), db.scheduledMessageDao().forChat(7).map { it.message.id })
            assertTrue(
                db.scheduledMessageDao().forChat(7)
                    .all { it.message.mediaCacheKey?.startsWith("synthetic-remote-") == true })
            assertTrue(db.messageDao().byIds(7, listOf(37, 38)).isEmpty())
            assertTrue(
                cache.applyDurableBatch(
                    5,
                    1,
                    null,
                    emptyMap()
                ) { error("duplicate receipt replayed") })
            assertTrue(File(directory, "owned").listFiles().orEmpty().isEmpty())
            File(
                directory,
                "verified"
            ).writeText("oldPid=$oldPid newPid=${Process.myPid()} attempts=2 queue=2")
        } finally {
            worker.stop(); scope.cancel(); db.close()
        }
    }
}
