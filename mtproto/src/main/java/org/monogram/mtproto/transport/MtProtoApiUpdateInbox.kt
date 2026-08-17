package org.monogram.mtproto.transport

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5

data class MtProtoApiUpdateInboxMetrics(
    val admitted: Long,
    val received: Long,
    val backlog: Long,
)

/** Lossless, ordered inbox for raw API update envelopes accepted by one MTProto session. */
class MtProtoApiUpdateInbox internal constructor() {
    private val updates = Channel<Updates_faf6aaa3d5>(Channel.UNLIMITED)
    private val admitted = AtomicLong()
    private val received = AtomicLong()

    suspend fun receive(): Updates_faf6aaa3d5? {
        val update = updates.receiveCatching().getOrNull() ?: return null
        received.incrementAndGet()
        return update
    }

    fun metrics(): MtProtoApiUpdateInboxMetrics {
        val accepted = admitted.get()
        val consumed = received.get()
        return MtProtoApiUpdateInboxMetrics(accepted, consumed, accepted - consumed)
    }

    internal fun admit(update: Updates_faf6aaa3d5) {
        admitted.incrementAndGet()
        if (updates.trySend(update).isFailure) {
            admitted.decrementAndGet()
            error("MTProto API update inbox is closed")
        }
    }

    internal fun close() {
        updates.close()
    }
}
