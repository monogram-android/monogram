package org.monogram.core.database

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

class RoomBoundsTest {
    private fun message(id: Int, text: String = "m") = Message(
        MessageId(PeerId(1), id),
        senderId = null,
        text = text,
        date = 1L,
        outgoing = false,
    )

    @Test
    fun batchesStopAtOneHundredRowsAndAQuarterMegabyte() {
        val byRows = chunkMessages(List(250) { message(it) })
        assertEquals(3, byRows.size)
        assertEquals(100, byRows[0].size)
        assertEquals(50, byRows[2].size)

        val heavy = message(1, text = "x".repeat(80 * 1024))
        val byBytes = chunkMessages(List(10) { heavy.copy(id = MessageId(PeerId(1), it)) })
        assertTrue(byBytes.size > 1)
        byBytes.forEach { batch ->
            val bytes = batch.sumOf { it.estimatedSerializedBytes() }
            assertTrue(batch.size == 1 || bytes <= MESSAGE_BATCH_BYTES)
        }
    }

    @Test
    fun aSingleOversizedRowIsNotSplit() {
        val huge = message(1, text = "x".repeat(MESSAGE_BATCH_BYTES))
        assertEquals(1, chunkMessages(listOf(huge)).single().size)
    }

    @Test
    fun foregroundWorkPassesQueuedMaintenance() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        runBlocking {
            withContext(RoomLane.maintenance()) {
                DatabaseProvider.roomExecutor.execute {
                    order += "maintenance-running"
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
        }
        check(started.await(5, TimeUnit.SECONDS))
        val done = CountDownLatch(2)
        runBlocking {
            withContext(RoomLane.maintenance()) {
                DatabaseProvider.roomExecutor.execute {
                    order += "maintenance-queued"
                    done.countDown()
                }
            }
            DatabaseProvider.roomExecutor.execute {
                order += "foreground"
                done.countDown()
            }
        }
        release.countDown()
        check(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("maintenance-running", "foreground", "maintenance-queued"), order)
    }

    @Test
    fun nestedRoomHopInsideABlockingTransactionCompletes() {
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        Thread {
            try {
                runBlocking {
                    DatabaseProvider.onRoom {
                        suspendCancellableCoroutine { outer ->
                            DatabaseProvider.roomExecutor.execute {
                                runBlocking {
                                    DatabaseProvider.onRoom {
                                        outer.resume(Unit)
                                    }
                                }
                            }
                        }
                    }
                }
                done.countDown()
            } catch (thrown: Throwable) {
                error.set(thrown)
                done.countDown()
            }
        }.apply { isDaemon = true }.start()
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertNull(error.get())
    }
}
