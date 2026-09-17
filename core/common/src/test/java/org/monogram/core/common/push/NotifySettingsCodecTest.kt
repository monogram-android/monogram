package org.monogram.core.common.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.core.models.NotifySettings

class NotifySettingsCodecTest {
    @Test
    fun roundTripsEveryField() {
        val settings = NotifySettings(
            showPreviews = false,
            silent = true,
            muteUntil = Int.MAX_VALUE,
            storiesMuted = true,
            storiesHideSender = true,
            sound = "custom-ring",
        )

        assertEquals(settings, NotifySettingsCodec.decode(NotifySettingsCodec.encode(settings)))
    }

    @Test
    fun unknownAndMalformedEntriesAreRejected() {
        assertNull(NotifySettingsCodec.decode(null))
        assertNull(NotifySettingsCodec.decode(""))
        assertNull(NotifySettingsCodec.decode("not-a-number"))
    }

    @Test
    fun missingTrailingFieldsFallBackToDefaults() {
        // An entry written before a field existed keeps notifications enabled and the default sound.
        val decoded = NotifySettingsCodec.decode("1700000000|0")
        assertEquals(1700000000, decoded?.muteUntil)
        assertEquals(false, decoded?.showPreviews)
        assertEquals(true, decoded?.silent)
        assertEquals(true, decoded?.storiesMuted)
        assertEquals(true, decoded?.storiesHideSender)
        assertEquals(NotifySettings().sound, decoded?.sound)
    }

    @Test
    fun mutedForeverSurvivesTheRoundTrip() {
        val decoded = NotifySettingsCodec.decode(
            NotifySettingsCodec.encode(NotifySettings(muteUntil = Int.MAX_VALUE)),
        )
        assertEquals(true, decoded?.isMuted(Int.MAX_VALUE - 1))
    }
}
