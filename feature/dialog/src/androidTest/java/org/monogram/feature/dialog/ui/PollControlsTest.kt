package org.monogram.feature.dialog.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.monogram.core.models.Poll
import org.monogram.core.models.PollAnswer
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.R

class PollControlsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun openActions(description: String = context.getString(org.monogram.core.ui.R.string.media_more_actions)) {
        compose.onNodeWithContentDescription(description).performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    private fun poll(
        multiple: Boolean = true,
        answered: Boolean = false,
        quiz: Boolean = false,
        closed: Boolean = false
    ) = Poll(
        question = "Synthetic poll", multipleChoice = multiple, isQuiz = quiz, closed = closed,
        answers = listOf(
            PollAnswer("First", chosen = answered, option = byteArrayOf(0, -1)),
            PollAnswer("Second", option = byteArrayOf(-128, 0))
        ),
    )

    @Test
    fun multipleChoiceWaitsForSubmitPreservesOpaqueBytesAndRestoresSelection() {
        val restoration = StateRestorationTester(compose)
        val votes = mutableListOf<List<ByteArray>>()
        var busy by mutableStateOf(false)
        val model = poll()
        restoration.setContent {
            MonogramTheme(dynamicColor = false) {
                PollBubbleCard(model, { votes += it; busy = true }, null, voting = busy)
            }
        }
        val submit = context.getString(R.string.dialog_poll_submit)
        compose.onNodeWithText(submit).assertIsNotEnabled()
        compose.onNodeWithText("First").performClick()
        compose.onNodeWithText("Second").performClick()
        compose.runOnIdle { assertTrue(votes.isEmpty()) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("First").assertIsOn()
        compose.onNodeWithText("Second").assertIsOn()
        compose.onNodeWithText(submit).performClick()
        compose.runOnIdle {
            assertEquals(1, votes.size)
            assertArrayEquals(byteArrayOf(0, -1), votes.single()[0])
            assertArrayEquals(byteArrayOf(-128, 0), votes.single()[1])
        }
        compose.onNodeWithText("First").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.dialog_poll_updating)).assertIsDisplayed()
    }

    @Test
    fun retractSendsEmptyVectorAndAnsweredQuizAndClosedPollAreReadOnly() {
        var model by mutableStateOf(poll(answered = true).copy(creator = true))
        val votes = mutableListOf<List<ByteArray>>()
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                PollBubbleCard(
                    model,
                    { votes += it },
                    null,
                    onClose = {})
            }
        }
        val retract = context.getString(R.string.dialog_poll_retract)
        openActions()
        compose.runOnIdle { assertTrue(votes.isEmpty()) }
        composerUiProof("poll-message-action-menu.png")
        compose.onNodeWithText(context.getString(R.string.dialog_poll_close)).assertIsDisplayed()
        compose.onNodeWithText(retract).performClick()
        compose.runOnIdle {
            assertEquals(1, votes.size); assertTrue(
            votes.single().isEmpty()
        ); model = poll(answered = true, quiz = true)
        }
        compose.onNodeWithText(retract).assertDoesNotExist()
        compose.onNodeWithText("Second").assertIsNotEnabled()
        compose.runOnIdle { model = poll(closed = true) }
        compose.onNodeWithText(retract).assertDoesNotExist()
        compose.onNodeWithText("First").assertIsNotEnabled()
    }

    @Test
    fun closePollRequiresConfirmationSurvivesRestorationAndDisappearsAfterAuthoritativeClose() {
        val restoration = StateRestorationTester(compose)
        var model by mutableStateOf(poll().copy(creator = true))
        var closes = 0
        restoration.setContent {
            MonogramTheme(dynamicColor = false) {
                PollBubbleCard(model, {}, null, onClose = { closes++ })
            }
        }
        val close = context.getString(R.string.dialog_poll_close)
        val cancel = context.getString(R.string.dialog_cancel)
        openActions()
        compose.onNodeWithText(close).performClick()
        compose.runOnIdle { assertEquals(0, closes) }
        compose.onNodeWithText(cancel).performClick()
        compose.runOnIdle { assertEquals(0, closes) }
        openActions()
        compose.onNodeWithText(close).performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(context.getString(R.string.dialog_poll_close_confirm))
            .assertIsDisplayed()
        compose.onNode(hasText(close) and hasClickAction() and hasAnyAncestor(isDialog()))
            .performClick()
        compose.runOnIdle { assertEquals(1, closes); model = model.copy(closed = true) }
        compose.onNodeWithText(close).assertDoesNotExist()
    }

    @Test
    fun closePollIsHiddenForNonCreatorAndDisabledWhileVoting() {
        var model by mutableStateOf(poll())
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                PollBubbleCard(model, {}, null, voting = true, onClose = { error("busy close") })
            }
        }
        val close = context.getString(R.string.dialog_poll_close)
        compose.onNodeWithText(close).assertDoesNotExist()
        compose.runOnIdle { model = model.copy(creator = true) }
        compose.onNodeWithContentDescription(context.getString(org.monogram.core.ui.R.string.media_more_actions))
            .assertIsNotEnabled()
    }

    @Test
    fun answeredPollWithRevotingDisabledHasNoRetractionControl() {
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                PollBubbleCard(
                    poll(answered = true).copy(revotingDisabled = true),
                    { error("forbidden vote") },
                    null
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.dialog_poll_retract)).assertDoesNotExist()
        compose.onNodeWithText("First").assertIsNotEnabled()
        compose.onNodeWithText("Second").assertIsNotEnabled()
    }

    @Test
    fun russianDarkLargeTextKeepsSelectionAndSubmitVisible() {
        val localized = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag("ru")); fontScale = 1.5f
            })
        var submitted = false
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalResources provides localized.resources,
                androidx.compose.ui.platform.LocalConfiguration provides localized.resources.configuration,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    density.density,
                    1.5f
                ),
            ) {
                MonogramTheme(darkTheme = true, dynamicColor = false) {
                    PollBubbleCard(
                        poll().copy(
                            question = "Синтетический опрос с длинным вопросом",
                            creator = true
                        ), { submitted = true }, null, onClose = {})
                }
            }
        }
        compose.onNodeWithText("First").assertIsDisplayed().performClick()
        compose.onNodeWithText(localized.getString(R.string.dialog_poll_submit)).assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertTrue(submitted) }
        val image = compose.onRoot().captureToImage()
        val bitmap = image.asAndroidBitmap()
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let { java.io.File(it) }
            ?: context.getExternalFilesDir(null)!!
        directory.mkdirs()
        val output = java.io.File(directory, "poll-russian-dark-large.png")
        output.outputStream()
            .use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        openActions(localized.getString(org.monogram.core.ui.R.string.media_more_actions))
        compose.onNodeWithText(localized.getString(R.string.dialog_poll_close)).performClick()
        compose.onNodeWithText(localized.getString(R.string.dialog_poll_close_confirm))
            .assertIsDisplayed()
        compose.onNodeWithText(localized.getString(R.string.dialog_cancel)).assertIsDisplayed()
        val confirmation = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        java.io.File(directory, "poll-close-russian-dark-large.png").outputStream().use {
            confirmation.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun resultPercentagesUseTotalVotersAndLongAnswersRemainReadable() {
        val longAnswer =
            "A longer answer that must stay readable across multiple lines in the message card"
        val model = poll(answered = true).copy(
            totalVoters = 4, answers = listOf(
                PollAnswer(longAnswer, chosen = true, voters = 3, option = byteArrayOf(1)),
                PollAnswer("Second", voters = 1, option = byteArrayOf(2)),
            )
        )
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                PollBubbleCard(
                    model,
                    {},
                    null
                )
            }
        }
        val formatter = java.text.NumberFormat.getPercentInstance()
        compose.onNodeWithText(formatter.format(0.75)).assertIsDisplayed()
        compose.onNodeWithText(formatter.format(0.25)).assertIsDisplayed()
        compose.onNodeWithText(longAnswer).assertIsDisplayed()
        composerUiProof("poll-message-results.png")
    }

    @Test
    fun singleChoiceVotesImmediatelyAndMissingPermissionDisablesRows() {
        var allowed by mutableStateOf(true)
        val votes = mutableListOf<List<ByteArray>>()
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                PollBubbleCard(
                    poll(multiple = false), if (allowed) {
                        { votes += it }
                    } else null, null)
            }
        }
        compose.onNodeWithText("Second").performClick()
        compose.runOnIdle {
            assertEquals(1, votes.size); assertArrayEquals(
            byteArrayOf(-128, 0),
            votes.single().single()
        ); allowed = false
        }
        compose.onNodeWithText("First").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.dialog_poll_submit)).assertDoesNotExist()
    }
}
