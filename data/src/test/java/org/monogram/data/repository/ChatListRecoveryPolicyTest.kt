package org.monogram.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.domain.repository.ConnectionStatus

class ChatListRecoveryPolicyTest {
    @Test
    fun `resume recovery triggers for disconnected state even without stale list`() {
        val decision = ChatListRecoveryPolicy.decideResumeRecovery(
            snapshot(
                connectionStatus = ConnectionStatus.Connecting,
                rendered = 120,
                cachedChats = 120,
                activePositions = 120
            )
        )

        assertEquals(ResumeRecoveryDecision.Disconnected, decision)
    }

    @Test
    fun `resume recovery triggers for stale sparse list while connected`() {
        val decision = ChatListRecoveryPolicy.decideResumeRecovery(
            snapshot(
                connectionStatus = ConnectionStatus.Connected,
                rendered = 4,
                cachedChats = 80,
                activePositions = 4
            )
        )

        assertEquals(ResumeRecoveryDecision.Stale, decision)
    }

    @Test
    fun `resume recovery skips healthy connected state`() {
        val decision = ChatListRecoveryPolicy.decideResumeRecovery(
            snapshot(
                connectionStatus = ConnectionStatus.Connected,
                rendered = 120,
                cachedChats = 120,
                activePositions = 120
            )
        )

        assertEquals(ResumeRecoveryDecision.None, decision)
    }

    @Test
    fun `repeated resume events are throttled`() {
        val decision = ChatListRecoveryPolicy.decideResumeRecovery(
            snapshot(
                connectionStatus = ConnectionStatus.Connecting,
                rendered = 0,
                cachedChats = 30,
                activePositions = 0,
                nowMs = 10_000L,
                lastResumeRecoveryAttemptAtMs = 8_000L
            )
        )

        assertEquals(ResumeRecoveryDecision.None, decision)
    }

    private fun snapshot(
        connectionStatus: ConnectionStatus?,
        rendered: Int,
        cachedChats: Int,
        activePositions: Int,
        nowMs: Long = 10_000L,
        lastResumeRecoveryAttemptAtMs: Long = 0L,
        activeRequestId: Long = 1L,
        currentLimit: Int = 200,
        initialLimit: Int = 200,
        maxLimit: Int = 10_000,
        isLoading: Boolean = false
    ) = ChatListRecoverySnapshot(
        rendered = rendered,
        activePositions = activePositions,
        cachedChats = cachedChats,
        currentLimit = currentLimit,
        initialLimit = initialLimit,
        maxLimit = maxLimit,
        activeRequestId = activeRequestId,
        isLoading = isLoading,
        connectionStatus = connectionStatus,
        nowMs = nowMs,
        lastRecoveryAttemptAtMs = 0L,
        lastResumeRecoveryAttemptAtMs = lastResumeRecoveryAttemptAtMs
    )
}
