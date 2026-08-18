package org.monogram.mtproto.updates

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MtProtoChannelUpdateOrdering(
    val channelId: Long,
    val pts: Int,
    val ptsCount: Int,
) {
    init {
        require(channelId > 0) { "channelId must be positive" }
        require(ptsCount > 0) { "ptsCount must be positive" }
    }
}

sealed interface MtProtoChannelUpdateOrderingResult {
    data class Applied(val state: MtProtoUpdateState) : MtProtoChannelUpdateOrderingResult
    data object Duplicate : MtProtoChannelUpdateOrderingResult
    data class Gap(
        val channelId: Long,
        val expectedPts: Int,
        val actualPts: Int,
    ) : MtProtoChannelUpdateOrderingResult
}

/** Serializes independent channel counters and persists them only after the apply callback succeeds. */
class MtProtoChannelUpdateOrderingCoordinator(
    initialState: MtProtoUpdateState,
    private val apply: suspend (MtProtoUpdateState) -> Unit,
) {
    private val mutex = Mutex()
    private var state = initialState

    fun currentState(): MtProtoUpdateState = state

    suspend fun accept(ordering: MtProtoChannelUpdateOrdering): MtProtoChannelUpdateOrderingResult =
        acceptBatch(listOf(ordering))

    suspend fun acceptBatch(orderings: List<MtProtoChannelUpdateOrdering>): MtProtoChannelUpdateOrderingResult =
        mutex.withLock {
            if (orderings.isEmpty()) return@withLock MtProtoChannelUpdateOrderingResult.Duplicate
            val channelPts = state.channelPts.toMutableMap()
            var hasFreshUpdate = false
            for (ordering in orderings) {
                val current = channelPts[ordering.channelId] ?: 0
                when {
                    ordering.pts <= current -> Unit
                    ordering.pts != current + ordering.ptsCount -> {
                        return@withLock MtProtoChannelUpdateOrderingResult.Gap(
                            channelId = ordering.channelId,
                            expectedPts = current + ordering.ptsCount,
                            actualPts = ordering.pts,
                        )
                    }
                    else -> {
                        channelPts[ordering.channelId] = ordering.pts
                        hasFreshUpdate = true
                    }
                }
            }
            if (!hasFreshUpdate) return@withLock MtProtoChannelUpdateOrderingResult.Duplicate
            val next = state.copy(channelPts = channelPts.toMap())
            apply(next)
            state = next
            MtProtoChannelUpdateOrderingResult.Applied(next)
        }
}
