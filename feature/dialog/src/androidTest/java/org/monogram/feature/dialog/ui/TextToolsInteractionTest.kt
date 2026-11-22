package org.monogram.feature.dialog.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.TextEntity
import org.monogram.core.models.TextToolText
import org.monogram.core.models.TextToolsAvailability
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.DialogTextToolsState
import org.monogram.feature.dialog.InlineTranslation
import org.monogram.feature.dialog.R
import org.monogram.feature.dialog.TextToolsAction

class TextToolsInteractionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun summaryReplacesBodyAndOriginalRestoresRichCodeBlock() {
        val original = "val answer = 42"
        val message = Message(
            MessageId(PeerId(5), 11), null, original, 10,
            outgoing = false,
            entities = listOf(TextEntity("pre", 0, original.length, "kotlin")),
        )
        val summary = "Defines the answer."
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                MessageBubble(
                    message, null, showSender = false, joinsMessageAbove = false,
                    joinsMessageBelow = false, mediaRepository = null,
                    inlineTranslation = InlineTranslation(
                        message,
                        TextToolText(summary),
                        summary = true
                    ),
                )
            }
        }
        val summaryLabel = compose.activity.getString(R.string.dialog_tools_summary_result)
        val showOriginal = compose.activity.getString(R.string.dialog_tools_show_original)
        compose.onNodeWithText(summaryLabel).assertIsDisplayed()
        compose.onNodeWithText(summary).assertIsDisplayed()
        compose.onNodeWithText(original).assertDoesNotExist()

        compose.onNodeWithText(showOriginal).performClick()
        compose.onNodeWithText(original).assertIsDisplayed()
        compose.onNodeWithText("KOTLIN").assertIsDisplayed()
        compose.onNodeWithText(summary).assertDoesNotExist()

        compose.onNodeWithText(summaryLabel).performClick()
        compose.onNodeWithText(summary).assertIsDisplayed()
        compose.onNodeWithText("KOTLIN").assertDoesNotExist()
    }

    @Test
    fun translationMenuTogglesChatAndCloses() {
        var state by mutableStateOf(
            DialogTextToolsState(
                availability = TextToolsAvailability(),
                chatAllowed = true
            )
        )
        var lastAction: TextToolsAction? = null
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                DialogTranslationMenu(state) { action ->
                    lastAction = action
                    if (action is TextToolsAction.ChatEnabled) state =
                        state.copy(chatEnabled = action.enabled)
                }
            }
        }
        val open = compose.activity.getString(R.string.dialog_tools_translate)
        val translate = compose.activity.getString(R.string.dialog_tools_translate_chat)
        val original = compose.activity.getString(R.string.dialog_tools_show_original)
        compose.onNodeWithContentDescription(open).performClick()
        compose.onNodeWithText(translate).performClick()
        compose.runOnIdle { assertEquals(TextToolsAction.ChatEnabled(true), lastAction) }
        compose.onNodeWithText(original).assertDoesNotExist()
        compose.onNodeWithContentDescription(open).performClick()
        compose.onNodeWithText(original).performClick()
        compose.runOnIdle { assertEquals(TextToolsAction.ChatEnabled(false), lastAction) }
    }
}
