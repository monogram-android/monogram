package org.monogram.feature.dialog.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    private fun render(locating: Boolean = false, place: GeoPlace? = this.place) {
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                LocationPickerSheet(
                    place = place,
                    locating = locating,
                    mapTiles = null,
                    onSend = { sent++ },
                    onDismiss = { dismissed++ },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun showsTitleCoordinatesAndBothActions() {
        render()
        compose.onNodeWithText("Location").assertIsDisplayed()
        compose.onNodeWithText("55.7512, 37.6184").assertIsDisplayed()
        compose.onNodeWithText("Send this location").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()

        compose.onNodeWithText("Send this location").performClick()
        compose.waitForIdle()
        assertTrue("Send did not confirm", sent == 1)

        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue("Cancel did not dismiss", dismissed == 1)

        evidence("location-picker.png")
    }

    @Test
    fun readingLocationKeepsTheActionDisabled() {
        render(locating = true, place = null)
        compose.onNodeWithText("Finding your location…").assertIsDisplayed()
        compose.onNodeWithText("Send this location").performClick()
        compose.waitForIdle()
        assertTrue("a location with no fix must not be sent", sent == 0)
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
