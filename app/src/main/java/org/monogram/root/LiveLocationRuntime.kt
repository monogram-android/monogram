package org.monogram.root

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.core.common.Outcome
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.GeoPlace
import org.monogram.core.models.MessageId
import java.util.concurrent.ConcurrentHashMap

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveLocationRuntime internal constructor(
    private val scope: CoroutineScope,
    private val cache: OfflineWarmup,
    private val sample: suspend () -> GeoPlace?,
    private val permitted: () -> Boolean,
    private val edit: suspend (MessageId, GeoPlace?) -> Outcome<Unit>,
) {
    private val roots = ConcurrentHashMap.newKeySet<Any>()
    private val mutex = Mutex()
    private val controllers = MutableStateFlow<LiveLocationSync?>(null)
    private var owner: LiveLocationSync?
        get() = controllers.value
        set(value) {
            controllers.value = value
        }
    val errors = controllers.flatMapLatest {
        it?.errors
            ?: flowOf(emptyMap())
    }
    val failure = controllers.flatMapLatest { it?.failure ?: flowOf(null) }
    private var epoch: Long? = null

    internal fun setForeground(root: Any, visible: Boolean) {
        if (visible) roots.add(root) else roots.remove(root)
        scope.launch {
            mutex.withLock {
                owner?.setForeground(roots.isNotEmpty())
                if (roots.isEmpty()) owner?.stop() else owner?.start(scope)
            }
        }
    }

    internal suspend fun startAuthorized() = mutex.withLock {
        val current = cache.outgoingAccountEpoch()
        if (!cache.outgoingAccountIsCurrent(current)) return@withLock
        if (epoch != current) {
            owner?.stop()
            owner = LiveLocationSync(cache, sample, permitted, edit)
            epoch = current
        }
        owner?.setForeground(roots.isNotEmpty())
        if (roots.isNotEmpty()) owner?.start(scope)
    }

    private suspend fun authorizedOwner(expectedEpoch: Long?): LiveLocationSync? = mutex.withLock {
        val current = cache.outgoingAccountEpoch()
        owner?.takeIf {
            epoch == current && (expectedEpoch == null || expectedEpoch == current) && cache.outgoingAccountIsCurrent(
                current
            )
        }
    }

    internal suspend fun resume(id: MessageId, expectedEpoch: Long? = null): Boolean {
        val current = authorizedOwner(expectedEpoch) ?: return false
        return mutex.withLock {
            if (owner !== current || roots.isEmpty() || !current.start(scope)) false else current.resume(
                id
            )
        }
    }

    internal suspend fun requestStop(id: MessageId, expectedEpoch: Long? = null): Boolean =
        authorizedOwner(expectedEpoch)?.requestStop(id) == true

    internal suspend fun stopAuthorized() = mutex.withLock {
        owner?.stop()
        owner = null
        epoch = null
    }
}
