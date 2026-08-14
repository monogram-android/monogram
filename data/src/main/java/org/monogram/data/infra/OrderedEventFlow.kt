package org.monogram.data.infra

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Lossless ordered bridge for terminal events emitted from non-suspending callbacks. */
internal class OrderedEventFlow<T>(scope: CoroutineScope) {
    private val ingress = Channel<T>(Channel.UNLIMITED)
    private val _events = MutableSharedFlow<T>()

    val events: SharedFlow<T> = _events.asSharedFlow()

    init {
        scope.launch {
            for (event in ingress) {
                _events.emit(event)
            }
        }
    }

    fun enqueue(event: T): Boolean = ingress.trySend(event).isSuccess
}

/** Keeps only the newest pending progress value for each key. */
internal class LatestByKeyEventFlow<K, T>(
    scope: CoroutineScope,
    private val keyOf: (T) -> K,
    private val shouldEmit: (T) -> Boolean = { true }
) {
    private val latest = ConcurrentHashMap<K, T>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val _events = MutableSharedFlow<T>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<T> = _events.asSharedFlow()

    init {
        scope.launch {
            for (ignored in signal) {
                do {
                    val batch = ArrayList<Pair<K, T>>()
                    latest.forEach { key, event -> batch.add(key to event) }
                    batch.forEach { (key, event) ->
                        if (latest.remove(key, event) && shouldEmit(event)) {
                            _events.emit(event)
                        }
                    }
                } while (latest.isNotEmpty())
            }
        }
    }

    fun enqueue(event: T) {
        latest[keyOf(event)] = event
        signal.trySend(Unit)
    }

    fun remove(key: K) {
        latest.remove(key)
    }
}
