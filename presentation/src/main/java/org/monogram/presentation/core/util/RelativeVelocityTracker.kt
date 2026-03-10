package org.monogram.presentation.core.util

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker


internal class RelativeVelocityTracker(
    private val timeProvider: CurrentTimeProvider
) {
    private val tracker = VelocityTracker()
    private var lastY: Float? = null

    fun delta(delta: Float) {
        val new = (lastY ?: 0f) + delta

        tracker.addPosition(timeProvider.now(), Offset(0f, new))
        lastY = new
    }

    fun reset(): Float {
        lastY = null

        val velocity = tracker.calculateVelocity()
        tracker.resetTracking()

        return velocity.y
    }
}

internal fun RelativeVelocityTracker.deriveDelta(initial: Float) =
    initial - reset()

internal interface CurrentTimeProvider {
    fun now(): Long
}

internal class CurrentTimeProviderImpl: CurrentTimeProvider {
    override fun now(): Long =
        SystemClock.uptimeMillis()
}
