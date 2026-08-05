package org.monogram.data.di

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 *  - a slow, blocked or throwing lane affects only itself;
 *  - the observation flow is allowed to conflate, and does;
 *  - ingestion from the TDLib callback thread never waits for a consumer.
 *
 * Progress is driven by explicit gates rather than by sleeping, so runtime does not
 * depend on the platform's timer resolution. `delay` appears only as a polling interval
 * in [awaitCount], never once per update.
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

    /**
     * Polls until [actual] reaches [expected]. Uses an explicit deadline rather than
     * `withTimeout` so a failure reports what was actually reached.
     */
    private suspend fun awaitCount(
        expected: Int,
        what: String,
        timeoutMs: Long = 30_000,
        actual: () -> Int,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (actual() < expected) {
            if (System.nanoTime() > deadline) {
                throw AssertionError("timed out after ${timeoutMs}ms waiting for $expected $what, reached ${actual()}")
            }
            delay(2)
        }
    }

    /** Number of updates the pump has fanned out, as opposed to merely accepted. */
    private fun dispatched(pipeline: TdUpdatePipeline): Int =
        Regex("dispatched=(\\d+)").find(pipeline.metrics())?.groupValues?.get(1)?.toInt() ?: 0

    private suspend fun awaitObserverCount(pipeline: TdUpdatePipeline, expected: Int) {
        val deadline = System.nanoTime() + 30_000L * 1_000_000
        while (!pipeline.metrics().contains("observers=$expected")) {
            if (System.nanoTime() > deadline) {
                throw AssertionError("observer never subscribed: ${pipeline.metrics()}")
            }
            delay(2)
        }
    }

    @Test(timeout = 60_000)
    fun `lane receives every update exactly once and in order`() = runBlocking {
        val pipeline = pipeline()
        val received = Collections.synchronizedList(mutableListOf<Int>())
        pipeline.lane("state", scope()) { received.add(seqOf(it)) }

        val total = 5_000
        submitFromCallbackThread(pipeline, total)
        awaitCount(total, "updates on the lane") { received.size }

        assertEquals(total, received.size)
        assertEquals((0 until total).toList(), received.toList())
    }

    @Test(timeout = 60_000)
    fun `lane filter is applied and does not create gaps`() = runBlocking {
        val pipeline = pipeline()
        val received = Collections.synchronizedList(mutableListOf<Int>())
        pipeline.lane("even", scope(), filter = { seqOf(it) % 2 == 0 }) { received.add(seqOf(it)) }

        submitFromCallbackThread(pipeline, 1_000)
        awaitCount(500, "even updates") { received.size }

        assertEquals(500, received.size)
        assertEquals((0 until 1_000 step 2).toList(), received.toList())
    }

    @Test(timeout = 60_000)
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
        awaitCount(total, "handled or failed updates") { handled.get() + failed.get() }

        assertEquals(100, failed.get())
        assertEquals(900, handled.get())
        assertTrue("lane must still be registered", pipeline.metrics().contains("flaky"))
    }

    /**
     * A lane that makes no progress at all is the extreme case of a slow lane, and it is
     * reached by a gate rather than by sleeping, so the test cannot become slow.
     */
    @Test(timeout = 60_000)
    fun `a blocked lane does not stop another lane and catches up losslessly`() = runBlocking {
        val pipeline = pipeline()
        val gate = CompletableDeferred<Unit>()
        val blocked = Collections.synchronizedList(mutableListOf<Int>())
        val healthy = Collections.synchronizedList(mutableListOf<Int>())

        pipeline.lane("blocked", scope()) { gate.await(); blocked.add(seqOf(it)) }
        pipeline.lane("healthy", scope()) { healthy.add(seqOf(it)) }

        val total = 2_000
        submitFromCallbackThread(pipeline, total)

        awaitCount(total, "updates on the healthy lane") { healthy.size }
        assertEquals((0 until total).toList(), healthy.toList())
        assertEquals("the blocked lane must not have progressed", 0, blocked.size)

        gate.complete(Unit)
        awaitCount(total, "updates on the unblocked lane") { blocked.size }
        assertEquals(
            "a lane must catch up losslessly and in order",
            (0 until total).toList(),
            blocked.toList()
        )
    }

    @Test(timeout = 60_000)
    fun `ingestion does not wait for consumers`() = runBlocking {
        val pipeline = pipeline()
        val gate = CompletableDeferred<Unit>()
        val processed = AtomicInteger()
        pipeline.lane("blocked", scope()) {
            gate.await()
            processed.incrementAndGet()
        }

        // Every submit returns even though the lane handler has never returned.
        val total = 10_000
        submitFromCallbackThread(pipeline, total)
        assertEquals("ingestion must not block on a consumer", 0, processed.get())

        gate.complete(Unit)
        awaitCount(total, "updates drained after unblocking") { processed.get() }
        assertEquals(total, processed.get())
    }

    @Test(timeout = 60_000)
    fun `lane deregisters when its scope is cancelled`() = runBlocking {
        val pipeline = pipeline()
        val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val seen = AtomicInteger()
        pipeline.lane("transient", ownScope) { seen.incrementAndGet() }

        submitFromCallbackThread(pipeline, 10)
        awaitCount(10, "updates before cancellation") { seen.get() }

        ownScope.cancel()
        val deadline = System.nanoTime() + 30_000L * 1_000_000
        while (pipeline.metrics().contains("transient")) {
            if (System.nanoTime() > deadline) {
                throw AssertionError("lane was not deregistered: ${pipeline.metrics()}")
            }
            delay(2)
        }

        // Further updates must neither reach nor accumulate for the dead lane.
        submitFromCallbackThread(pipeline, 100, from = 10)
        awaitCount(110, "the pump to dispatch the remaining updates") { dispatched(pipeline) }
        assertFalse(pipeline.metrics().contains("transient"))
        assertEquals("a cancelled lane must not keep consuming", 10, seen.get())
    }

    /**
     * The reason lanes exist. Under a burst larger than the observation buffer, the flow
     * conflates and the lane does not.
     *
     * Deterministic: the observer is frozen on its first element for the whole burst, and
     * the burst is several times the flow's buffer, so conflation is guaranteed by
     * SharedFlow's semantics rather than by winning a race.
     */
    @Test(timeout = 60_000)
    fun `observation flow conflates under load while a lane does not`() = runBlocking {
        val pipeline = pipeline()
        val laneSeen = AtomicInteger()
        val observerSeen = AtomicInteger()
        val observerFrozen = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sentinelSeen = CompletableDeferred<Unit>()

        pipeline.lane("durable", scope()) { laneSeen.incrementAndGet() }

        val observer = scope().launch {
            pipeline.updates.collect { u ->
                if (!observerFrozen.isCompleted) {
                    observerFrozen.complete(Unit)
                    release.await()
                }
                observerSeen.incrementAndGet()
                if (seqOf(u) == SENTINEL) sentinelSeen.complete(Unit)
            }
        }
        awaitObserverCount(pipeline, 1)

        // Freeze the observer, then push far more than its buffer can hold.
        submitFromCallbackThread(pipeline, 1)
        observerFrozen.await()

        val burst = 3_000
        submitFromCallbackThread(pipeline, burst, from = 1)
        pipeline.submit(update(SENTINEL))
        val submitted = 1 + burst + 1

        // submit() only fills the ingest queue. Wait for the pump to have emitted all of
        // it, so the observer is provably frozen across the whole emission sequence and
        // its buffer has certainly overflowed before it is allowed to run again.
        awaitCount(submitted, "the pump to dispatch the burst") { dispatched(pipeline) }

        // The sentinel is the newest value, so it is always in the buffer: once the
        // observer has seen it, nothing more is coming and its count is final.
        release.complete(Unit)
        sentinelSeen.await()

        awaitCount(submitted, "updates on the lane") { laneSeen.get() }
        assertEquals("a lane must never drop", submitted, laneSeen.get())
        assertTrue(
            "the observation flow must conflate: it saw ${observerSeen.get()} of $submitted",
            observerSeen.get() < submitted
        )
        observer.cancel()
    }

    @Test(timeout = 60_000)
    fun `metrics expose the backlog of a stalled lane`() = runBlocking {
        val pipeline = pipeline()
        val gate = CompletableDeferred<Unit>()
        pipeline.lane("stalled", scope()) { gate.await() }

        submitFromCallbackThread(pipeline, 250)
        val backlogPattern = Regex("stalled: backlog=(\\d+)")
        awaitCount(249, "the stalled lane's backlog to be reported") {
            backlogPattern.find(pipeline.metrics())?.groupValues?.get(1)?.toInt() ?: 0
        }

        val metrics = pipeline.metrics()
        gate.complete(Unit)
        assertTrue(metrics, metrics.contains("submitted=250"))
    }

    private companion object {
        /** Distinct from every generated sequence number. */
        private const val SENTINEL = -1
    }
}
