package org.monogram.network.bridge.session

/** Monotonic milliseconds for pump cooldowns. Tests inject scheduler time. */
internal fun interface MonotonicClock {
    fun elapsedMs(): Long
}

internal object NanoTimeClock : MonotonicClock {
    override fun elapsedMs(): Long = System.nanoTime() / 1_000_000L
}
