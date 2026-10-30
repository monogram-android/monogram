package org.monogram.feature.dialog.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.R

class AttachDockInteractionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun allActionsRemainVisibleWithLargeLocalizedLabels() {
        val configuration =
            android.content.res.Configuration(compose.activity.resources.configuration).apply {
                setLocale(java.util.Locale("ru"))
                fontScale = 1.5f
            }
        val context = compose.activity.createConfigurationContext(configuration)
        val calls = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalResources provides context.resources,
                LocalDensity provides Density(density.density, 1.5f)
            ) {
                MonogramTheme(darkTheme = true, dynamicColor = false) {
                    Surface {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            AttachDock(
                                AttachBody.Gallery,
                                true,
                                true,
                                { calls += "gallery" },
                                { calls += "file" },
                                { calls += "location" },
                                onCreatePoll = { calls += "poll" },
                                onCreateChecklist = { calls += "checklist" })
                        }
                    }
                }
            }
        }
        listOf(
            R.string.dialog_attach_gallery,
            R.string.dialog_attach_file,
            R.string.dialog_location_action,
            R.string.dialog_poll_create,
            R.string.dialog_checklist_create
        ).forEach {
            compose.onNodeWithContentDescription(context.getString(it)).assertIsDisplayed()
                .performClick()
        }
        compose.runOnIdle {
            assertEquals(
                listOf("gallery", "file", "location", "poll", "checklist"),
                calls
            )
        }
        compose.waitForIdle()
        composerUiProof("attach-actions.png")
    }
}
