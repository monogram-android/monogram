package org.monogram.network.bridge.session

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.monogram.core.common.PerfLog
import org.monogram.mtproto.MtprotoNative
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Dispatch classes understood by `set_dispatch_class` in native; keep in sync. */
internal object DispatchClass {
    const val INTERACTIVE_READ = 0
    const val BACKGROUND_READ = 1
    const val INTERACTIVE_MEDIA = 2
    const val BACKGROUND_MEDIA = 3
    const val INTERACTIVE_WRITE = 4
    const val ORDINARY_MEDIA = 5
    const val VISIBLE_MEDIA = 6
    const val USER_MEDIA = 7
}

internal class NativeRequestTag(
    val tag: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NativeRequestTag>
}

private val taggedRequests = ConcurrentHashMap<String, Long>()

internal fun promoteTaggedRequest(
    native: MtprotoNative,
    tag: String,
    dispatchClass: Int,
) {
    taggedRequests[tag]?.let { native.promoteRequestControl(it, dispatchClass) }
}

internal class HistoryReadPriority : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HistoryReadPriority>

    private var requestId: Long? = null

    @Volatile
    var dispatchClass: Int = DispatchClass.BACKGROUND_READ
        private set

    @Synchronized
    fun attach(id: Long) {
        requestId = id
    }

    @Synchronized
    fun promote(native: MtprotoNative) {
        if (dispatchClass == DispatchClass.INTERACTIVE_READ) return
        dispatchClass = DispatchClass.INTERACTIVE_READ
        // A worker already inside native can still be waiting at a lane gate.
        requestId?.let { native.promoteRequestControl(it, dispatchClass) }
    }

    @Synchronized
    fun detach(id: Long) {
        if (requestId == id) requestId = null
    }
}

private class NativeRequestContext(
    private val native: MtprotoNative,
    private val id: Long,
    private val dispatchClass: Int,
    private val historyPriority: HistoryReadPriority?,
) : AbstractCoroutineContextElement(Key), ThreadContextElement<Long> {
    companion object Key : CoroutineContext.Key<NativeRequestContext>

    override fun updateThreadContext(context: CoroutineContext): Long {
        native.setDispatchClass(historyPriority?.dispatchClass ?: dispatchClass)
        return native.bindRequestControl(id)
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: Long,
    ) {
        native.bindRequestControl(oldState)
        native.setDispatchClass(DispatchClass.INTERACTIVE_READ)
    }
}

internal fun dispatchClassName(dispatchClass: Int): String = when (dispatchClass) {
    DispatchClass.INTERACTIVE_READ -> "interactive_read"
    DispatchClass.BACKGROUND_READ -> "background_read"
    DispatchClass.INTERACTIVE_MEDIA -> "interactive_media"
    DispatchClass.BACKGROUND_MEDIA -> "background_media"
    DispatchClass.INTERACTIVE_WRITE -> "interactive_write"
    DispatchClass.ORDINARY_MEDIA -> "ordinary_media"
    DispatchClass.VISIBLE_MEDIA -> "visible_media"
    DispatchClass.USER_MEDIA -> "user_media"
    else -> "class$dispatchClass"
}

/** Cancellation wakes native I/O without waiting for the blocking worker to return. */
internal suspend fun <T> nativeRequest(
    native: MtprotoNative,
    dispatcher: CoroutineDispatcher,
    dispatchClass: Int,
    block: suspend () -> T,
): T {
    val startedAt = if (PerfLog.isEnabled()) System.nanoTime() else 0L
    val opId = PerfLog.currentOpId()
    val context = currentCoroutineContext()
    val tag = context[NativeRequestTag]?.tag
    val historyPriority = context[HistoryReadPriority]
    val serviceStartedAt = AtomicLong(0L)
    var resultLabel = "ok"
    try {
        return suspendCancellableCoroutine { continuation ->
            val id = native.createRequestControl()
            if (tag != null) taggedRequests[tag] = id
            historyPriority?.attach(id)
            val result = AtomicReference<Result<T>?>(null)
            val worker = CoroutineScope(
                dispatcher + NativeRequestContext(
                    native,
                    id,
                    dispatchClass,
                    historyPriority
                ) + PerfLog.OpTrace(
                    opId,
                ),
            ).launch(start = CoroutineStart.LAZY) {
                if (startedAt != 0L) serviceStartedAt.set(System.nanoTime())
                result.set(runCatching { block() })
            }
            worker.invokeOnCompletion { failure ->
                if (tag != null) taggedRequests.remove(tag, id)
                historyPriority?.detach(id)
                native.releaseRequestControl(id)
                continuation.resumeWith(
                    result.get() ?: Result.failure(
                        failure ?: IllegalStateException("Native request ended without a result"),
                    ),
                )
            }
            continuation.invokeOnCancellation {
                native.cancelRequestControl(id)
                worker.cancel()
            }
            worker.start()
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        resultLabel = "cancel"
        throw e
    } catch (e: Exception) {
        resultLabel = "err"
        throw e
    } finally {
        if (startedAt != 0L) {
            val finishedAt = System.nanoTime()
            val serviceAt = serviceStartedAt.get().takeIf { it != 0L } ?: finishedAt
            PerfLog.withOpId(opId) {
                PerfLog.trace(
                    op = "nativeRequest",
                    phase = "dispatch",
                    elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
                    dispatchClass = dispatchClass,
                    result = resultLabel,
                    detail = "lane=${dispatchClassName(dispatchClass)} " + "queue_wait_ms=${(serviceAt - startedAt) / 1_000_000} " + "service_ms=${(finishedAt - serviceAt) / 1_000_000}",
                )
            }
        }
    }
}
