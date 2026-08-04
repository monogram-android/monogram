package org.monogram.data.infra

import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxLifecycleMetricsTest {

    @Test
    fun `publishes lifecycle percentiles after terminal updates`() {
        var now = 0L
        val metrics = OutboxLifecycleMetrics { now }
        metrics.recordPending(chatId = 1L, temporaryMessageId = -1L)
        now = 100L
        metrics.recordTerminal(chatId = 1L, temporaryMessageId = -1L)
        metrics.recordPending(chatId = 1L, temporaryMessageId = -2L)
        now = 300L
        metrics.recordTerminal(chatId = 1L, temporaryMessageId = -2L)

        val snapshot = metrics.snapshot.value
        assertEquals(0, snapshot.active)
        assertEquals(2L, snapshot.completed)
        assertEquals(100L, snapshot.p50Ms)
        assertEquals(200L, snapshot.p95Ms)
        assertEquals(200L, snapshot.p99Ms)
    }
}
