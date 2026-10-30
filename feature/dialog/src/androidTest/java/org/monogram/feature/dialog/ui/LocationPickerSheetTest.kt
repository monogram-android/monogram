package org.monogram.feature.dialog.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.monogram.core.models.GeoPlace
import org.monogram.core.ui.theme.MonogramTheme

/**
 * Location window chrome: the copy, the coordinates readout and the two actions.
 *
 * The map itself is not asserted: with no [MapTileStore] the card falls back to its placeholder,
 * and tile rendering is covered by the message-bubble path.
 */
class LocationPickerSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val place = GeoPlace(latitude = 55.7512, longitude = 37.6184)

    private var sent = 0
    private var dismissed = 0

    private var sharedPeriod = 0
    private fun render(
        locating: Boolean = false,
        place: GeoPlace? = this.place,
        live: Boolean = false
    ) {
        val config =
            android.content.res.Configuration(compose.activity.resources.configuration).apply {
                if (live) {
                    setLocale(java.util.Locale("ru")); fontScale = 1.5f
                }
            }
        val localized = compose.activity.createConfigurationContext(config)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalResources provides localized.resources,
                androidx.compose.ui.platform.LocalConfiguration provides config,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    localized.resources.displayMetrics.density,
                    config.fontScale
                ),
            ) {
                MonogramTheme(darkTheme = live, dynamicColor = false) {
                LocationPickerSheet(
                    place = place,
                    locating = locating,
                    mapTiles = null,
                    onSend = { sent++ },
                    onDismiss = { dismissed++ },
                    onShareLive = if (live) ({ sharedPeriod = it }) else null,
                )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun showsTitleCoordinatesAndBothActions() {
        render(live = true)
        evidence("location-picker-initial.png")
        val context = compose.activity.createConfigurationContext(
            android.content.res.Configuration(compose.activity.resources.configuration)
                .apply { setLocale(java.util.Locale("ru")) })
        val title =
            context.getString(org.monogram.feature.dialog.R.string.dialog_location_pick_title)
        val send = context.getString(org.monogram.feature.dialog.R.string.dialog_location_send_this)
        val cancel = context.getString(org.monogram.feature.dialog.R.string.dialog_cancel)
        val share =
            context.getString(org.monogram.feature.dialog.R.string.dialog_location_share_live)
        compose.onAllNodesWithText(title)[0].assertIsDisplayed()
        compose.onNodeWithText("55.7512, 37.6184").assertIsDisplayed()
        compose.onNodeWithText(send).assertIsDisplayed()
        compose.onNodeWithText(context.getString(org.monogram.feature.dialog.R.string.dialog_location_live))
            .performClick()
        compose.onNodeWithText("8 часов").performScrollTo().performClick()
        evidence("location-picker-live.png")
        compose.onNodeWithText(share).performScrollTo().performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(
                28800,
                sharedPeriod
            ); org.junit.Assert.assertEquals(0, sent)
        }
        compose.onNodeWithText(cancel).performScrollTo().assertIsDisplayed()

        compose.onAllNodesWithText(title)[1].performScrollTo().performClick()
        compose.onNodeWithText(send).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("Send did not confirm", sent == 1)

        compose.onNodeWithText(cancel).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("Cancel did not dismiss", dismissed == 1)

        evidence("location-picker.png")
    }

    @Test
    fun readingLocationKeepsTheActionDisabled() {
        render(locating = true, place = null)
        compose.onNodeWithText(compose.activity.getString(org.monogram.feature.dialog.R.string.dialog_location_locating))
            .assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(org.monogram.feature.dialog.R.string.dialog_location_send_this))
            .performClick()
        compose.waitForIdle()
        assertTrue("a location with no fix must not be sent", sent == 0)
        val context = compose.activity
        org.junit.Assert.assertEquals(
            android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        )
        org.junit.Assert.assertEquals(
            android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        )
        kotlinx.coroutines.runBlocking {
            org.junit.Assert.assertNull(kotlinx.coroutines.withTimeout(2_000) {
                org.monogram.core.ui.currentLocation(context, allowCached = false)
            })
        }
        org.junit.Assert.assertNull(org.monogram.core.ui.lastKnownLocation(context))
    }

    @Test
    fun liveSessionNoticesKeepStopUncertaintyAndRequireExplicitResume() {
        val config =
            android.content.res.Configuration(compose.activity.resources.configuration).apply {
                setLocale(java.util.Locale("ru")); fontScale = 1.5f
            }
        val context = compose.activity.createConfigurationContext(config)
        val id = org.monogram.core.models.MessageId(org.monogram.core.models.PeerId(7), 37)
        val expiry = System.currentTimeMillis() / 1000 + 900
        var phase by androidx.compose.runtime.mutableStateOf(org.monogram.core.models.LiveLocationPhase.PAUSED)
        var busy by androidx.compose.runtime.mutableStateOf(false)
        var failure by androidx.compose.runtime.mutableStateOf<org.monogram.core.common.telegram.TelegramError?>(
            null
        )
        var resumed = 0;
        var stopped = 0;
        var opened = 0
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalResources provides context.resources,
                androidx.compose.ui.platform.LocalConfiguration provides config,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    context.resources.displayMetrics.density,
                    1.5f
                ),
            ) {
                MonogramTheme(darkTheme = true, dynamicColor = false) {
                    androidx.compose.material3.Surface {
                        androidx.compose.foundation.layout.Box(
                            androidx.compose.ui.Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(WindowInsets.safeDrawing),
                            contentAlignment = androidx.compose.ui.Alignment.BottomCenter
                        ) {
                            LiveLocationNotices(
                                listOf(
                                    org.monogram.core.models.LiveLocationSession(
                                        id,
                                        123,
                                        expiry,
                                        phase
                                    )
                                ),
                                emptyMap(),
                                failure,
                                if (busy) setOf(37) else emptySet(),
                                emptySet(),
                                { org.junit.Assert.assertEquals(37, it); resumed++ },
                                { org.junit.Assert.assertEquals(37, it); stopped++ },
                                { org.junit.Assert.assertEquals(37, it); opened++ })
                        }
                    }
                }
            }
        }
        val resume = context.getString(org.monogram.feature.dialog.R.string.dialog_location_resume)
        val stop = context.getString(org.monogram.feature.dialog.R.string.dialog_location_stop)
        val paused = context.getString(org.monogram.feature.dialog.R.string.dialog_location_paused)
        compose.onNodeWithText(paused, substring = true).assertIsDisplayed()
        assertTrue(
            "Live controls must stay compact at large font scale",
            compose.onNodeWithTag("live-location-37")
                .fetchSemanticsNode().boundsInRoot.height <= 96 * context.resources.displayMetrics.density
        )
        compose.onNodeWithText(
            context.getString(
                org.monogram.feature.dialog.R.string.dialog_location_until,
                ""
            ), substring = true
        ).performClick()
        compose.onNodeWithContentDescription(resume).performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(
                1,
                resumed
            ); org.junit.Assert.assertEquals(1, opened)
        }
        compose.onNodeWithText(paused, substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(stop).assertIsDisplayed()
        evidence("live-location-paused.png")
        compose.onNodeWithContentDescription(stop).performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(1, stopped); phase =
            org.monogram.core.models.LiveLocationPhase.STOPPING; busy = true
        }
        compose.onNodeWithTag("live-location-stop-37").assertIsNotEnabled()
        compose.onNodeWithContentDescription(resume).assertDoesNotExist()
        compose.runOnIdle {
            phase = org.monogram.core.models.LiveLocationPhase.UNCERTAIN_STOP; busy = false
        }
        compose.onNodeWithText(
            context.getString(org.monogram.feature.dialog.R.string.dialog_location_stop_uncertain),
            substring = true
        ).assertIsDisplayed()
        compose.onNodeWithContentDescription(stop).assertIsDisplayed()
        evidence("live-location-uncertain.png")
        compose.onNodeWithContentDescription(stop).performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(
                2,
                stopped
            ); org.junit.Assert.assertEquals(1, resumed)
        }
        compose.onNodeWithText(
            context.getString(org.monogram.feature.dialog.R.string.dialog_location_stop_uncertain),
            substring = true
        ).assertIsDisplayed()
        compose.runOnIdle {
            phase = org.monogram.core.models.LiveLocationPhase.ACTIVE; failure =
            org.monogram.core.common.telegram.TelegramError.parse("RPC 500: INTERNAL")
        }
        compose.onNodeWithContentDescription(resume).performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(
                2,
                resumed
            ); org.junit.Assert.assertEquals(2, stopped); phase =
            org.monogram.core.models.LiveLocationPhase.STOPPED
        }
        compose.onNodeWithContentDescription(stop).assertDoesNotExist()
        compose.runOnIdle { phase = org.monogram.core.models.LiveLocationPhase.EXPIRED }
        compose.onNodeWithContentDescription(stop).assertDoesNotExist()
    }

    /** Visual evidence for review; the file lands in the test app's cache dir. */
    private fun evidence(name: String) {
        compose.waitForIdle()
        val frame = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, name).outputStream().use {
            frame.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        frame.recycle()
    }
}
