package org.monogram.mtproto.updates

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Ordering metadata extracted from one live update envelope. */
data class MtProtoUpdateOrdering(
    val pts: Int? = null,
    val ptsCount: Int = 0,
    val qts: Int? = null,
    val qtsCount: Int = 0,
    val date: Int? = null,
    val seqStart: Int? = null,
    val seq: Int? = null,
) {
    init {
        require(pts == null || ptsCount > 0) { "ptsCount must be positive when pts is present" }
        require(qts == null || qtsCount > 0) { "qtsCount must be positive when qts is present" }
        require(seqStart == null || seq != null) { "seq is required when seqStart is present" }
        require(seqStart == null || (seq != null && seqStart <= seq)) { "seqStart must not exceed seq" }
    }
}

sealed interface MtProtoUpdateOrderingResult {
    data class Applied(val cursor: MtProtoUpdateCursor) : MtProtoUpdateOrderingResult
    data object Duplicate : MtProtoUpdateOrderingResult
    data class Gap(
        val expectedPts: Int?,
        val actualPts: Int?,
        val expectedQts: Int?,
        val actualQts: Int?,
        val expectedSeq: Int? = null,
        val actualSeqStart: Int? = null,
    ) : MtProtoUpdateOrderingResult
}

/**
 * Serializes live update application and advances the cursor only after the callback succeeds.
 * Recovery remains the owner of filling gaps; this class only admits contiguous live envelopes.
 */
class MtProtoUpdateOrderingCoordinator(
    initialCursor: MtProtoUpdateCursor,
    private val apply: suspend (MtProtoUpdateCursor) -> Unit,
) {
    private val mutex = Mutex()
    private var cursor = initialCursor

    fun currentCursor(): MtProtoUpdateCursor = cursor

    suspend fun accept(ordering: MtProtoUpdateOrdering): MtProtoUpdateOrderingResult = acceptBatch(listOf(ordering))

    suspend fun acceptBatch(orderings: List<MtProtoUpdateOrdering>): MtProtoUpdateOrderingResult = mutex.withLock {
        if (orderings.isEmpty()) return@withLock MtProtoUpdateOrderingResult.Duplicate
        var next = cursor
        var hasFreshUpdate = false
        for (ordering in orderings) {
            when (val result = evaluate(next, ordering)) {
                is MtProtoUpdateOrderingResult.Applied -> {
                    next = result.cursor
                    hasFreshUpdate = true
                }
                MtProtoUpdateOrderingResult.Duplicate -> Unit
                is MtProtoUpdateOrderingResult.Gap -> return@withLock result
            }
        }
        if (!hasFreshUpdate) return@withLock MtProtoUpdateOrderingResult.Duplicate
        apply(next)
        cursor = next
        MtProtoUpdateOrderingResult.Applied(next)
    }

    private fun evaluate(
        current: MtProtoUpdateCursor,
        ordering: MtProtoUpdateOrdering,
    ): MtProtoUpdateOrderingResult {
        val nextPts = ordering.pts?.let { current.pts + ordering.ptsCount }
        val nextQts = ordering.qts?.let { current.qts + ordering.qtsCount }
        val expectedPts = ordering.pts?.let { current.pts + ordering.ptsCount }
        val expectedQts = ordering.qts?.let { current.qts + ordering.qtsCount }
        val actualSeqStart = ordering.seq?.let { ordering.seqStart ?: it }
        val expectedSeq = ordering.seq?.let { current.seq + 1 }
        val statuses = listOfNotNull(
            ordering.pts?.let { counterStatus(current.pts, it, ordering.ptsCount) },
            ordering.qts?.let { counterStatus(current.qts, it, ordering.qtsCount) },
            ordering.seq?.let {
                when {
                    it <= current.seq -> CounterStatus.DUPLICATE
                    actualSeqStart == expectedSeq -> CounterStatus.CONTIGUOUS
                    else -> CounterStatus.GAP
                }
            },
        )
        val hasMixedReplay = CounterStatus.DUPLICATE in statuses && CounterStatus.CONTIGUOUS in statuses
        if (CounterStatus.GAP in statuses || hasMixedReplay) {
            return MtProtoUpdateOrderingResult.Gap(
                expectedPts = expectedPts,
                actualPts = ordering.pts,
                expectedQts = expectedQts,
                actualQts = ordering.qts,
                expectedSeq = expectedSeq,
                actualSeqStart = actualSeqStart,
            )
        }
        if (statuses.isNotEmpty() && statuses.all { it == CounterStatus.DUPLICATE }) {
            return MtProtoUpdateOrderingResult.Duplicate
        }

        return MtProtoUpdateOrderingResult.Applied(current.copy(
            pts = nextPts ?: current.pts,
            qts = nextQts ?: current.qts,
            date = maxOf(current.date, ordering.date ?: current.date),
            seq = maxOf(current.seq, ordering.seq ?: current.seq),
        ))
    }

    private fun counterStatus(current: Int, actual: Int, count: Int): CounterStatus = when {
        actual <= current -> CounterStatus.DUPLICATE
        actual == current + count -> CounterStatus.CONTIGUOUS
        else -> CounterStatus.GAP
    }

    private enum class CounterStatus { DUPLICATE, CONTIGUOUS, GAP }
}
