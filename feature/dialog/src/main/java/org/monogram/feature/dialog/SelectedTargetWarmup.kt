package org.monogram.feature.dialog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.monogram.core.common.WorkOwnership
import org.monogram.network.http.MediaPriority

const val PHONE_WARMUP_LIMIT = 20
const val TABLET_WARMUP_LIMIT = 30

/**
 * One explicit navigation target. Interest is released when the target changes,
 * and opening that same target promotes the work instead of starting another copy.
 * Optional work waits for the first usable frame and yields under pressure.
 */
class SelectedTargetWarmup(
    private val scope: CoroutineScope,
    private val tablet: () -> Boolean = { false },
    private val underPressure: () -> Boolean = { false },
    private val load: suspend (peerId: Long, anchorId: Int, limit: Int, priority: Int) -> Unit,
) {
    private var generation = 0
    private var job: Job? = null
    private var peerId: Long? = null
    private var anchorId: Int = 0
    private var frameReady = false
    private var priority = MediaPriority.IDLE

    fun onFirstFrame() {
        frameReady = true
        launch()
    }

    fun select(peer: Long, anchor: Int = 0) {
        if (peerId == peer && anchorId == anchor && job?.isActive == true) return
        generation++
        peerId = peer
        anchorId = anchor
        priority = MediaPriority.IDLE
        job?.cancel()
        job = null
        launch()
    }

    /** The opened chat raises an in-flight idle fetch instead of leaving it background. */
    fun promote(peer: Long) {
        if (peerId != peer) return
        val raised = priority < MediaPriority.VISIBLE
        priority = MediaPriority.VISIBLE
        WorkOwnership.promote(ownerKey(peer), MediaPriority.VISIBLE)
        if (job?.isActive == true && raised) {
            generation++
            launch()
        } else if (job?.isActive != true) {
            generation++
            launch()
        }
    }

    fun clear() {
        generation++
        peerId = null
        job?.cancel()
        job = null
    }

    fun limit(): Int = if (tablet()) TABLET_WARMUP_LIMIT else PHONE_WARMUP_LIMIT

    private fun launch() {
        val peer = peerId ?: return
        if (!frameReady || underPressure()) return
        val gen = generation
        val anchor = anchorId
        val requested = priority
        val key = ownerKey(peer)
        WorkOwnership.acquire(key, requested)
        job?.cancel()
        job = scope.launch {
            try {
                load(peer, anchor, limit(), WorkOwnership.state(key)?.priority ?: requested)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            } finally {
                WorkOwnership.release(key)
                if (gen == generation) WorkOwnership.finish(key)
            }
        }
    }

    private fun ownerKey(peer: Long) = "history-target:$peer"
}
