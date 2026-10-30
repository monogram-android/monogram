package org.monogram.feature.dialog.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.monogram.core.models.*
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.R
import org.monogram.feature.dialog.ViewerPageState

class ViewerPagesInteractionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun viewer(id: Long, reaction: String) =
        MessageViewer(PeerId(id), 100, title = "Fixture $id", emoticon = reaction)

    @Test
    fun fiftyToSixtyEntriesRemainReachableInRussianDarkLargeText() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val localized = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag("ru")); fontScale = 1.5f
            })
        var users by mutableStateOf((1L..50L).map { viewer(it, "👍") })
        var page by mutableStateOf(ViewerPageState(loaded = true, nextOffset = "second"))
        var opened: Long? = null
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalResources provides localized.resources,
                androidx.compose.ui.platform.LocalConfiguration provides localized.resources.configuration,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    density.density,
                    1.5f
                )
            ) {
                MonogramTheme(darkTheme = true, dynamicColor = false) {
                    PeerListSheet(
                        "reaction",
                        users,
                        { null },
                        { opened = it },
                        {},
                        page = page,
                        onLoadMore = {
                            users = users + (51L..60L).map { viewer(it, "👍") }; page =
                            page.copy(nextOffset = null)
                        })
                }
            }
        }
        compose.onNodeWithText(localized.getString(R.string.dialog_peer_list_load_more))
            .assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(60, users.size) }
        compose.onNode(
            hasScrollAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange),
            useUnmergedTree = true
        ).performScrollToIndex(59)
        compose.onNodeWithText("Fixture 60", useUnmergedTree = true).assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(60L, opened) }
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let { java.io.File(it) } ?: context.getExternalFilesDir(null)!!
        directory.mkdirs()
        compose.runOnUiThread {
            val view = android.view.inspector.WindowInspector.getGlobalWindowViews().last()
            val image = android.graphics.Bitmap.createBitmap(
                view.width,
                view.height,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            view.draw(android.graphics.Canvas(image))
            java.io.File(directory, "viewer-pages-russian-dark-large.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test
    fun filterSurvivesLoadingFailureRetryAndSecondPageArrival() {
        var users by mutableStateOf(listOf(viewer(1, "👍"), viewer(2, "🔥")))
        var page by mutableStateOf(ViewerPageState(loaded = true, nextOffset = "next"))
        var requests = 0
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                PeerListSheet(
                    "reaction",
                    users,
                    { null },
                    {},
                    {},
                    initialFilter = reactionChoiceKey("👍", null),
                    page = page,
                    onLoadMore = { requests++; page = page.copy(loading = true) })
            }
        }
        val more = context.getString(R.string.dialog_peer_list_load_more)
        val retry = context.getString(org.monogram.core.ui.R.string.status_retry)
        compose.onNodeWithText("Fixture 1").assertIsDisplayed()
        compose.onNodeWithText("Fixture 2").assertDoesNotExist()
        compose.onNodeWithText(more).performClick()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.runOnIdle { page = page.copy(loading = false, failed = true) }
        compose.onNodeWithText(context.getString(R.string.dialog_peer_list_load_failed))
            .assertIsDisplayed()
        compose.onNodeWithText("Fixture 1").assertIsDisplayed()
        compose.onNodeWithText(retry).performClick()
        compose.runOnIdle {
            assertEquals(2, requests)
            users = users + viewer(3, "👍")
            page = page.copy(loading = false, failed = false, nextOffset = null)
        }
        compose.onNodeWithText("Fixture 3").assertIsDisplayed()
        compose.onNodeWithText("Fixture 2").assertDoesNotExist()
        compose.onNodeWithText(more).assertDoesNotExist()
        compose.onNodeWithText(retry).assertDoesNotExist()
    }

    @Test
    fun initialFailureOffersRetryInsteadOfEndlessLoading() {
        var requests = 0
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                PeerListSheet(
                    "poll",
                    null,
                    { null },
                    {},
                    {},
                    page = ViewerPageState(failed = true),
                    onLoadMore = { requests++ })
            }
        }
        compose.onNodeWithText(context.getString(R.string.dialog_peer_list_load_failed))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(org.monogram.core.ui.R.string.status_retry))
            .performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }
}
