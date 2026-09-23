package org.monogram.core.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSettingsTest {
    @After
    fun tearDown() {
        DownloadSettings.resetForTests()
    }

    @Test
    fun telegramDefaultsMatchWifiMobileRoaming() {
        assertTrue(AutoDownloadPreset.WIFI.photos)
        assertTrue(AutoDownloadPreset.WIFI.videos)
        assertTrue(AutoDownloadPreset.WIFI.gifs)
        assertTrue(AutoDownloadPreset.WIFI.files)
        assertEquals(15L * AutoDownloadPreset.MB, AutoDownloadPreset.WIFI.maxVideoBytes)
        assertEquals(3L * AutoDownloadPreset.MB, AutoDownloadPreset.WIFI.maxFileBytes)
        assertTrue(AutoDownloadPreset.WIFI.preloadVideo)

        assertEquals(10L * AutoDownloadPreset.MB, AutoDownloadPreset.MOBILE.maxVideoBytes)
        assertEquals(1L * AutoDownloadPreset.MB, AutoDownloadPreset.MOBILE.maxFileBytes)

        assertTrue(AutoDownloadPreset.ROAMING.photos)
        assertFalse(AutoDownloadPreset.ROAMING.videos)
        assertFalse(AutoDownloadPreset.ROAMING.gifs)
        assertFalse(AutoDownloadPreset.ROAMING.files)
        assertFalse(AutoDownloadPreset.ROAMING.preloadVideo)
    }

    @Test
    fun roamingBlocksVideosGifsAndFiles() {
        val roaming = AutoDownloadPreset.ROAMING
        assertTrue(roaming.allowsDisplay("photo", 2_000_000))
        assertFalse(roaming.allowsFull("video", 400_000))
        assertFalse(roaming.allowsFull("gif", 100_000))
        assertFalse(roaming.allowsFull("document", 100_000))
        assertFalse(roaming.allowsStream("video", 400_000))
    }

    @Test
    fun wifiAllowsPhotosAndSizedFiles() {
        val wifi = AutoDownloadPreset.WIFI
        assertTrue(wifi.allowsDisplay("photo", null))
        assertTrue(wifi.allowsFull("gif", 2_000_000))
        assertTrue(wifi.allowsFull("document", 2_000_000))
        assertFalse(wifi.allowsFull("document", 4L * AutoDownloadPreset.MB))
        assertTrue(wifi.allowsFull("video", 10L * AutoDownloadPreset.MB))
        assertFalse(wifi.allowsFull("video", 20L * AutoDownloadPreset.MB))
        assertTrue(wifi.allowsStream("video", 20L * AutoDownloadPreset.MB))
        assertFalse(wifi.allowsFull("photo", 100_000))
        assertTrue(wifi.allowsFull("document", 100_000, userRequested = true))
    }

    @Test
    fun imageDocumentsFollowFilesNotPhotos() {
        val wifi = AutoDownloadPreset.WIFI
        val roaming = AutoDownloadPreset.ROAMING
        assertFalse(wifi.allowsDisplay("document", 200_000))
        assertTrue(wifi.allowsFull("document", 200_000))
        assertFalse(roaming.allowsFull("document", 200_000))
        assertFalse(wifi.allowsFull("document", null))
    }

    @Test
    fun encodeRoundTripAndDisabledPreset() {
        val original = AutoDownloadPreset.MOBILE.copy(files = false, maxFileBytes = 2L * AutoDownloadPreset.MB)
        assertEquals(original, AutoDownloadPreset.decode(original.encode(), AutoDownloadPreset.WIFI))
        val off = AutoDownloadPreset.WIFI.copy(enabled = false)
        assertFalse(off.allowsDisplay("photo", 1_000))
        assertFalse(off.allowsFull("gif", 1_000))
        assertTrue(off.allowsFull("gif", 1_000, userRequested = true))
        assertEquals(AutoDownloadPreset.WIFI, AutoDownloadPreset.decode("bad", AutoDownloadPreset.WIFI))
    }

    @Test
    fun networkOverrideSelectsPreset() {
        DownloadSettings.setNetworkOverride(AutoDownloadNetwork.Roaming)
        assertEquals(AutoDownloadNetwork.Roaming, DownloadSettings.activeNetwork())
        assertEquals(AutoDownloadPreset.ROAMING, DownloadSettings.activePreset())
        DownloadSettings.setNetworkOverride(AutoDownloadNetwork.Mobile)
        assertEquals(AutoDownloadPreset.MOBILE, DownloadSettings.activePreset())
    }

    @Test
    fun cycleDebugNetworkWalksWifiMobileRoaming() {
        DownloadSettings.setNetworkOverride(AutoDownloadNetwork.Wifi)
        DownloadSettings.cycleDebugNetwork()
        assertEquals(AutoDownloadNetwork.Mobile, DownloadSettings.activeNetwork())
        DownloadSettings.cycleDebugNetwork()
        assertEquals(AutoDownloadNetwork.Roaming, DownloadSettings.activeNetwork())
        DownloadSettings.cycleDebugNetwork()
        assertEquals(AutoDownloadNetwork.Wifi, DownloadSettings.activeNetwork())
    }
}
