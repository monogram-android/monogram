package org.monogram.network.bridge.session

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.monogram.core.common.PerfLog
import org.monogram.mtproto.MtprotoNative
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
}

private class NativeRequestContext(
    private val native: MtprotoNative,
    private val id: Long,
    private val dispatchClass: Int,
) : ThreadContextElement<Long>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NativeRequestContext>

    override fun updateThreadContext(context: CoroutineContext): Long {
        native.setDispatchClass(dispatchClass)
        return native.bindRequestControl(id)
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Long) {
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
    var resultLabel = "ok"
    try {
        return suspendCancellableCoroutine { continuation ->
            val id = native.createRequestControl()
            val result = AtomicReference<Result<T>?>(null)
            val worker =
                CoroutineScope(dispatcher + NativeRequestContext(native, id, dispatchClass)).launch(
                    start = CoroutineStart.LAZY
                ) {
                    result.set(runCatching { block() })
                }
            worker.invokeOnCompletion { failure ->
                native.releaseRequestControl(id)
                continuation.resumeWith(
                    result.get() ?: Result.failure(
                        failure ?: IllegalStateException("Native request ended without a result"),
                    )
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
            PerfLog.trace(
                op = "nativeRequest",
                phase = "dispatch",
                elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
                dispatchClass = dispatchClass,
                result = resultLabel,
                detail = "lane=${dispatchClassName(dispatchClass)}",
            )
        }
    }
}
