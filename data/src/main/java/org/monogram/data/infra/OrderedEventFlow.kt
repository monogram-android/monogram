package org.monogram.data.infra

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

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
