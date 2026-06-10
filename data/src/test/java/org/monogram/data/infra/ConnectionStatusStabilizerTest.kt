package org.monogram.data.infra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.domain.repository.ConnectionStatus

class ConnectionStatusStabilizerTest {
    @Test
    fun `updating after connected is hidden inside grace window`() {
        val stabilizer = connectedStabilizer()

        val result = stabilizer.onStatus(
            rawStatus = ConnectionStatus.Updating,
            nowMs = 1_000L,
            hasUsableNetwork = true
        )

        assertNull(result.status)
        assertEquals(
            ConnectionStatus.Connected,
            stabilizer.onStatus(ConnectionStatus.Connected, 2_000L, true).status
        )
    }

    @Test
    fun `connecting after connected is published after grace window`() {
        val stabilizer = connectedStabilizer()

        val pending = stabilizer.onStatus(ConnectionStatus.Connecting, 1_000L, true)

        assertNull(pending.status)
        assertEquals(ConnectionStatus.Connecting, stabilizer.publishPendingIfDue(4_000L))
    }

    @Test
    fun `waiting for network is published immediately after connected`() {
        val stabilizer = connectedStabilizer()

        val result = stabilizer.onStatus(
            rawStatus = ConnectionStatus.WaitingForNetwork,
            nowMs = 1_000L,
            hasUsableNetwork = true
        )

        assertEquals(ConnectionStatus.WaitingForNetwork, result.status)
    }

    @Test
    fun `connecting to proxy is published immediately after connected`() {
        val stabilizer = connectedStabilizer()

        val result = stabilizer.onStatus(
            rawStatus = ConnectionStatus.ConnectingToProxy,
            nowMs = 1_000L,
            hasUsableNetwork = true
        )

        assertEquals(ConnectionStatus.ConnectingToProxy, result.status)
    }

    @Test
    fun `transient states are published before first connected`() {
        val stabilizer = ConnectionStatusStabilizer(ConnectionStatus.Connecting)

        val result = stabilizer.onStatus(
            rawStatus = ConnectionStatus.Updating,
            nowMs = 1_000L,
            hasUsableNetwork = true
        )

        assertEquals(ConnectionStatus.Updating, result.status)
    }

    @Test
    fun `connecting without active network becomes waiting for network`() {
        val stabilizer = connectedStabilizer()

        val result = stabilizer.onStatus(
            rawStatus = ConnectionStatus.Connecting,
            nowMs = 1_000L,
            hasUsableNetwork = false
        )

        assertEquals(ConnectionStatus.WaitingForNetwork, result.status)
    }

    private fun connectedStabilizer(): ConnectionStatusStabilizer =
        ConnectionStatusStabilizer(ConnectionStatus.Connected)
}
