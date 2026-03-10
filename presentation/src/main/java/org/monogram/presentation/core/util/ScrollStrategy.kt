package org.monogram.presentation.core.util

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import org.monogram.presentation.core.ui.CollapsingToolbarState

enum class ScrollStrategy {
    EnterAlways {
        override fun create(
            offsetY: MutableState<Int>,
            toolbarState: CollapsingToolbarState,
            flingBehavior: FlingBehavior
        ): NestedScrollConnection =
            EnterAlwaysNestedScrollConnection(offsetY, toolbarState, flingBehavior)
    },
    EnterAlwaysCollapsed {
        override fun create(
            offsetY: MutableState<Int>,
            toolbarState: CollapsingToolbarState,
            flingBehavior: FlingBehavior
        ): NestedScrollConnection =
            EnterAlwaysCollapsedNestedScrollConnection(offsetY, toolbarState, flingBehavior)
    },
    ExitUntilCollapsed {
        override fun create(
            offsetY: MutableState<Int>,
            toolbarState: CollapsingToolbarState,
            flingBehavior: FlingBehavior
        ): NestedScrollConnection =
            ExitUntilCollapsedNestedScrollConnection(toolbarState, flingBehavior)
    };

    internal abstract fun create(
        offsetY: MutableState<Int>,
        toolbarState: CollapsingToolbarState,
        flingBehavior: FlingBehavior
    ): NestedScrollConnection
}

private class ScrollDelegate(
    private val offsetY: MutableState<Int>
) {
    private var scrollToBeConsumed: Float = 0f

    fun doScroll(delta: Float) {
        val scroll = scrollToBeConsumed + delta
        val scrollInt = scroll.toInt()

        scrollToBeConsumed = scroll - scrollInt

        offsetY.value += scrollInt
    }
}

internal class EnterAlwaysNestedScrollConnection(
    private val offsetY: MutableState<Int>,
    private val toolbarState: CollapsingToolbarState,
    private val flingBehavior: FlingBehavior
): NestedScrollConnection {
    private val scrollDelegate = ScrollDelegate(offsetY)
    private val tracker = RelativeVelocityTracker(CurrentTimeProviderImpl())

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val dy = available.y
        tracker.delta(dy)

        val toolbar = toolbarState.height.toFloat()
        val offset = offsetY.value.toFloat()

        val consume = if(dy < 0) {
            val toolbarConsumption = toolbarState.dispatchRawDelta(dy)
            val remaining = dy - toolbarConsumption
            val offsetConsumption = remaining.coerceAtLeast(-toolbar - offset)
            scrollDelegate.doScroll(offsetConsumption)

            toolbarConsumption + offsetConsumption
        }else{
            val offsetConsumption = dy.coerceAtMost(-offset)
            scrollDelegate.doScroll(offsetConsumption)

            val toolbarConsumption = toolbarState.dispatchRawDelta(dy - offsetConsumption)

            offsetConsumption + toolbarConsumption
        }

        return Offset(0f, consume)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val velocity = tracker.reset()

        val left = if(velocity > 0) {
            toolbarState.fling(flingBehavior, velocity)
        }else{
            velocity
        }

        return Velocity(x = 0f, y = available.y - left)
    }
}

internal class EnterAlwaysCollapsedNestedScrollConnection(
    private val offsetY: MutableState<Int>,
    private val toolbarState: CollapsingToolbarState,
    private val flingBehavior: FlingBehavior
): NestedScrollConnection {
    private val scrollDelegate = ScrollDelegate(offsetY)
    private val tracker = RelativeVelocityTracker(CurrentTimeProviderImpl())

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val dy = available.y
        tracker.delta(dy)

        val consumed = if(dy > 0) {
            val offsetConsumption = dy.coerceAtMost(-offsetY.value.toFloat())
            scrollDelegate.doScroll(offsetConsumption)

            offsetConsumption
        }else{
            val toolbarConsumption = toolbarState.dispatchRawDelta(dy)
            val offsetConsumption = (dy - toolbarConsumption).coerceAtLeast(-toolbarState.height.toFloat() - offsetY.value)

            scrollDelegate.doScroll(offsetConsumption)

            toolbarConsumption + offsetConsumption
        }

        return Offset(0f, consumed)
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        val dy = available.y

        return if(dy > 0) {
            Offset(0f, toolbarState.dispatchRawDelta(dy))
        }else{
            Offset(0f, 0f)
        }
    }

    override suspend fun onPreFling(available: Velocity): Velocity =
        Velocity(x = 0f, y = tracker.deriveDelta(available.y))

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val dy = available.y

        val left = if(dy > 0) {
            toolbarState.fling(flingBehavior, dy)
        }else{
            dy
        }

        return Velocity(x = 0f, y = available.y - left)
    }
}

internal class ExitUntilCollapsedNestedScrollConnection(
    private val toolbarState: CollapsingToolbarState,
    private val flingBehavior: FlingBehavior
): NestedScrollConnection {
    private val tracker = RelativeVelocityTracker(CurrentTimeProviderImpl())

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val dy = available.y
        tracker.delta(dy)

        val consume = if(dy < 0) {
            toolbarState.dispatchRawDelta(dy)
        }else{
            0f
        }

        return Offset(0f, consume)
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        val dy = available.y

        val consume = if(dy > 0) {
            toolbarState.dispatchRawDelta(dy)
        }else{
            0f
        }

        return Offset(0f, consume)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val velocity = tracker.reset()

        val left = if(velocity < 0) {
            toolbarState.fling(flingBehavior, velocity)
        }else{
            velocity
        }

        return Velocity(x = 0f, y = available.y - left)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val velocity = available.y

        val left = if(velocity > 0) {
            toolbarState.fling(flingBehavior, velocity)
        }else{
            velocity
        }

        return Velocity(x = 0f, y = available.y - left)
    }
}
