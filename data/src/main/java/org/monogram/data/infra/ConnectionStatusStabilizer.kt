package org.monogram.data.infra

import org.monogram.domain.repository.ConnectionStatus

internal class ConnectionStatusStabilizer(
    initialStatus: ConnectionStatus,
    private val connectingGraceMs: Long = 3_000L,
    private val updatingGraceMs: Long = 4_000L
) {
    private var publishedStatus: ConnectionStatus = initialStatus
    private var hasPublishedConnected: Boolean = initialStatus is ConnectionStatus.Connected
    private var pendingStatus: PendingStatus? = null

    fun onStatus(
        rawStatus: ConnectionStatus,
        nowMs: Long,
        hasUsableNetwork: Boolean
    ): StabilizedStatus {
        val status = if (!hasUsableNetwork) {
            ConnectionStatus.WaitingForNetwork
        } else {
            rawStatus
        }

        if (status is ConnectionStatus.Connected) {
            hasPublishedConnected = true
            pendingStatus = null
            publishedStatus = status
            return StabilizedStatus(status = status, pendingDueAtMs = null)
        }

        if (status is ConnectionStatus.WaitingForNetwork || status is ConnectionStatus.ConnectingToProxy) {
            pendingStatus = null
            publishedStatus = status
            return StabilizedStatus(status = status, pendingDueAtMs = null)
        }

        if (!hasPublishedConnected || publishedStatus !is ConnectionStatus.Connected) {
            pendingStatus = null
            publishedStatus = status
            return StabilizedStatus(status = status, pendingDueAtMs = null)
        }

        val graceMs = graceMsFor(status)
        val pending = pendingStatus
            ?.takeIf { it.status == status }
            ?: PendingStatus(status = status, firstSeenAtMs = nowMs, graceMs = graceMs)
                .also { pendingStatus = it }

        val dueAtMs = pending.firstSeenAtMs + pending.graceMs
        return if (nowMs >= dueAtMs) {
            pendingStatus = null
            publishedStatus = status
            StabilizedStatus(status = status, pendingDueAtMs = null)
        } else {
            StabilizedStatus(status = null, pendingDueAtMs = dueAtMs)
        }
    }

    fun publishPendingIfDue(nowMs: Long): ConnectionStatus? {
        val pending = pendingStatus ?: return null
        if (nowMs < pending.firstSeenAtMs + pending.graceMs) return null

        pendingStatus = null
        publishedStatus = pending.status
        return pending.status
    }

    fun hasPendingStatus(): Boolean = pendingStatus != null

    private fun graceMsFor(status: ConnectionStatus): Long = when (status) {
        ConnectionStatus.Connecting -> connectingGraceMs
        ConnectionStatus.Updating -> updatingGraceMs
        else -> 0L
    }

    data class StabilizedStatus(
        val status: ConnectionStatus?,
        val pendingDueAtMs: Long?
    )

    private data class PendingStatus(
        val status: ConnectionStatus,
        val firstSeenAtMs: Long,
        val graceMs: Long
    )
}
