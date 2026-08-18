package org.monogram.mtproto.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateChannel
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
    fun `extracts channel pts and fails closed for unsupported updates`() {
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

        val unsupported = MtProtoUpdateMetadataExtractor.extract(
            UpdatesCombined(listOf(UpdateChannel(100)), emptyList(), emptyList(), 50, 8, 9)
        )
        assertTrue(unsupported is MtProtoUpdateMetadataResult.Unsupported)
        assertEquals(MtProtoUpdateMetadataResult.RecoveryRequired, MtProtoUpdateMetadataExtractor.extract(UpdatesTooLong))
    }
}
