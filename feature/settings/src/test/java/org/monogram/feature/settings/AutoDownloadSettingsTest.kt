package org.monogram.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.core.ui.AutoDownloadNetwork
import org.monogram.core.ui.AutoDownloadPreset
import org.monogram.feature.settings.ui.formatAutoDownloadLimit
import org.monogram.feature.settings.ui.parseAutoDownloadNetwork
import org.monogram.feature.settings.ui.autoDownloadNetworkKey
import org.monogram.feature.settings.ui.autoDownloadSummary

class AutoDownloadSettingsTest {
    @Test
    fun networkKeysRoundTrip() {
        assertEquals("wifi", autoDownloadNetworkKey(AutoDownloadNetwork.Wifi))
        assertEquals("mobile", autoDownloadNetworkKey(AutoDownloadNetwork.Mobile))
        assertEquals("roaming", autoDownloadNetworkKey(AutoDownloadNetwork.Roaming))
        assertEquals(AutoDownloadNetwork.Wifi, parseAutoDownloadNetwork("wifi"))
        assertEquals(AutoDownloadNetwork.Mobile, parseAutoDownloadNetwork("mobile"))
        assertEquals(AutoDownloadNetwork.Roaming, parseAutoDownloadNetwork("roaming"))
        assertEquals(AutoDownloadNetwork.Wifi, parseAutoDownloadNetwork("unknown"))
    }

    @Test
    fun sizeLabelsMatchTelegramCaps() {
        assertEquals("500 KB", formatAutoDownloadLimit(512_000))
        assertEquals("1 MB", formatAutoDownloadLimit(AutoDownloadPreset.MB))
        assertEquals("3 MB", formatAutoDownloadLimit(3L * AutoDownloadPreset.MB))
        assertEquals("15 MB", formatAutoDownloadLimit(15L * AutoDownloadPreset.MB))
        assertEquals("2 GB", formatAutoDownloadLimit(2L * AutoDownloadPreset.MB * 1024L))
    }

    @Test
    fun roamingSummaryIsPhotosOnly() {
        assertEquals("photos", autoDownloadSummary(AutoDownloadPreset.ROAMING))
        assertEquals("", autoDownloadSummary(AutoDownloadPreset.ROAMING.copy(enabled = false)))
        assertEquals(
            "photos,videos:${15L * AutoDownloadPreset.MB},gifs:${15L * AutoDownloadPreset.MB},files:${3L * AutoDownloadPreset.MB}",
            autoDownloadSummary(AutoDownloadPreset.WIFI),
        )
    }
}
