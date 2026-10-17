package org.monogram.core.ui.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.monogram.core.models.LastSeen
import org.monogram.core.ui.components.PeerPresence.flow
import java.util.concurrent.ConcurrentHashMap

data class PeerPresenceLease(val status: String?, val statusAt: Long?)

/**
 * Latest presence lease, per peer. Online heartbeats move the deadline without a new
 * list or dialog state, so only a composable that collects [flow] restarts.
 */
object PeerPresence {
    private val leases = ConcurrentHashMap<Long, MutableStateFlow<PeerPresenceLease?>>()

    fun flow(peerId: Long): StateFlow<PeerPresenceLease?> =
        leases.getOrPut(peerId) { MutableStateFlow(null) }

    fun record(peerId: Long, status: String?, statusAt: Long?) {
        val flow = leases.getOrPut(peerId) { MutableStateFlow(null) }
        val current = flow.value
        if (current != null &&
            !LastSeen.affectsUi(current.status, current.statusAt, status, statusAt)
        ) {
            return
        }
        val next = PeerPresenceLease(status, statusAt)
        if (current != next) flow.value = next
    }
}
