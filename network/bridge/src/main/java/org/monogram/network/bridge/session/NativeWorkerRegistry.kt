package org.monogram.network.bridge.session

import kotlinx.coroutines.CompletableDeferred

/**
 * Register before starting any native work. Complete only after that work cannot touch native
 * state again, including when starting the worker fails. Cancellation is only a signal.
 * Cleanup can run on the completing or signalling thread and must not wait for this drain.
 */
internal class NativeWorkerRegistry {
    private val lock = Any()
    private val workers = mutableSetOf<Worker>()
    private val drained = CompletableDeferred<Unit>()
    private var closed = false
    private var cleanupFailure: Throwable? = null

    fun tryRegister(cancel: () -> Unit, cleanup: () -> Unit): Worker? = synchronized(lock) {
        if (closed) null else Worker(cancel, cleanup).also { workers.add(it) }
    }

    fun close() {
        val toCancel = synchronized(lock) {
            if (closed) return
            closed = true
            workers.filter { !it.finishing && !it.signalled }.onEach {
                it.signalled = true
                it.signalling = true
            }
        }
        if (toCancel.isEmpty()) completeIfDrained()
        var failure: Throwable? = null
        for (worker in toCancel) {
            try {
                worker.signal()
            } catch (error: Throwable) {
                if (failure == null) failure =
                    error else if (failure !== error) failure.addSuppressed(error)
            } finally {
                try {
                    signalFinished(worker)
                } catch (error: Throwable) {
                    if (failure == null) failure =
                        error else if (failure !== error) failure.addSuppressed(error)
                }
            }
        }
        failure?.let { throw it }
    }

    suspend fun awaitClosed() = drained.await()

    inner class Worker internal constructor(
        internal val signal: () -> Unit,
        private val cleanup: () -> Unit,
    ) {
        internal var finishing = false
        internal var signalled = false
        internal var signalling = false

        fun cancel() {
            synchronized(lock) {
                if (finishing || signalled) return
                signalled = true
                signalling = true
            }
            try {
                signal()
            } finally {
                signalFinished(this)
            }
        }

        /** Call after the worker has really stopped, including on startup failure. */
        fun complete() {
            val runCleanup = synchronized(lock) {
                if (finishing) return
                finishing = true
                !signalling
            }
            if (runCleanup) cleanupAndRemove()
        }

        internal fun cleanupAndRemove() {
            var failure: Throwable? = null
            try {
                cleanup()
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                synchronized(lock) {
                    if (failure != null) {
                        val previous = cleanupFailure
                        if (previous == null) cleanupFailure = failure
                        else if (previous !== failure) previous.addSuppressed(failure)
                    }
                    workers.remove(this)
                }
                completeIfDrained()
            }
        }
    }

    private fun signalFinished(worker: Worker) {
        val runCleanup = synchronized(lock) {
            worker.signalling = false
            worker.finishing
        }
        if (runCleanup) worker.cleanupAndRemove()
    }

    private fun completeIfDrained() {
        val state = synchronized(lock) { (closed && workers.isEmpty()) to cleanupFailure }
        if (state.first) {
            val failure = state.second
            if (failure == null) drained.complete(Unit) else drained.completeExceptionally(failure)
        }
    }
}
