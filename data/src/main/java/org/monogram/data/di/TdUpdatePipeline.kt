package org.monogram.data.di

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Fan-out for TDLib updates, with two deliberately different delivery contracts.
 *
 * [submit] is called on TDLib's single "TDLib thread" (`Client.ResponseReceiver`, see
 * `Client.java`). That same thread also delivers every query result, so it must never
 * block and must stay O(1): blocking it stalls all in-flight requests, and TDLib's
 * native output queue then grows without bound. [submit] therefore performs exactly one
 * non-suspending enqueue and nothing else; a dedicated pump thread does the fan-out.
 * (Fanning out on the callback thread instead was measured at ~38us/update with eight
 * lanes attached, against a ~8us total budget.)
 *
 * Two ways to consume:
 *
 *  - [lane] — **lossless and strictly ordered.** Private unbounded queue, private worker,
 *    per-update exception isolation. Use it whenever the handler writes to Room, mutates
 *    [org.monogram.data.chats.ChatCache], or issues a TDLib request. The order a lane sees
 *    is exactly TDLib's delivery order.
 *  - [updates] — **lossy by design.** A shared [SharedFlow] with `DROP_OLDEST`. Use it only
 *    for consumers that render state they can re-read; a drop there costs a redraw, never a
 *    state transition.
 *
 * There is deliberately no bounded lane. A bounded lane would silently discard updates,
 * which is the defect this class exists to remove.
 */
internal class TdUpdatePipeline {

    private val ingest = Channel<TdApi.Update>(Channel.UNLIMITED)

