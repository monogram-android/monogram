package org.monogram.core.common.push

/** Coalesces short-lived MTProto wake jobs and invalidates in-flight work on cancel. */
class PushWakeGate(private val minGapMs: Long = 1_500L) {
    @Volatile private var lastStartMs: Long = Long.MIN_VALUE / 2
    @Volatile private var generation: Int = 0

    fun tryStart(nowMs: Long): Int? {
        if (nowMs - lastStartMs < minGapMs) return null
        lastStartMs = nowMs
        generation += 1
        return generation
    }

    fun cancel() {
        generation += 1
    }

    fun isCurrent(id: Int): Boolean = id == generation
}
