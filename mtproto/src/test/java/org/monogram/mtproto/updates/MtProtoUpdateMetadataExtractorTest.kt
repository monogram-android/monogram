package org.monogram.mtproto.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateChatParticipant
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatePtsChanged
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateShort
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDeleteChannelMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDeleteMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesCombined
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesTooLong

class MtProtoUpdateMetadataExtractorTest {
    @Test
    fun `extracts global pts and envelope sequence`() {
        val result = MtProtoUpdateMetadataExtractor.extract(
            UpdatesCombined(
                updates = listOf(UpdateDeleteMessages(emptyList(), pts = 12, ptsCount = 2)),
                users = emptyList(),
                chats = emptyList(),
                date = 50,
                seqStart = 8,
                seq = 9,
            )
        ) as MtProtoUpdateMetadataResult.Ordered

        assertEquals(listOf(MtProtoUpdateOrdering(12, 2)), result.metadata.global)
        assertEquals(MtProtoUpdateOrdering(date = 50, seqStart = 8, seq = 9), result.metadata.envelope)
    }

    @Test
    fun `extracts channel pts classifies exhaustively and resyncs on pts change`() {
        val channel = MtProtoUpdateMetadataExtractor.extract(
            UpdatesCombined(
                updates = listOf(UpdateDeleteChannelMessages(100, emptyList(), 7, 1)),
                users = emptyList(),
                chats = emptyList(),
                date = 50,
                seqStart = 8,
                seq = 9,
            )
        ) as MtProtoUpdateMetadataResult.Ordered
        assertEquals(listOf(MtProtoChannelUpdateOrdering(100, 7, 1)), channel.metadata.channels)

        // Informational updates without counters are order-free, not session-fatal.
        val orderFree = MtProtoUpdateMetadataExtractor.extract(
            UpdatesCombined(listOf(UpdateChannel(100), UpdateConfig), emptyList(), emptyList(), 50, 8, 9)
        ) as MtProtoUpdateMetadataResult.Ordered
        assertTrue(orderFree.metadata.global.isEmpty() && orderFree.metadata.channels.isEmpty())

        // Bot/business qts-bearing updates advance the global qts counter.
        val qts = MtProtoUpdateMetadataExtractor.extract(
            UpdateShort(UpdateChatParticipant(1L, 2, 3L, 4L, null, null, null, 77), date = 5)
        ) as MtProtoUpdateMetadataResult.Ordered
        assertEquals(listOf(MtProtoUpdateOrdering(qts = 77, qtsCount = 1, date = 5)), qts.metadata.global)

        // A server-side state invalidation forces a full resync.
        assertEquals(
            MtProtoUpdateMetadataResult.RecoveryRequired,
            MtProtoUpdateMetadataExtractor.extract(UpdateShort(UpdatePtsChanged, date = 5)),
        )
        assertEquals(MtProtoUpdateMetadataResult.RecoveryRequired, MtProtoUpdateMetadataExtractor.extract(UpdatesTooLong))
    }
}
