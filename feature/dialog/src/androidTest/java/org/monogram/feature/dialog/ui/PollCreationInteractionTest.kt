package org.monogram.feature.dialog.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.*
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.R

class PollCreationInteractionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun closeStaysReachableWhenFocusMovesBetweenPollAndTaskFields() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        var kind by mutableStateOf(MessageCreationKind.Poll)
        var visible by mutableStateOf(true)
        var dismissed = 0
        val context = compose.activity
        compose.setContent {
            MonogramTheme(darkTheme = false, dynamicColor = false) {
                if (visible) MessageCreationSheet(
                    kind, null, false, null,
                    { _, _ -> error("unexpected send") }, { _, _ -> error("unexpected send") },
                    { visible = false; dismissed++ })
            }
        }
        MessageCreationKind.entries.forEach { current ->
            compose.runOnIdle { kind = current; visible = true }
            val checklist = current == MessageCreationKind.Checklist
            val title =
                context.getString(if (checklist) R.string.dialog_checklist_title else R.string.dialog_poll_question)
            val item = context.getString(
                if (checklist) R.string.dialog_checklist_item else R.string.dialog_poll_option,
                1
            )
            val close =
                compose.onNodeWithContentDescription(context.getString(R.string.dialog_cancel))
            compose.onNode(hasSetTextAction() and hasText(title)).performScrollTo().performClick()
                .performTextInput("Synthetic title")
            compose.waitForIdle()
            close.assertIsDisplayed()
            val top = close.fetchSemanticsNode().boundsInWindow.top
            val imeHeights = mutableListOf<Int>()
            val popup = android.view.inspector.WindowInspector.getGlobalWindowViews().last {
                (it.layoutParams as? android.view.WindowManager.LayoutParams)?.title == "Pop-Up Window"
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                (androidx.core.view.ViewCompat.getRootWindowInsets(popup)
                    ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0) > 0
            }
            val listener = android.view.ViewTreeObserver.OnPreDrawListener {
                imeHeights += androidx.core.view.ViewCompat.getRootWindowInsets(popup)
                    ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
                true
            }
            compose.runOnIdle { popup.viewTreeObserver.addOnPreDrawListener(listener) }
            compose.onNode(hasSetTextAction() and hasText(item)).performScrollTo().performClick()
                .performTextInput("Synthetic item")
            compose.waitForIdle()
            compose.runOnIdle { popup.viewTreeObserver.removeOnPreDrawListener(listener) }
            assertTrue(
                "IME disappeared during field switch: $imeHeights",
                imeHeights.isNotEmpty() && imeHeights.all { it > 0 })
            close.assertIsDisplayed()
            assertEquals(top, close.fetchSemanticsNode().boundsInWindow.top, 2f)
            composerUiProof(if (checklist) "checklist-keyboard.png" else "poll-keyboard.png")
            close.performClick()
            compose.waitUntil { !visible }
        }
        compose.runOnIdle { assertEquals(2, dismissed) }
    }

    @Test
    fun addingAndRemovingOptionsKeepsRemainingTextAndOrder() {
        compose.setContent {
            MonogramTheme(darkTheme = false, dynamicColor = false) {
                MessageCreationSheet(
                    MessageCreationKind.Poll, null, false, null,
                    { _, _ -> }, { _, _ -> }, {})
            }
        }
        val context = compose.activity
        fun option(index: Int) = compose.onNode(
            hasSetTextAction() and hasText(
                context.getString(
                    R.string.dialog_poll_option,
                    index
                )
            )
        )
        option(1).performTextInput("First")
        option(2).performTextInput("Second")
        compose.onNodeWithText(context.getString(R.string.dialog_poll_add_option)).performScrollTo()
            .performClick()
        option(3).performScrollTo().performTextInput("Third")
        compose.onNodeWithContentDescription(
            context.getString(R.string.dialog_delete) + ": " +
                    context.getString(R.string.dialog_poll_option, 2)
        ).performScrollTo().performClick()
        compose.waitForIdle()
        option(1).performScrollTo().assertTextContains("First")
        option(2).performScrollTo().assertTextContains("Third")
        compose.onNodeWithText("Second").assertDoesNotExist()
    }

    @Test
    fun quizValidationRestorationAndFailedAdmissionPreserveFormUntilConfirmation() {
        val restore = StateRestorationTester(compose)
        val context = compose.activity.createConfigurationContext(
            android.content.res.Configuration(compose.activity.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag("ru")); fontScale = 1.5f
            })
        var admission by mutableStateOf<Long?>(null)
        var error by mutableStateOf<TelegramError?>(null)
        val calls = mutableListOf<Pair<PollSendSpec, MessageSendOptions>>()
        var dismissed = 0
        var allowed by mutableStateOf(true)
        restore.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalResources provides context.resources,
                androidx.compose.ui.platform.LocalConfiguration provides context.resources.configuration,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    density.density,
                    1.5f
                ),
            ) {
                MonogramTheme(darkTheme = true, dynamicColor = false) {
                    MessageCreationSheet(
                        MessageCreationKind.Poll,
                        admission,
                        false,
                        error,
                        { spec, options ->
                            calls += spec to options; error =
                            TelegramError.parse("RPC 500: INTERNAL")
                        },
                        { _, _ -> error("unexpected checklist send") },
                        { dismissed++ },
                        allowed = allowed
                    )
                }
            }
        }
        val send = context.getString(R.string.dialog_send)
        compose.onNodeWithText(send).performScrollTo().assertIsNotEnabled()
        fun fill(label: String, value: String) {
            compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo()
                .performTextInput(value)
        }
        fill(context.getString(R.string.dialog_poll_question), "Synthetic quiz")
        fill(context.getString(R.string.dialog_poll_option, 1), "First")
        fill(context.getString(R.string.dialog_poll_option, 2), "Second")
        compose.onNodeWithText(context.getString(R.string.dialog_poll_quiz)).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText(context.getString(R.string.dialog_poll_quiz)).assertIsOn()
        compose.onNodeWithText(send).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription(
            context.getString(
                R.string.dialog_poll_correct_option,
                2
            )
        ).performScrollTo().performClick()
        fill(context.getString(R.string.dialog_poll_explanation), "Synthetic explanation")
        compose.onNodeWithText(context.getString(R.string.dialog_send_silent)).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText(context.getString(R.string.dialog_send_silent)).assertIsOn()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(context.getString(R.string.dialog_send_silent)).assertIsOn()
        compose.onNode(hasSetTextAction() and hasText("Synthetic quiz")).performScrollTo()
            .assertTextContains("Synthetic quiz")
        compose.onNodeWithContentDescription(
            context.getString(
                R.string.dialog_poll_correct_option,
                2
            )
        ).performScrollTo().assertIsSelected()
        compose.waitForIdle()
        composerUiProof("poll-sheet.png")
        compose.onNodeWithText(send).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, calls.size); assertEquals(0, dismissed)
            assertEquals(
                PollSendSpec(
                    "Synthetic quiz",
                    listOf("First", "Second"),
                    quiz = true,
                    correctAnswer = 1,
                    solution = "Synthetic explanation"
                ), calls.single().first
            )
            assertTrue(calls.single().second.silent)
        }
        compose.runOnIdle { allowed = false }
        compose.onNodeWithText(send).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.dialog_send_options))
            .assertIsNotEnabled()
        compose.runOnIdle { allowed = true }
        compose.onNodeWithText(send).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(calls.first(), calls.last()); assertEquals(2, calls.size) }
        compose.onNodeWithContentDescription(context.getString(R.string.dialog_send_options))
            .performScrollTo().performClick()
        val schedule = context.getString(R.string.dialog_send_schedule)
        compose.waitUntil {
            compose.onAllNodesWithText(schedule).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(schedule).performClick()
        compose.onNode(
            hasText(schedule) and hasClickAction() and SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.Role,
                androidx.compose.ui.semantics.Role.Button
            )
        ).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil { calls.size == 3 }
        compose.runOnIdle {
            assertEquals(3, calls.size)
            assertEquals(calls.first().first, calls.last().first)
            assertTrue(calls.last().second.silent)
            assertTrue(requireNotNull(calls.last().second.scheduleDate).toLong() > System.currentTimeMillis() / 1000 + 59)
        }
        compose.onNode(hasText(schedule) and hasClickAction()).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(4, calls.size); assertEquals(
            calls[2],
            calls[3]
        ); admission = 123L
        }
        compose.waitUntil { dismissed == 1 }
    }
}
