package org.monogram.feature.dialog.scheduled

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.*
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.R

class ScheduledQueueScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    @Test
    fun textEditorDismissesWithoutMutationAppliesOnceAndPreservesDraftOnConcurrentChange() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = ScheduledMessage(
            Message(
                MessageId(PeerId(7), 8),
                senderId = null,
                text = "original editable caption",
                date = 2_000_000_000L,
                outgoing = true,
                mediaKind = "photo"
            ), false
        )
        var state by mutableStateOf(
            ScheduledQueueStore.State(
                messages = listOf(original),
                fromCache = false
            )
        )
        val applied = mutableListOf<String>()
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                ScheduledQueueScreen(state, {}, {}, {}, {}, onEdit = { expected, text, _ ->
                    assertEquals(original, expected); applied += text
                    state = state.copy(
                        messages = listOf(
                            original.copy(
                                message = original.message.copy(text = text)
                            )
                        )
                    )
                })
            }
        }
        fun open() {
            compose.onNodeWithContentDescription(context.getString(R.string.dialog_scheduled_actions))
                .performClick()
            compose.onNodeWithText(context.getString(R.string.dialog_edit)).performClick()
        }
        open()
        compose.onNode(hasSetTextAction()).performTextReplacement("local caption")
        compose.onNodeWithContentDescription(context.getString(R.string.dialog_markdown_close))
            .performClick()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
        open()
        compose.onNode(hasSetTextAction()).assertTextContains("original editable caption")
        compose.onNode(hasSetTextAction()).performTextReplacement("new caption")
        compose.onNodeWithText(context.getString(R.string.dialog_markdown_apply)).performClick()
        compose.runOnIdle { assertEquals(listOf("new caption"), applied) }
        open()
        compose.onNode(hasSetTextAction()).performTextReplacement("keep local draft")
        compose.runOnIdle {
            state =
                state.copy(messages = listOf(original.copy(message = original.message.copy(text = "remote caption"))))
        }
        compose.onNodeWithText(context.getString(R.string.dialog_scheduled_edit_changed))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.dialog_markdown_apply))
            .assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).assertTextContains("keep local draft")
        compose.runOnIdle { assertEquals(1, applied.size) }
    }

    @Test
    fun unknownQueueContentDoesNotExposeEditing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queued = ScheduledMessage(
            Message(
                MessageId(PeerId(7), 8), senderId = null,
                text = "unknown formatting fixture", date = 2_000_000_000L, outgoing = true
            )
        )
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                ScheduledQueueScreen(
                    ScheduledQueueStore.State(
                        messages = listOf(queued),
                        fromCache = false
                    ),
                    {},
                    {},
                    {},
                    {},
                    onEdit = { _, _, _ -> error("unknown content must not be edited") })
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.dialog_scheduled_actions))
            .performClick()
        compose.onNodeWithText(context.getString(R.string.dialog_edit)).assertDoesNotExist()
    }

    @Test
    fun editorRestoresLocalTextAndFormattingAndWaitsForMatchingQueueSnapshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = ScheduledMessage(
            Message(
                MessageId(PeerId(7), 8),
                senderId = null,
                text = "original caption",
                date = 2_000_000_000L,
                outgoing = true,
                mediaKind = "photo",
                entities = listOf(TextEntity("bold", 0, 8))
            ), false
        )
        var state by mutableStateOf(
            ScheduledQueueStore.State(
                messages = listOf(original),
                fromCache = false
            )
        )
        var saved: List<TextEntity>? = null
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            MonogramTheme(dynamicColor = false) {
                ScheduledQueueScreen(state, {}, {}, {}, {}, onEdit = { _, text, entities ->
                    assertEquals("original caption saved", text); saved = entities
                })
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.dialog_scheduled_actions))
            .performClick()
        compose.onNodeWithText(context.getString(R.string.dialog_edit)).performClick()
        compose.onNode(hasSetTextAction())
            .performTextInputSelection(androidx.compose.ui.text.TextRange(16))
        compose.onNode(hasSetTextAction()).performTextInput(" saved")
        compose.runOnIdle { state = state.copy(messages = emptyList(), fromCache = true) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains("original caption saved")
        compose.onNodeWithText(context.getString(R.string.dialog_markdown_apply))
            .assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(messages = listOf(original), fromCache = false) }
        compose.onNodeWithText(context.getString(R.string.dialog_markdown_apply)).assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertEquals(listOf(TextEntity("bold", 0, 8)), saved) }
    }

    @Test
    fun failedEditsKeepLocalTextAndLateAcknowledgementsDoNotConsumeNewerEdits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = ScheduledMessage(
            Message(
                MessageId(PeerId(7), 8), senderId = null,
                text = "original", date = 2_000_000_000L, outgoing = true
            ), false
        )
        var state by mutableStateOf(
            ScheduledQueueStore.State(
                messages = listOf(original),
                fromCache = false
            )
        )
        var attempts = 0
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                ScheduledQueueScreen(
                    state,
                    {},
                    {
                        state = state.copy(
                            error = null,
                            needsRefresh = false,
                            uncertainIds = emptySet()
                        )
                    },
                    {},
                    {},
                    onEdit = { _, _, _ -> attempts++; state = state.copy(mutatingId = 8) })
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.dialog_scheduled_actions))
            .performClick()
        compose.onNodeWithText(context.getString(R.string.dialog_edit)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("attempted edit")
        compose.onNodeWithText(context.getString(R.string.dialog_markdown_apply)).performClick()
        compose.runOnIdle {
            state = state.copy(
                mutatingId = null,
                needsRefresh = true,
                uncertainIds = setOf(8),
                error = TelegramError.parse("RPC 500: INTERNAL")
            )
        }
        compose.onNode(hasSetTextAction()).assertTextContains("attempted edit")
        compose.onNodeWithText(context.getString(R.string.dialog_markdown_apply))
            .assertIsNotEnabled()
        compose.onNodeWithText(context.getString(org.monogram.core.ui.R.string.status_retry))
            .performClick()
        compose.runOnIdle { assertEquals(1, attempts) }
        compose.onNodeWithText(context.getString(R.string.dialog_markdown_apply)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("newer local draft")
        compose.runOnIdle {
            assertEquals(2, attempts)
            state = state.copy(
                mutatingId = null,
                messages = listOf(original.copy(message = original.message.copy(text = "attempted edit")))
            )
        }
        compose.onNode(hasSetTextAction()).assertTextContains("newer local draft")
        compose.onNodeWithText(context.getString(R.string.dialog_scheduled_edit_changed))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.dialog_markdown_apply))
            .assertIsNotEnabled()
    }

    @Test
    fun photoPreviewOpensFromCacheShowsLoadingAndRetryAndClosesWithoutMutations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queued = ScheduledMessage(
            Message(
                MessageId(PeerId(7), 37), senderId = null,
                text = "synthetic preview caption", date = 2_000_000_000L, outgoing = true,
                mediaKind = "photo", mediaCacheKey = "scheduled-preview-fixture"
            )
        )
        val file = java.io.File(context.cacheDir, "scheduled-preview-fixture.png")
        val bitmap =
            android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        file.outputStream()
            .use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        var state by mutableStateOf(
            ScheduledQueueStore.State(
                messages = listOf(queued),
                fromCache = true
            )
        )
        var retries = 0
        var closes = 0
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                ScheduledQueueScreen(
                    state,
                    {},
                    {},
                    onPreview = { id ->
                        assertEquals(37, id)
                        state = state.copy(
                            preview = ScheduledQueueStore.PreviewState(
                                queued,
                                loading = true
                            )
                        )
                    },
                    onClosePreview = { closes++; state = state.copy(preview = null) },
                    onRetryPreview = {
                        retries++; state = state.copy(
                        preview = ScheduledQueueStore.PreviewState(
                            queued,
                            loading = true
                        )
                    )
                    })
            }
        }
        try {
            compose.onNode(hasContentDescription(context.getString(R.string.dialog_media_photo)) and hasClickAction())
                .performClick()
            val close = context.getString(org.monogram.core.ui.R.string.media_preview_close)
            compose.onNodeWithContentDescription(close).assertIsDisplayed()
            compose.runOnIdle {
                state = state.copy(
                    preview = ScheduledQueueStore.PreviewState(
                        queued,
                        error = TelegramError.parse("RPC 500: INTERNAL")
                    )
                )
            }
            compose.onNodeWithText(context.getString(org.monogram.core.ui.R.string.media_video_retry))
                .performClick()
            compose.runOnIdle {
                assertEquals(1, retries); state =
                state.copy(preview = ScheduledQueueStore.PreviewState(queued, file = file))
            }
            compose.onNode(hasText("synthetic preview caption") and hasAnyAncestor(isDialog()))
                .assertIsDisplayed()
            compose.onNodeWithContentDescription(close).performClick()
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(1, closes); assertNull(state.preview) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun cachedRowsSurviveLoadingAndErrorsAndRetryAndBackAreExplicit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queued = ScheduledMessage(
            Message(
                MessageId(PeerId(7), 8), senderId = null,
                text = "synthetic queued text", date = 2_000_000_000L, outgoing = true
            )
        )
        var state by mutableStateOf(ScheduledQueueStore.State(messages = listOf(queued)))
        var refreshes = 0
        var backs = 0
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                ScheduledQueueScreen(state, { backs++ }, { refreshes++ })
            }
        }
        compose.onNodeWithText("synthetic queued text").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.dialog_refresh))
            .performClick()
        compose.runOnIdle { assertEquals(1, refreshes); state = state.copy(loading = true) }
        compose.onNodeWithText("synthetic queued text").assertIsDisplayed()
        compose.runOnIdle {
            state = state.copy(loading = false, error = TelegramError.parse("RPC 500: INTERNAL"))
        }
        compose.onNodeWithText("synthetic queued text").assertIsDisplayed()
        compose.onNodeWithText(context.getString(org.monogram.core.ui.R.string.status_retry))
            .performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.dialog_back)).performClick()
        compose.runOnIdle { assertEquals(2, refreshes); assertEquals(1, backs) }
    }

    @Test
    fun sendNowAndCancellationRequireConfirmationAndUncertainRowsDisableActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queued = ScheduledMessage(
            Message(
                MessageId(PeerId(7), 8), senderId = null,
                text = "synthetic action fixture", date = 2_000_000_000L, outgoing = true
            )
        )
        var state by mutableStateOf(
            ScheduledQueueStore.State(
                messages = listOf(queued),
                fromCache = false
            )
        )
        val sent = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                ScheduledQueueScreen(state, {}, {}, { sent += it }, { cancelled += it })
            }
        }
        val actions = context.getString(R.string.dialog_scheduled_actions)
        val send = context.getString(R.string.dialog_scheduled_send_now)
        val cancel = context.getString(R.string.dialog_scheduled_cancel)
        fun choose(label: String) {
            compose.onNodeWithContentDescription(actions).performClick()
            compose.onNodeWithText(label).performClick()
            compose.onNode(isDialog()).assertExists()
        }
        choose(send)
        compose.onNodeWithText(context.getString(R.string.dialog_cancel)).performClick()
        compose.runOnIdle { assertTrue(sent.isEmpty()); assertTrue(cancelled.isEmpty()) }
        choose(send)
        compose.onNode(hasText(send) and hasClickAction() and hasAnyAncestor(isDialog()))
            .performClick()
        compose.runOnIdle { assertEquals(listOf(8), sent); assertTrue(cancelled.isEmpty()) }
        choose(cancel)
        compose.onNodeWithText(context.getString(R.string.dialog_cancel)).performClick()
        compose.runOnIdle { assertTrue(cancelled.isEmpty()) }
        choose(cancel)
        compose.onNode(hasText(cancel) and hasClickAction() and hasAnyAncestor(isDialog()))
            .performClick()
        compose.runOnIdle {
            assertEquals(listOf(8), cancelled); state = state.copy(uncertainIds = setOf(8))
        }
        compose.onNodeWithContentDescription(actions).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.dialog_scheduled_check_result))
            .assertIsDisplayed()
    }

    @Test
    fun reschedulePickerRestoresAndDismissalNeverChangesTheQueue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = System.currentTimeMillis() / 1000 + 3600
        val state = ScheduledQueueStore.State(
            fromCache = false, messages = listOf(
                ScheduledMessage(
                    Message(
                        MessageId(PeerId(7), 8),
                        senderId = null,
                        text = "synthetic reschedule",
                        date = original,
                        outgoing = true
                    )
                )
            )
        )
        val changes = mutableListOf<Pair<Int, Int>>()
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            MonogramTheme(dynamicColor = false) {
                ScheduledQueueScreen(state, {}, {}, {}, {}, { id, date -> changes += id to date })
            }
        }
        val label = context.getString(R.string.dialog_scheduled_reschedule)
        fun openPicker() {
            compose.onNodeWithContentDescription(context.getString(R.string.dialog_scheduled_actions))
                .performClick()
            compose.onNodeWithText(label).performClick()
        }
        openPicker()
        restoration.emulateSavedInstanceStateRestore()
        val button = SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.Role,
            androidx.compose.ui.semantics.Role.Button
        )
        compose.onNode(hasText(label) and button).performScrollTo().assertIsEnabled()
        compose.onNode(hasText(context.getString(R.string.dialog_cancel)) and button)
            .performScrollTo().performClick()
        compose.runOnIdle { assertTrue(changes.isEmpty()) }
        openPicker()
        compose.onNode(hasText(label) and button).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(8 to ((original / 60) * 60).toInt()), changes) }
    }

    @Test
    fun queuedListScrollRestoresAcrossSavedState() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        val state = ScheduledQueueStore.State(fromCache = false, messages = (1..60).map { id ->
            ScheduledMessage(
                Message(
                    MessageId(PeerId(7), id), senderId = null,
                    text = "synthetic queued item $id", date = 2_000_000_000L + id, outgoing = true
                )
            )
        })
        restoration.setContent {
            MonogramTheme(dynamicColor = false) {
                ScheduledQueueScreen(
                    state,
                    {},
                    {})
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(49)
        compose.onNodeWithText("synthetic queued item 50").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("synthetic queued item 50").assertIsDisplayed()
    }

    @Test
    fun emptyStateAndOnlineSentinelUseLocalizedLabels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var state by mutableStateOf(ScheduledQueueStore.State(fromCache = false))
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                ScheduledQueueScreen(
                    state,
                    {},
                    {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.dialog_scheduled_empty))
            .assertIsDisplayed()
        compose.runOnIdle {
            state = state.copy(
                messages = listOf(
                    ScheduledMessage(
                        Message(
                            MessageId(PeerId(7), 8),
                            senderId = null,
                            text = "online fixture",
                            date = 0x7ffffffeL,
                            outgoing = true
                        )
                    )
                )
            )
        }
        compose.onNodeWithText(context.getString(R.string.dialog_scheduled_empty))
            .assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.dialog_schedule_when_online))
            .assertIsDisplayed()
        compose.onNodeWithText("online fixture").assertIsDisplayed()
    }
}
