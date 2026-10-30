package org.monogram.core.models

enum class LiveLocationPhase { ACTIVE, PAUSED, STOPPING, UNCERTAIN_STOP, STOPPED, EXPIRED }

data class LiveLocationSession(
    val messageId: MessageId,
    val randomId: Long,
    val expiresAt: Long?,
    val phase: LiveLocationPhase = LiveLocationPhase.ACTIVE,
    val revision: Long = 0,
) {
    fun expired(nowSeconds: Long): Boolean = expiresAt?.let { nowSeconds >= it } ?: false

    fun canTransition(next: LiveLocationPhase): Boolean = when (phase) {
        LiveLocationPhase.ACTIVE -> next in setOf(
            LiveLocationPhase.PAUSED, LiveLocationPhase.STOPPING, LiveLocationPhase.EXPIRED
        )

        LiveLocationPhase.PAUSED -> next in setOf(
            LiveLocationPhase.ACTIVE, LiveLocationPhase.STOPPING, LiveLocationPhase.EXPIRED
        )

        LiveLocationPhase.STOPPING -> next in setOf(
            LiveLocationPhase.STOPPED, LiveLocationPhase.UNCERTAIN_STOP, LiveLocationPhase.EXPIRED
        )

        LiveLocationPhase.UNCERTAIN_STOP -> next in setOf(
            LiveLocationPhase.STOPPING, LiveLocationPhase.STOPPED, LiveLocationPhase.EXPIRED
        )

        LiveLocationPhase.STOPPED, LiveLocationPhase.EXPIRED -> false
    }
}
