package org.monogram.mtproto.updates

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MtProtoUpdateStateTransitionTest {
    private val initial = MtProtoUpdateState(MtProtoUpdateCursor(10, 20, 30, 40), mapOf(100L to 5))

    @Test
    fun `calculates global channel and outer sequence in one state`() = runBlocking {
        val metadata = MtProtoUpdateEnvelopeMetadata(
            global = listOf(MtProtoUpdateOrdering(pts = 11, ptsCount = 1)),
            channels = listOf(MtProtoChannelUpdateOrdering(100, 7, 2)),
            envelope = MtProtoUpdateOrdering(date = 31, seqStart = 41, seq = 41),
        )

        assertEquals(
            MtProtoUpdateStateTransitionResult.Applied(
                MtProtoUpdateState(MtProtoUpdateCursor(11, 20, 31, 41), mapOf(100L to 7))
            ),
            MtProtoUpdateStateTransition.apply(initial, metadata),
        )
    }

    @Test
    fun `does not partially advance state on channel gap`() = runBlocking {
        val metadata = MtProtoUpdateEnvelopeMetadata(
            global = listOf(MtProtoUpdateOrdering(pts = 11, ptsCount = 1)),
            channels = listOf(MtProtoChannelUpdateOrdering(100, 8, 2)),
            envelope = null,
        )

        assertEquals(
            MtProtoUpdateStateTransitionResult.ChannelGap(
                MtProtoChannelUpdateOrderingResult.Gap(100, expectedPts = 7, actualPts = 8)
            ),
            MtProtoUpdateStateTransition.apply(initial, metadata),
        )
    }
}
