package org.monogram.feature.dialog.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.monogram.core.models.TextEntity
import org.monogram.core.ui.ComposerSlotAction
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.R

class ComposerEmojiEditingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun emojiShortcutRemainsEnabledWhileEditing() {
        val actions = mutableListOf<ComposerSlotAction>()
        compose.setContent {
            EditingComposer(TextFieldValue("Existing message"), onSlot = actions::add)
        }
        compose
            .onNodeWithContentDescription(compose.activity.getString(R.string.dialog_panel_emoji))
            .assertIsEnabled()
            .performClick()
        compose
            .onNodeWithContentDescription(compose.activity.getString(R.string.dialog_attach))
            .assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf(ComposerSlotAction.Emoji), actions) }
    }

    @Test
    fun staleCustomEmojiEntityDoesNotCrashAnEmptyEditor() {
        val value = mutableStateOf(TextFieldValue())
        compose.setContent {
            EditingComposer(
                value = value.value,
                entities = listOf(TextEntity("custom_emoji", 0, 2, "123")),
                onChange = { value.value = it },
            )
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("🙂")
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("", value.value.text) }
    }

    @Test
    fun customEmojiOutsideEditedTextIsIgnored() {
        compose.setContent {
            EditingComposer(
                value = TextFieldValue("Text"),
                entities =
                    listOf(
                        TextEntity("custom_emoji", -1, 2, "123"),
                        TextEntity("custom_emoji", 4, 2, "456"),
                        TextEntity("custom_emoji", 0, 0, "789"),
                    ),
            )
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertExists()
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    private fun EditingComposer(
        value: TextFieldValue,
        entities: List<TextEntity> = emptyList(),
        onChange: (TextFieldValue) -> Unit = {},
        onSlot: (ComposerSlotAction) -> Unit = {},
    ) {
        MonogramTheme(dynamicColor = false) {
            DialogWriteBar(
                composer = value,
                onComposerChange = onChange,
                draftEntities = entities,
                sending = false,
                canSendPlain = true,
                canSendPhotos = true,
                isChannel = false,
                editing = true,
                editingBody = "Existing message",
                replyBody = null,
                pendingAttach = emptyList(),
                hasFailed = false,
                onSend = {},
                onSlot = onSlot,
                onRetryFailed = {},
                onCancelEdit = {},
                onClearReply = {},
                onClearAttach = {},
            )
        }
    }
}
