package org.monogram.core.database

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.PeerId

class CloudDraftSyncMonitorTest {
    private val peer = PeerId(7)

    @Test
    fun peerFailuresAreScopedButAccountFailuresReachAllDrafts() = runBlocking {
        val monitor = CloudDraftSyncMonitor()
        val token = monitor.begin(peer to 9)
        assertTrue(monitor.observe(peer, 9).first().syncing)
        assertFalse(monitor.observe(peer, 10).first().syncing)
        val error = TelegramError.parse("CHAT_WRITE_FORBIDDEN")
        monitor.finish(peer to 9, token, error)
        assertEquals(error, monitor.observe(peer, 9).first().error)
        assertNull(monitor.observe(peer, 10).first().error)
        monitor.fail(TelegramError.parse("FLOOD_WAIT_5"))
        assertEquals(TelegramError.Kind.Flood, monitor.observe(PeerId(8)).first().error?.kind)
    }

    @Test
    fun lateCollectorsReceiveRemainingFloodWaitInsteadOfTheOriginalDuration() = runBlocking {
        var now = 0L
        val monitor = CloudDraftSyncMonitor { now }
        monitor.fail(TelegramError.parse("FLOOD_WAIT_5"))
        now = 2_001
        assertEquals(3, monitor.observe(peer).first().error?.retryAfterSeconds)
        now = 6_000
        assertEquals(0, monitor.observe(peer).first().error?.retryAfterSeconds)
    }

    @Test
    fun staleCompletionCannotClearANewerOperationOrCrossAReset() = runBlocking {
        val monitor = CloudDraftSyncMonitor()
        val old = monitor.begin(peer to 0)
        monitor.reset()
        val current = monitor.begin(peer to 0)
        monitor.finish(peer to 0, old)
        assertTrue(monitor.observe(peer).first().syncing)
        val error = TelegramError.parse("CHAT_WRITE_FORBIDDEN")
        monitor.finish(peer to 0, current, error)
        monitor.finish(peer to 0, old)
        assertEquals(error, monitor.observe(peer).first().error)
        monitor.reset()
        assertEquals(CloudDraftSyncStatus(), monitor.observe(peer).first())
    }

    @Test
    fun retryIsAnExplicitSignalWithoutPerformingTransportWork() = runBlocking {
        val monitor = CloudDraftSyncMonitor()
        val received = async(start = CoroutineStart.UNDISPATCHED) { monitor.retries.first() }
        monitor.requestRetry()
        received.await()
        assertEquals(CloudDraftSyncStatus(), monitor.observe(peer).first())
    }
}
