package org.monogram.core.database

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.monogram.core.common.telegram.TelegramError

data class OutgoingSendSyncState(
    val activeRandomId: Long? = null,
    val errors: Map<Long, TelegramError> = emptyMap(),
    val error: TelegramError? = null,
)

class OutgoingSendSyncMonitor {
    private val mutable = MutableStateFlow(OutgoingSendSyncState())
    private val requests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val retries = requests
    fun requestResume() {
        requests.tryEmit(Unit)
    }

    val state = mutable.asStateFlow()
    fun begin(randomId: Long) {
        mutable.update { it.copy(activeRandomId = randomId, errors = it.errors - randomId) }
    }

    fun finish(randomId: Long, error: TelegramError?) {
        mutable.update {
            if (it.activeRandomId != randomId) it else it.copy(
                activeRandomId = null,
                errors = if (error == null) it.errors - randomId else it.errors + (randomId to error)
            )
        }
    }

    fun fail(error: TelegramError?) {
        mutable.update { it.copy(error = error) }
    }

    fun retain(ids: Set<Long>) {
        mutable.update {
            it.copy(
                activeRandomId = it.activeRandomId?.takeIf(ids::contains),
                errors = it.errors.filterKeys(ids::contains)
            )
        }
    }

    fun reset() {
        mutable.value = OutgoingSendSyncState()
    }
}
