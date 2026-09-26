package org.monogram.root

import com.arkivanov.essenty.backhandler.BackCallback
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.backhandler.BackEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBackGestureTest {
    @Test
    fun platformAndContentGestureDispatchToTheSameNavigationCallback() {
        val platform = BackDispatcher()
        val gesture = BackDispatcher()
        val handler = GestureBackHandler(platform, gesture)
        var backs = 0
        var cancellations = 0
        val callback = BackCallback(onBack = { backs++ }, onBackCancelled = { cancellations++ })
        handler.register(callback)
        assertTrue(platform.back())
        assertTrue(gesture.startPredictiveBack(BackEvent()))
        gesture.cancelPredictiveBack()
        assertEquals(1, backs)
        assertEquals(1, cancellations)
        assertTrue(gesture.startPredictiveBack(BackEvent()))
        gesture.back()
        assertEquals(2, backs)
        handler.unregister(callback)
        assertFalse(platform.back())
        assertFalse(gesture.back())
    }

    @Test
    fun shortAndReversedDragsDoNotPopTheChat() {
        assertFalse(shouldCompleteChatBack(-0.1f))
        assertFalse(shouldCompleteChatBack(0.29f))
        assertTrue(shouldCompleteChatBack(0.3f))
        assertTrue(shouldCompleteChatBack(1f))
    }

    @Test
    fun intentionalFlingCompletesButJitterAndReverseFlingDoNot() {
        assertTrue(shouldCompleteChatBack(0.2f, distanceDp = 80f, velocityDp = 1000f))
        assertFalse(shouldCompleteChatBack(0.2f, distanceDp = 80f, velocityDp = 200f))
        assertFalse(shouldCompleteChatBack(0.05f, distanceDp = 12f, velocityDp = 1000f))
        assertFalse(shouldCompleteChatBack(0.4f, distanceDp = 160f, velocityDp = -1000f))
    }
}
