package org.monogram.feature.dialog.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.*
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.R

class ChecklistCreationInteractionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun checklistBoundsPermissionsAndRestorationPreserveFormUntilAdmission() {
        val restore = StateRestorationTester(compose)
        val context = compose.activity
        var admission by mutableStateOf<Long?>(null)
        var failure by mutableStateOf<TelegramError?>(null)
        val calls = mutableListOf<Pair<ChecklistSendSpec, MessageSendOptions>>()
        var dismissed = 0
        restore.setContent {
            MonogramTheme(dynamicColor = false) {
                MessageCreationSheet(
                    MessageCreationKind.Checklist, admission, false, failure,
                    { _, _ -> throw AssertionError("unexpected poll send") },
                    { spec, options ->
                        calls += spec to options; failure = TelegramError.parse("RPC 500: INTERNAL")
                    }, { dismissed++ })
            }
        }
        val send = context.getString(R.string.dialog_send)
        compose.onNodeWithText(send).performScrollTo().assertIsNotEnabled()
        fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))
        val title = context.getString(R.string.dialog_checklist_title)
        val first = context.getString(R.string.dialog_checklist_item, 1)
        field(title).performScrollTo().performTextInput("x".repeat(33))
        field(first).performScrollTo().performTextInput("First task")
        compose.onNodeWithText(send).performScrollTo().assertIsNotEnabled()
        field(title).performScrollTo().performTextReplacement("Synthetic list")
        field(first).performScrollTo().performTextReplacement("x".repeat(65))
        compose.onNodeWithText(send).performScrollTo().assertIsNotEnabled()
        field(first).performScrollTo().performTextReplacement("First task")
        compose.onNodeWithText(context.getString(R.string.dialog_checklist_add)).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        field(context.getString(R.string.dialog_checklist_item, 2)).performScrollTo()
            .performTextInput("Second task")
        compose.onNodeWithText(context.getString(R.string.dialog_checklist_allow_append))
            .performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        restore.emulateSavedInstanceStateRestore()
        field("Synthetic list").performScrollTo().assertTextContains("Synthetic list")
        compose.onNodeWithText(context.getString(R.string.dialog_checklist_allow_append))
            .performScrollTo().assertIsOn()
        compose.onNodeWithText(context.getString(R.string.dialog_checklist_allow_complete))
            .assertIsOff()
        compose.waitForIdle()
        composerUiProof("checklist-sheet.png")
        compose.onNodeWithText(send).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(1, calls.size); assertEquals(0, dismissed)
            assertEquals(
                ChecklistSendSpec(
                    "Synthetic list",
                    listOf("First task", "Second task"),
                    othersCanAppend = true
                ), calls.single().first
            )
        }
        compose.onNodeWithText(send).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(2, calls.size); assertEquals(
            calls.first(),
            calls.last()
        ); admission = 123L
        }
        compose.waitUntil { dismissed == 1 }
    }
}
