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

class ChecklistMutationInteractionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private fun message(
        outgoing: Boolean = false,
        complete: Boolean = true,
        append: Boolean = true
    ) = Message(
        MessageId(PeerId(7), 37), null, null, 100, outgoing = outgoing, mediaKind = "todo",
        checklist = Checklist(
            "Synthetic checklist", othersCanComplete = complete, othersCanAppend = append,
            entities = listOf(TextEntity("italic", 0, 19)),
            items = listOf(
                ChecklistItem(
                    1,
                    "Fixture item",
                    entities = listOf(TextEntity("bold", 0, 7))
                )
            )
        ),
    )

    @Test
    fun pendingToggleRetainsValueAndDisablesToggleAndAppendUntilServerResult() {
        var model by mutableStateOf(message())
        var busy by mutableStateOf(false)
        var toggles = 0;
        var appends = 0
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                MessageBubble(
                    model,
                    null,
                    showSender = false,
                    joinsMessageAbove = false,
                    joinsMessageBelow = false,
                    mediaRepository = null,
                    checklistBusy = busy,
                    onToggleChecklist = { toggles++; busy = true },
                    onAddChecklistItem = { _, _ -> appends++ })
            }
        }
        val add =
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.dialog_checklist_add)

        fun assertRichItem() {
            val title = compose.onNodeWithText("Synthetic checklist")
                .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
            assertTrue(title.spanStyles.any { it.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic })
            val text = compose.onNodeWithText("Fixture item")
                .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
            assertTrue(text.spanStyles.any { it.start == 0 && it.end == 7 && it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold })
        }
        assertRichItem()
        compose.onNodeWithText("Fixture item").assertIsOff().performClick()
        compose.onNodeWithText("Fixture item").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText(add).assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, toggles); assertEquals(0, appends)
            model = model.copy(checklist = model.checklist!!.let {
                it.copy(items = it.items.map { item ->
                    item.copy(done = true)
                })
            })
            busy = false
        }
        assertRichItem()
        compose.onNodeWithText("Fixture item").assertIsOn().assertIsEnabled()
        compose.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(
                R.string.dialog_checklist_progress, 1, 1
            )
        ).assertIsDisplayed()
        composerUiProof("checklist-message-completed.png")
        compose.onNodeWithText(add).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, appends) }
    }

    @Test
    fun taskOverflowKeepsEditingAndConfirmedDeletionAvailable() {
        val model = message(outgoing = true).let {
            it.copy(
                checklist = it.checklist!!.copy(
                    items = listOf(ChecklistItem(1, "First task"), ChecklistItem(2, "Second task"))
                )
            )
        }
        var edited: Int? = null
        var removed: Int? = null
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                MessageBubble(
                    model,
                    null,
                    showSender = false,
                    joinsMessageAbove = false,
                    joinsMessageBelow = false,
                    mediaRepository = null,
                    onToggleChecklist = {},
                    onEditChecklistItem = { edited = it },
                    onRemoveChecklistItem = { removed = it })
            }
        }
        val context = compose.activity
        val more = context.getString(org.monogram.core.ui.R.string.media_more_actions)
        compose.onAllNodesWithContentDescription(more)[0].performClick()
        compose.onNodeWithText(context.getString(R.string.dialog_checklist_edit_item, "First task"))
            .performClick()
        compose.runOnIdle { assertEquals(1, edited); assertNull(removed) }
        compose.onAllNodesWithContentDescription(more)[0].performClick()
        compose.onNodeWithText(
            context.getString(
                R.string.dialog_checklist_remove_item,
                "First task"
            )
        ).performClick()
        compose.runOnIdle { assertNull(removed) }
        compose.onNodeWithText(context.getString(R.string.dialog_delete)).performClick()
        compose.runOnIdle { assertEquals(1, removed) }
        composerUiProof("checklist-message-actions.png")
    }

    @Test
    fun accessPendingAndExhaustedIdsBlockChecklistActions() {
        var model by mutableStateOf(message(complete = false, append = false))
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                MessageBubble(
                    model,
                    null,
                    showSender = false,
                    joinsMessageAbove = false,
                    joinsMessageBelow = false,
                    mediaRepository = null,
                    onToggleChecklist = { error("forbidden toggle") },
                    onAddChecklistItem = { _, _ -> error("forbidden append") })
            }
        }
        val add =
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.dialog_checklist_add)
        compose.onNodeWithText("Fixture item").assertIsNotEnabled()
        compose.onNodeWithText(add).assertDoesNotExist()
        compose.runOnIdle { model = message(outgoing = true).copy(pending = true) }
        compose.onNodeWithText("Fixture item").assertIsNotEnabled()
        compose.onNodeWithText(add).assertDoesNotExist()
        compose.runOnIdle {
            model = message(outgoing = true).let {
                it.copy(
                    checklist = it.checklist!!.copy(
                        items = listOf(ChecklistItem(Int.MAX_VALUE, "Last item"))
                    )
                )
            }
        }
        compose.onNodeWithText(add).assertDoesNotExist()
    }
}
