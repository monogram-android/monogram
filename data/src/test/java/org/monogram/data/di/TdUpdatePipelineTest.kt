package org.monogram.data.di

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contract of [TdUpdatePipeline]:
 *
 *  - a lane never loses an update and never reorders one;
 *  - a slow or throwing lane affects only itself;
 *  - the observation flow is allowed to conflate, and does;
 *  - ingestion from the TDLib callback thread never waits for a consumer.
 */
class TdUpdatePipelineTest {

    private val scopes = mutableListOf<CoroutineScope>()
    private val pipelines = mutableListOf<TdUpdatePipeline>()

    private fun pipeline(): TdUpdatePipeline = TdUpdatePipeline().also { pipelines.add(it) }

    private fun scope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes.add(it) }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        pipelines.forEach { it.shutdown() }
    }

    /** Updates carry their sequence number in chatId so ordering is checkable. */
    private fun update(seq: Int): TdApi.Update = TdApi.UpdateChatTitle(seq.toLong(), "t$seq")

    private fun seqOf(update: TdApi.Update) = (update as TdApi.UpdateChatTitle).chatId.toInt()

    /** Pushes updates the way TDLib does: from one plain thread that is not a coroutine. */
    private fun submitFromCallbackThread(pipeline: TdUpdatePipeline, count: Int, from: Int = 0) {
        val thread = Thread({ repeat(count) { pipeline.submit(update(from + it)) } }, "TDLib thread")
        thread.start()
        thread.join()
    }

    private suspend fun awaitCount(expected: Int, timeoutMs: Long = 30_000, actual: () -> Int) {
        withTimeout(timeoutMs) {
            while (actual() < expected) delay(5)
        }
    }

    @Test
    fun `lane receives every update exactly once and in order`() = runBlocking {
        val pipeline = pipeline()
        val received = Collections.synchronizedList(mutableListOf<Int>())
        pipeline.lane("state", scope()) { received.add(seqOf(it)) }

        val total = 5_000
        submitFromCallbackThread(pipeline, total)
        awaitCount(total) { received.size }

        assertEquals(total, received.size)
        assertEquals((0 until total).toList(), received.toList())
    }

    @Test
    fun `lane filter is applied and does not create gaps`() = runBlocking {
        val pipeline = pipeline()
        val received = Collections.synchronizedList(mutableListOf<Int>())
        pipeline.lane("even", scope(), filter = { seqOf(it) % 2 == 0 }) { received.add(seqOf(it)) }

        submitFromCallbackThread(pipeline, 1_000)
        awaitCount(500) { received.size }

        assertEquals(500, received.size)
        assertEquals((0 until 1_000 step 2).toList(), received.toList())
    }

    @Test
    fun `a handler that throws does not end the lane`() = runBlocking {
        val pipeline = pipeline()
        val handled = AtomicInteger()
        val failed = AtomicInteger()
        pipeline.lane("flaky", scope()) {
            if (seqOf(it) % 10 == 0) {
                failed.incrementAndGet()
                error("boom on ${seqOf(it)}")
            }
            handled.incrementAndGet()
        }

        val total = 1_000
        submitFromCallbackThread(pipeline, total)
        awaitCount(total) { handled.get() + failed.get() }

        assertEquals(100, failed.get())
        assertEquals(900, handled.get())
        assertTrue("lane must still be registered", pipeline.metrics().contains("flaky"))
    }

    @Test
    fun `a slow lane does not make a fast lane lose or lag`() = runBlocking {
        val pipeline = pipeline()
        val fast = Collections.synchronizedList(mutableListOf<Int>())
        val slow = AtomicInteger()

        pipeline.lane("fast", scope()) { fast.add(seqOf(it)) }
        pipeline.lane("slow", scope()) { delay(2); slow.incrementAndGet() }

        val total = 500
        submitFromCallbackThread(pipeline, total)

        // The fast lane completes long before the slow one, and loses nothing.
        awaitCount(total) { fast.size }
        assertEquals((0 until total).toList(), fast.toList())

        // The slow lane is merely delayed, never truncated.
        awaitCount(total) { slow.get() }
        assertEquals(total, slow.get())
    }

    @Test
    fun `ingestion does not wait for consumers`() = runBlocking {
        val pipeline = pipeline()
        val gate = CompletableDeferred<Unit>()
        val processed = AtomicInteger()
        pipeline.lane("blocked", scope()) {
            gate.await()
            processed.incrementAndGet()
        }

        val total = 10_000
        val elapsedMs = kotlin.system.measureTimeMillis { submitFromCallbackThread(pipeline, total) }

        // The lane handler has not returned even once, yet every submit has completed.
        assertEquals(0, processed.get())
        assertTrue(
            "submitting $total updates took ${elapsedMs}ms; ingestion must not block on consumers",
            elapsedMs < 2_000
        )

        gate.complete(Unit)
        awaitCount(total) { processed.get() }
        assertEquals(total, processed.get())
    }

    @Test
    fun `lane deregisters when its scope is cancelled`() = runBlocking {
        val pipeline = pipeline()
        val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pipeline.lane("transient", ownScope) { }

        submitFromCallbackThread(pipeline, 10)
        withTimeout(10_000) { while (!pipeline.metrics().contains("transient")) delay(5) }

        ownScope.cancel()
        withTimeout(10_000) { while (pipeline.metrics().contains("transient")) delay(5) }

        // Further updates must not accumulate for the dead lane.
        submitFromCallbackThread(pipeline, 100, from = 10)
        delay(200)
        assertFalse(pipeline.metrics().contains("transient"))
    }

    /**
     * The reason lanes exist. A consumer slower than the arrival rate loses updates on the
     * observation flow and loses nothing on a lane, under the identical burst.
     */
    @Test
    fun `observation flow conflates under load while a lane does not`() = runBlocking {
        val pipeline = pipeline()
        val laneSeen = AtomicInteger()
        val observerSeen = AtomicInteger()
        val observerScope = scope()

        pipeline.lane("durable", scope()) { delay(1); laneSeen.incrementAndGet() }
        val observer = observerScope.launch {
            pipeline.updates.collect { delay(1); observerSeen.incrementAndGet() }
        }
        // Let the observer subscribe before the burst starts.
        withTimeout(10_000) { while (!pipeline.metrics().contains("observers=1")) delay(5) }

        val total = 4_000
        submitFromCallbackThread(pipeline, total)

        awaitCount(total, timeoutMs = 60_000) { laneSeen.get() }
        assertEquals("a lane must never drop", total, laneSeen.get())

        delay(500)
        assertTrue(
            "the observation flow is expected to conflate under this load, saw ${observerSeen.get()} of $total",
            observerSeen.get() < total
        )
        observer.cancel()
    }

    @Test
    fun `metrics expose the backlog of a stalled lane`() = runBlocking {
        val pipeline = pipeline()
        val gate = CompletableDeferred<Unit>()
        pipeline.lane("stalled", scope()) { gate.await() }

        submitFromCallbackThread(pipeline, 250)
        val backlogPattern = Regex("stalled: backlog=(\\d+)")
        withTimeout(10_000) {
            while (laneBacklog(backlogPattern, pipeline) < 249) delay(5)
        }

        val metrics = pipeline.metrics()
        gate.complete(Unit)

        assertTrue(metrics, metrics.contains("submitted=250"))
        assertTrue(metrics, laneBacklog(backlogPattern, pipeline) >= 0)
    }

    private fun laneBacklog(pattern: Regex, pipeline: TdUpdatePipeline): Int =
        pattern.find(pipeline.metrics())?.groupValues?.get(1)?.toInt() ?: -1
}
