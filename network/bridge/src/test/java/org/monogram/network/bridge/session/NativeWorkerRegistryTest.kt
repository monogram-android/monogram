package org.monogram.network.bridge.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativeWorkerRegistryTest {
    @Test
    fun callerCancellationDoesNotCompleteTheWorker() = runBlocking {
        val registry = NativeWorkerRegistry()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancels = AtomicInteger()
        val cleanups = AtomicInteger()
        val worker =
            registry.tryRegister({ cancels.incrementAndGet() }, { cleanups.incrementAndGet() })!!
        val executor = Executors.newSingleThreadExecutor()
        try {
            val native = executor.submit {
                entered.countDown()
                try {
                    check(release.await(5, TimeUnit.SECONDS))
                } finally {
                    worker.complete()
                    worker.complete()
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val caller = Job()
            caller.invokeOnCompletion { worker.cancel() }
            caller.cancel()
            registry.close()
            registry.close()
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) { registry.awaitClosed() }
            assertFalse(waiting.isCompleted)
            assertEquals(1, cancels.get())
            assertEquals(0, cleanups.get())
            release.countDown()
            native.get(5, TimeUnit.SECONDS)
            withTimeout(5_000) { waiting.join(); registry.awaitClosed() }
            assertEquals(1, cleanups.get())
            assertNull(registry.tryRegister({}, {}))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun closeRacesAdmissionWithoutLosingAdmittedWorkers() = runBlocking {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(200) {
                val registry = NativeWorkerRegistry()
                val start = CountDownLatch(1)
                val cancelled = AtomicInteger()
                val cleaned = AtomicInteger()
                val admission = executor.submit<NativeWorkerRegistry.Worker?> {
                    check(start.await(5, TimeUnit.SECONDS))
                    registry.tryRegister(
                        { cancelled.incrementAndGet() },
                        { cleaned.incrementAndGet() })
                }
                val close = executor.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    registry.close()
                }
                start.countDown()
                val worker = admission.get(5, TimeUnit.SECONDS)
                close.get(5, TimeUnit.SECONDS)
                if (worker != null) {
                    assertEquals(1, cancelled.get())
                    val waiting =
                        launch(start = CoroutineStart.UNDISPATCHED) { registry.awaitClosed() }
                    assertFalse(waiting.isCompleted)
                    worker.complete()
                    withTimeout(5_000) { waiting.join() }
                    assertEquals(1, cleaned.get())
                } else {
                    assertEquals(0, cancelled.get())
                    withTimeout(5_000) { registry.awaitClosed() }
                }
                assertNull(registry.tryRegister({}, {}))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun cancellationRunsOutsideLockAndCleanupWaitsForIt() = runBlocking {
        val registry = NativeWorkerRegistry()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanups = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val worker = registry.tryRegister({
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }, { cleanups.incrementAndGet() })!!
        try {
            val close = executor.submit { registry.close() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val finished = executor.submit { worker.cancel(); worker.complete(); worker.complete() }
            finished.get(5, TimeUnit.SECONDS)
            assertEquals(0, cleanups.get())
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) { registry.awaitClosed() }
            assertFalse(waiting.isCompleted)
            release.countDown()
            close.get(5, TimeUnit.SECONDS)
            withTimeout(5_000) { waiting.join(); registry.awaitClosed() }
            assertEquals(1, cleanups.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun failedCleanupIsReportedByEveryWaiter() = runBlocking {
        val registry = NativeWorkerRegistry()
        val failure = IllegalStateException("cleanup failed")
        val worker = registry.tryRegister({}, { throw failure })!!
        registry.close()
        try {
            worker.complete()
            error("Expected cleanup failure")
        } catch (error: IllegalStateException) {
            assertTrue(error === failure)
        }
        worker.complete()
        repeat(2) {
            try {
                registry.awaitClosed()
                error("Expected drain failure")
            } catch (error: IllegalStateException) {
                assertTrue(error === failure || error.cause === failure)
            }
        }
    }

    @Test
    fun cancellingAWaiterDoesNotCancelSharedDrain() = runBlocking {
        val registry = NativeWorkerRegistry()
        val worker = registry.tryRegister({}, {})
        assertNotNull(worker)
        registry.close()
        val started = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            started.complete(Unit)
            registry.awaitClosed()
        }
        started.await()
        first.cancel()
        first.join()
        val second = launch(start = CoroutineStart.UNDISPATCHED) { registry.awaitClosed() }
        assertFalse(second.isCompleted)
        worker!!.complete()
        withTimeout(5_000) { second.join(); registry.awaitClosed() }
        assertTrue(second.isCompleted)
    }
}
