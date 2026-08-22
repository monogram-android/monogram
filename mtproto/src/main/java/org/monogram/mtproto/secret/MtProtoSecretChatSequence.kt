package org.monogram.mtproto.secret

/**
 * Per-chat secret-message sequence state (`seq_in`/`seq_out` counters).
 *
 * Senders increment [nextOutgoingSeq] and attach the peer's acknowledged inbound count;
 * receivers accept only the next expected outbound sequence, treating anything at or below
 * the high-water mark as a duplicate and anything beyond it as a gap (missing messages).
 */
class MtProtoSecretChatSequence(
    initialInSeq: Int = 0,
    private val initialOutSeq: Int = 0,
) {
    init {
        require(initialInSeq >= 0) { "initial in sequence must not be negative" }
        require(initialOutSeq >= 0) { "initial out sequence must not be negative" }
    }

    @Volatile
    var maxInSeq: Int = initialInSeq
        private set

    @Volatile
    var maxOutSeq: Int = initialOutSeq
        private set

    sealed interface IncomingResult {
        /** In-order message; the inbound counter advanced to [seqOut]. */
        data class Accepted(val seqOut: Int) : IncomingResult

        /** Already processed; safe to drop. */
        data class Duplicate(val seqOut: Int) : IncomingResult

        /** Messages before this one are missing; do not advance. */
        data class Gap(val seqOut: Int, val expectedSeqOut: Int) : IncomingResult
    }

    fun acceptIncoming(seqOut: Int): IncomingResult = synchronized(this) {
        require(seqOut > 0) { "outbound sequence must be positive" }
        when {
            seqOut <= maxInSeq -> IncomingResult.Duplicate(seqOut)
            seqOut == maxInSeq + 1 -> {
                maxInSeq = seqOut
                IncomingResult.Accepted(seqOut)
            }
            else -> IncomingResult.Gap(seqOut, maxInSeq + 1)
        }
    }

    /** Allocates the sender's next `seq_out` and reports the peer-acknowledged `seq_in`. */
    fun nextOutgoing(): Pair<Int, Int> = synchronized(this) {
        maxOutSeq += 1
        maxOutSeq to maxInSeq
    }
}
