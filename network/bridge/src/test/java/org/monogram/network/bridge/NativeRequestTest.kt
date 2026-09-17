package org.monogram.network.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.MtprotoNative
import org.monogram.network.bridge.session.DispatchClass
import org.monogram.network.bridge.session.dispatchClassName
import org.monogram.network.bridge.session.nativeRequest

@OptIn(ExperimentalCoroutinesApi::class)
class NativeRequestTest {
    private class Controls : MtprotoNative by MtprotoNative.Stub {
        var next = 0L
        var current = 0L
        val cancelled = mutableSetOf<Long>()
        val released = mutableSetOf<Long>()
        val dispatchClasses = mutableListOf<Int>()
        override fun setDispatchClass(classId: Int) { dispatchClasses += classId }
        override fun createRequestControl(): Long = ++next
        override fun bindRequestControl(id: Long): Long = current.also { current = id }
        override fun cancelRequestControl(id: Long) { cancelled.add(id) }
        override fun releaseRequestControl(id: Long) { released.add(id) }
    }

    @Test
    fun cancellationBeforeDispatchReleasesControlWithoutRunningBody() = runTest {
        val native = Controls()
        var ran = false
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            nativeRequest(native, StandardTestDispatcher(testScheduler), DispatchClass.INTERACTIVE_READ) { ran = true }
        }
        request.cancel()
        runCurrent()
        assertTrue(!ran)
        assertEquals(setOf(1L), native.cancelled)
        assertEquals(setOf(1L), native.released)
    }

    @Test
    fun cancellingOneRequestPreservesAnotherAndRestoresThreadBinding() = runTest {
        val native = Controls()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val finish = CompletableDeferred<Int>()
        var firstId = 0L
        var secondId = 0L
        val first = launch {
            nativeRequest(native, dispatcher, DispatchClass.INTERACTIVE_READ) {
                firstId = native.current
                awaitCancellation()
            }
        }
        val second = async {
            nativeRequest(native, dispatcher, DispatchClass.INTERACTIVE_READ) {
                secondId = native.current
                val result = finish.await()
                assertEquals(secondId, native.current)
                result
            }
        }
        runCurrent()
        first.cancel()
        runCurrent()
        assertEquals(setOf(firstId), native.cancelled)
        assertEquals(setOf(firstId), native.released)
        assertTrue(second.isActive)
        finish.complete(42)
        assertEquals(42, second.await())
        runCurrent()
        assertEquals(setOf(firstId, secondId), native.released)
        assertEquals(0L, native.current)
    }

    @Test
    fun dispatchClassNameLabelsKnownClasses() {
        assertEquals("interactive_read", dispatchClassName(DispatchClass.INTERACTIVE_READ))
        assertEquals("background_read", dispatchClassName(DispatchClass.BACKGROUND_READ))
        assertEquals("interactive_media", dispatchClassName(DispatchClass.INTERACTIVE_MEDIA))
        assertEquals("background_media", dispatchClassName(DispatchClass.BACKGROUND_MEDIA))
    }

    @Test
    fun dispatchClassTravelsWithTheRequestAndRestoresTheInteractiveDefault() = runTest {
        val native = Controls()
        val seen = mutableListOf<Int>()
        nativeRequest(native, StandardTestDispatcher(testScheduler), DispatchClass.BACKGROUND_MEDIA) {
            seen += native.dispatchClasses.last()
        }
        assertEquals(listOf(DispatchClass.BACKGROUND_MEDIA), seen)
        // Restored to the interactive read default after the worker finishes.
        assertEquals(DispatchClass.INTERACTIVE_READ, native.dispatchClasses.last())
    }

    @Test
    fun thrownFailureReleasesControlAndPreservesException() = runTest {
        val native = Controls()
        val failure = IllegalArgumentException("fixture")
        try {
            nativeRequest(native, StandardTestDispatcher(testScheduler), DispatchClass.INTERACTIVE_READ) { throw failure }
            error("Expected failure")
        } catch (error: IllegalArgumentException) {
            assertEquals(failure.message, error.message)
            assertTrue(error === failure || error.cause === failure)
        }
        assertEquals(setOf(1L), native.released)
        assertEquals(0L, native.current)
    }
}
