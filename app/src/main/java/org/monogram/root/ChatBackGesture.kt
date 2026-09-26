package org.monogram.root

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.LayoutDirection
import com.arkivanov.essenty.backhandler.BackCallback
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.backhandler.BackEvent
import com.arkivanov.essenty.backhandler.BackHandler
import kotlin.math.abs

/** Both platform back and in-content drags use the same Decompose animation. */
internal class GestureBackHandler(
    private val platform: BackHandler,
    private val gesture: BackHandler,
) : BackHandler {
    override fun isRegistered(callback: BackCallback): Boolean = platform.isRegistered(callback)
    override fun register(callback: BackCallback) {
        platform.register(callback)
        gesture.register(callback)
    }
    override fun unregister(callback: BackCallback) {
        platform.unregister(callback)
        gesture.unregister(callback)
    }
}

internal fun Modifier.chatBackGesture(
    dispatcher: BackDispatcher,
    layoutDirection: LayoutDirection,
    prioritizeChildren: () -> Boolean = { false },
    enabled: () -> Boolean,
): Modifier = pointerInput(dispatcher, layoutDirection) {
    val direction = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
    val edge = if (direction > 0) BackEvent.SwipeEdge.LEFT else BackEvent.SwipeEdge.RIGHT
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!enabled()) return@awaitEachGesture
        val childFirst = prioritizeChildren()
        var started = false
        var completed = false
        var progress = 0f
        val velocity = VelocityTracker().apply { addPointerInputChange(down) }
        try {
            while (true) {
                // Claim back-direction motion before a message consumes its touch slop.
                val event = awaitPointerEvent(if (!started && childFirst) PointerEventPass.Main else PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                // Switching Initial -> Main can return the same down consumed by a clickable.
                if (!started && change.uptimeMillis == down.uptimeMillis) continue
                if (event.changes.count { it.pressed } > 1) break
                velocity.addPointerInputChange(change)
                val delta = change.position - down.position
                if (!started) {
                    if (change.isConsumed || !change.pressed) break
                    if (abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) >= abs(delta.x)) break
                    if (abs(delta.x) <= viewConfiguration.touchSlop) continue
                    if (delta.x * direction <= 0f) break
                    started = dispatcher.startPredictiveBack(
                        BackEvent(swipeEdge = edge, touchX = down.position.x, touchY = down.position.y),
                    )
                    if (!started) break
                }
                progress = (delta.x * direction / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                change.consume()
                dispatcher.progressPredictiveBack(
                    BackEvent(progress, edge, change.position.x, change.position.y),
                )
                if (!change.pressed) {
                    // Let descendants observe the consumed release before navigation replaces them.
                    awaitPointerEvent(PointerEventPass.Final)
                    completed = true
                    val complete = shouldCompleteChatBack(
                        progress = progress,
                        distanceDp = delta.x * direction / density,
                        velocityDp = velocity.calculateVelocity().x * direction / density,
                    )
                    if (complete) dispatcher.back() else dispatcher.cancelPredictiveBack()
                    break
                }
            }
        } finally {
            if (started && !completed) dispatcher.cancelPredictiveBack()
        }
    }
}

internal fun shouldCompleteChatBack(progress: Float, distanceDp: Float = 0f, velocityDp: Float = 0f): Boolean =
    velocityDp > -800f && (progress >= 0.3f || (distanceDp >= 48f && velocityDp >= 800f))