    private val _updates = MutableSharedFlow<TdApi.Update>(
        replay = OBSERVER_REPLAY,
        extraBufferCapacity = OBSERVER_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Observation only — may conflate. Durable consumers must use [lane]. */
    val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    private val lanes = CopyOnWriteArrayList<Lane>()

    private val submitted = AtomicLong()
    private val dispatched = AtomicLong()
    private val rejected = AtomicLong()

    private val pumpExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, PUMP_THREAD_NAME).apply { isDaemon = true }
    }
    private val pumpScope = CoroutineScope(SupervisorJob() + pumpExecutor.asCoroutineDispatcher())

    init {
        pumpScope.launch {
            for (update in ingest) {
                // CopyOnWriteArrayList: indexed access, no iterator allocation on the hot path.
                for (index in lanes.indices) {
                    lanes.getOrNull(index)?.offer(update)
                }
                _updates.tryEmit(update)
                val count = dispatched.incrementAndGet()
                if (count % BACKLOG_SAMPLE_EVERY == 0L) reportBacklogIfHigh()
            }
        }
    }

    /**
     * Called on the TDLib callback thread for every update. One unbounded enqueue,
     * measured at single-digit nanoseconds and independent of the number of lanes.
     */
    fun submit(update: TdApi.Update) {
        submitted.incrementAndGet()
        // UNLIMITED: only fails once the pipeline has been shut down.
        if (ingest.trySend(update).isFailure) {
            rejected.incrementAndGet()
        }
    }

    /**
     * Registers a lossless, strictly ordered consumer. The lane stops and deregisters
     * when [scope] is cancelled.
     *
     * Register lanes during application startup. Updates delivered before a lane exists
     * are not retained for it, so a durable consumer that is constructed lazily will miss
     * everything TDLib sent beforehand.
     *
     * @param filter evaluated on the pump thread; keep it to cheap type checks.
     * @param context extra context for the worker, e.g. `Dispatchers.IO` for a lane that
     *   writes to Room. Defaults to [scope]'s dispatcher.
     */
    fun lane(
        name: String,
        scope: CoroutineScope,
        context: CoroutineContext = EmptyCoroutineContext,
        filter: (TdApi.Update) -> Boolean = { true },
        handler: suspend (TdApi.Update) -> Unit,
    ): Lane {
        val lane = Lane(name, filter)
        // Register before starting the worker: if `scope` is already cancelled the worker
        // completes immediately, and its completion handler must be able to find the lane.
        // Otherwise the lane would linger with nothing draining its queue.
        lanes.add(lane)
        val job = scope.launch(context) {
            for (update in lane.queue) {
                // Per-update isolation. `.catch { }` on a Flow ends the subscription for
                // good; this keeps the lane alive and counts the failure instead.
                try {
                    handler(update)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lane.failures.incrementAndGet()
                    Log.e(TAG, "lane '$name' failed on ${update.javaClass.simpleName}", e)
                }
                lane.processed.incrementAndGet()
            }
        }
        lane.worker = job
        job.invokeOnCompletion { cause ->
            lanes.remove(lane)
            lane.queue.close()
            if (cause != null && cause !is CancellationException) {
                Log.e(TAG, "lane '$name' terminated unexpectedly", cause)
            }
        }
        return lane
    }

    /** Snapshot for diagnostics; safe to call from any thread. */
    fun metrics(): String = buildString {
        append("submitted=").append(submitted.get())
        append(" dispatched=").append(dispatched.get())
        append(" ingestBacklog=").append(submitted.get() - dispatched.get())
        if (rejected.get() > 0) append(" rejected=").append(rejected.get())
        append(" observers=").append(_updates.subscriptionCount.value)
        for (lane in lanes) {
            append(" | ").append(lane.name)
                .append(": backlog=").append(lane.backlog())
                .append(" processed=").append(lane.processed.get())
                .append(" failures=").append(lane.failures.get())
        }
    }

    fun shutdown() {
        ingest.close()
        lanes.forEach { it.cancel() }
        pumpScope.cancel()
        pumpExecutor.shutdown()
    }

    private fun reportBacklogIfHigh() {
        val ingestBacklog = submitted.get() - dispatched.get()
        var worstLane: Lane? = null
        var worstBacklog = 0L
        for (lane in lanes) {
            val backlog = lane.backlog()
            if (backlog > worstBacklog) {
                worstBacklog = backlog
                worstLane = lane
            }
        }
        if (worstBacklog < BACKLOG_WARN_AT && ingestBacklog < BACKLOG_WARN_AT) return
        Log.w(TAG, "update backlog is high (worst lane '${worstLane?.name}'): ${metrics()}")
    }

    internal class Lane(
        val name: String,
        private val filter: (TdApi.Update) -> Boolean,
    ) {
        // Unbounded on purpose: a lane exists precisely because its consumer must not lose
        // updates. Backlog is reported through [metrics] rather than being discarded.
        val queue = Channel<TdApi.Update>(Channel.UNLIMITED)
        val queued = AtomicLong()
        val processed = AtomicLong()
        val failures = AtomicLong()

        @Volatile
        var worker: Job? = null

        fun offer(update: TdApi.Update) {
            if (!filter(update)) return
            // Count only what was actually accepted: trySend fails once the lane has been
            // closed, and counting those would leave backlog() permanently non-zero.
            if (queue.trySend(update).isSuccess) queued.incrementAndGet()
        }

        fun backlog(): Long = queued.get() - processed.get()

        fun cancel() {
            worker?.cancel()
            queue.close()
        }
    }

    private companion object {
        private const val TAG = "TdUpdatePipeline"
        private const val PUMP_THREAD_NAME = "td-update-pump"

        /**
         * Observation buffer. Large enough that a collector doing only in-memory work
         * cannot realistically fall behind; anything slower belongs on a lane.
         */
        private const val OBSERVER_BUFFER = 1024

        /**
         * Kept for late observers of cheap, replaceable state. It is not a correctness
         * mechanism: durable consumers use [lane], which never drops.
         */
        private const val OBSERVER_REPLAY = 3

        private const val BACKLOG_WARN_AT = 2048L
        private const val BACKLOG_SAMPLE_EVERY = 512L
    }
}
