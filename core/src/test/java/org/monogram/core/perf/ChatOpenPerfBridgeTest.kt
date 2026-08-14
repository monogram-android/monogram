package org.monogram.core.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChatOpenPerfBridgeTest {

    @Test
    fun `completed session keeps latency counters and report is resettable`() {
        ChatOpenPerfBridge.resetReport()
        ChatOpenPerfBridge.startSession(42L, null, "initial", "resolving")
        ChatOpenPerfBridge.recordHistoryRequest(42L, null)
        ChatOpenPerfBridge.markFirstContent(42L, null)
        ChatOpenPerfBridge.recordShadowMismatch(42L, null)
        ChatOpenPerfBridge.markSettled(42L, null)
        ChatOpenPerfBridge.clearSession(42L, null)

        val report = ChatOpenPerfBridge.report()
        assertEquals(1, report.sessionCount)
        val session = report.completedSessions.single()
        assertEquals(1, session.requestCount)
        assertNotNull(session.firstContentLatencyMs)
        assertNotNull(session.settledLatencyMs)
        assertEquals(1, session.shadowMismatchCount)

        ChatOpenPerfBridge.resetReport()
        assertEquals(0, ChatOpenPerfBridge.report().sessionCount)
        assertNull(ChatOpenPerfBridge.currentSession(42L, null))
    }
}
