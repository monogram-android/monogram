package org.monogram.network.bridge.updates

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.network.bridge.MtprotoUpdate
import uniffi.monogram_mtproto.UpdateEventDto

class ChannelDifferenceMappingTest {
    @Test
    fun channelDifferenceInvalidationMarkerSurvivesMapping() {
        assertEquals(
            listOf(MtprotoUpdate.Ignored("channel_difference_too_long:-10042")),
            UpdateEventDto.Ignored("channel_difference_too_long:-10042").toMtprotoUpdates(),
        )
    }
}
