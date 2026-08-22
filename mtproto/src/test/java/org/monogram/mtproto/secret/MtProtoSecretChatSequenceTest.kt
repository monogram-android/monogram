package org.monogram.mtproto.secret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MtProtoSecretChatSequenceTest {
    @Test
    fun `accepts in-order messages and reports peer-acknowledged inbound on send`() {
        val sequence = MtProtoSecretChatSequence()

        assertEquals(MtProtoSecretChatSequence.IncomingResult.Accepted(1), sequence.acceptIncoming(1))
        assertEquals(MtProtoSecretChatSequence.IncomingResult.Accepted(2), sequence.acceptIncoming(2))
        assertEquals(2, sequence.maxInSeq)

        val (outSeq, ackInSeq) = sequence.nextOutgoing()
        assertEquals(1, outSeq)
        assertEquals(2, ackInSeq)
        assertEquals(1, sequence.maxOutSeq)
    }

    @Test
    fun `classifies repeats as duplicates and skips as gaps`() {
        val sequence = MtProtoSecretChatSequence(initialInSeq = 3, initialOutSeq = 5)

        assertEquals(MtProtoSecretChatSequence.IncomingResult.Duplicate(3), sequence.acceptIncoming(3))
        assertEquals(MtProtoSecretChatSequence.IncomingResult.Duplicate(2), sequence.acceptIncoming(2))
        assertEquals(3, sequence.maxInSeq)

        assertEquals(
            MtProtoSecretChatSequence.IncomingResult.Gap(seqOut = 6, expectedSeqOut = 4),
            sequence.acceptIncoming(6),
        )
        // Gap must not advance the high-water mark.
        assertEquals(3, sequence.maxInSeq)
        assertEquals(MtProtoSecretChatSequence.IncomingResult.Accepted(4), sequence.acceptIncoming(4))
    }

    @Test
    fun `rejects non positive sequences and negative initialization`() {
        val sequence = MtProtoSecretChatSequence()

        assertThrows(IllegalArgumentException::class.java) { sequence.acceptIncoming(0) }
        assertThrows(IllegalArgumentException::class.java) { MtProtoSecretChatSequence(initialInSeq = -1) }
        assertThrows(IllegalArgumentException::class.java) { MtProtoSecretChatSequence(initialOutSeq = -1) }
    }
}
