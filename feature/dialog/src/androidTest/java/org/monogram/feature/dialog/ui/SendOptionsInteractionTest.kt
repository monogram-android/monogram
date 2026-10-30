package org.monogram.feature.dialog.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.monogram.core.database.toModel
import org.monogram.core.ui.loading.MonogramBusyIconButton
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.R
import org.monogram.feature.dialog.store.validSendSchedule

class SendOptionsInteractionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun localFailureShowsNotSentAndAllowsExplicitRetry() {
        val context = compose.activity
        var retried: Long? = null
        val message = org.monogram.core.database.entity.MessageEntity(
            chatId = 7, id = -37, senderId = 7, text = "Fixture", date = 100,
            outgoing = true, pending = true, randomId = 123, mediaCacheKey = null, mediaKind = null,
        )
        val row = org.monogram.core.database.entity.OutgoingTextSendEntity(
            123, message, false, false, null, null,
            org.monogram.core.database.entity.OutgoingSendPhase.FAILED,
        )
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                MessageBubble(
                    message.toModel().copy(pending = false, failed = true), null,
                    showSender = false, joinsMessageAbove = false, joinsMessageBelow = false,
                    mediaRepository = null, onRetrySend = { retried = row.randomId },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.dialog_outbox_failed), substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText(
            context.getString(R.string.dialog_outbox_uncertain),
            substring = true
        ).assertDoesNotExist()
        compose.onNodeWithText(context.getString(org.monogram.core.ui.R.string.status_retry))
            .performClick()
        compose.runOnIdle { assertEquals(123L, retried) }
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Test
    fun editingAttachmentActionWorksWithBlankCaptionAndDisablesDuringUpload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = context.getString(R.string.dialog_replace_attachment)
        var busy by mutableStateOf(false)
        var editing by mutableStateOf(true)
        var replacements = 0
        var sends = 0
        var pending by mutableStateOf(emptyList<org.monogram.core.models.UploadItem>())
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                DialogWriteBar(
                    composer = androidx.compose.ui.text.input.TextFieldValue(""),
                    onComposerChange = {},
                    sending = busy,
                    canSendPlain = true,
                    canSendPhotos = true,
                    isChannel = false,
                    editing = editing,
                    editingBody = "synthetic attachment",
                    replyBody = null,
                    pendingAttach = pending,
                    hasFailed = false,
                    onSend = { sends++ },
                    onRetryFailed = {},
                    onCancelEdit = { editing = false },
                    onClearReply = {},
                    onClearAttach = {},
                    onReplaceAttachment = {
                        replacements++
                        pending = listOf(
                            org.monogram.core.models.UploadItem(
                                "synthetic-replacement",
                                "photo"
                            )
                        )
                    })
            }
        }
        compose.onNodeWithContentDescription(label).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, replacements); assertEquals(0, sends) }
        compose.onNodeWithContentDescription(context.getString(R.string.dialog_editing))
            .assertIsEnabled()
        compose.waitForIdle()
        composerUiProof("replace-attachment.png")
        compose.onNodeWithContentDescription(context.getString(R.string.dialog_editing))
            .performClick()
        compose.runOnIdle { assertEquals(1, sends); busy = true }
        compose.onNodeWithContentDescription(label).assertIsNotEnabled()
        compose.runOnIdle { busy = false; editing = false }
        compose.onNodeWithContentDescription(label).assertDoesNotExist()
    }

    @Test
    fun longPressOpensOptionsWithoutSendingAndSilentChoiceRunsOnlyOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val silentLabel = context.getString(R.string.dialog_send_silent)
        var plain = 0
        var silent = 0
        compose.setContent {
            var menu by remember { mutableStateOf(false) }
            MonogramTheme(dynamicColor = false) {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.fillMaxSize(),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom
                ) {
                    Text("synthetic photo attachment")
                    org.monogram.core.ui.components.ChatComposerLayout(
                        style = org.monogram.core.ui.ComposerStyle.Material,
                        leftAction = org.monogram.core.ui.ComposerSlotAction.None,
                        rightAction = org.monogram.core.ui.ComposerSlotAction.None,
                        leftEnabled = false,
                        rightEnabled = false,
                        sendEnabled = true,
                        sending = false,
                        editing = false,
                        leftDescription = "",
                        rightDescription = "",
                        sendDescription = "synthetic send",
                        onLeft = {},
                        onRight = {},
                        onSend = { plain++ },
                        onSendLongClick = { menu = true },
                        sendMenu = {
                            SendOptionsMenu(menu,
                                { menu = false },
                                { silent++; menu = false },
                                { menu = false })
                        }) { Text("synthetic caption") }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("synthetic send").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_100)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("synthetic send").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(silentLabel).assertIsDisplayed()
        composerUiProof("send-menu.png")
        compose.onNodeWithText(silentLabel).performClick()
        compose.runOnIdle { assertEquals(0, plain); assertEquals(1, silent) }
        compose.onNodeWithContentDescription("synthetic send").performClick()
        compose.runOnIdle { assertEquals(1, plain); assertEquals(1, silent) }
    }

    @Test
    fun uncertainSendOffersAnExplicitRetryAndReadySendDoesNot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var phase by mutableStateOf(org.monogram.core.database.entity.OutgoingSendPhase.READY)
        var retried: Long? = null
        val row = org.monogram.core.database.entity.OutgoingTextSendEntity(
            randomId = 321,
            message = org.monogram.core.database.entity.MessageEntity(
                chatId = 7,
                id = -1,
                senderId = null,
                text = "synthetic retry",
                date = 100,
                outgoing = true,
                pending = true,
                randomId = 321,
                mediaCacheKey = null,
                mediaKind = null
            ),
            silent = true, noWebpage = false, scheduleDate = null, webpageUrl = null,
        )
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                val failed = phase == org.monogram.core.database.entity.OutgoingSendPhase.UNCERTAIN
                MessageBubble(
                    row.message.toModel().copy(pending = !failed, failed = failed), null,
                    showSender = false, joinsMessageAbove = false, joinsMessageBelow = false,
                    mediaRepository = null, sendUncertain = failed,
                    onRetrySend = if (failed) ({
                        retried = row.randomId
                        phase = org.monogram.core.database.entity.OutgoingSendPhase.READY
                    }) else null,
                )
            }
        }
        val retry = context.getString(org.monogram.core.ui.R.string.status_retry)
        compose.onNodeWithText(retry).assertDoesNotExist()
        compose.runOnIdle { phase = org.monogram.core.database.entity.OutgoingSendPhase.UNCERTAIN }
        compose.onNodeWithText(
            context.getString(R.string.dialog_outbox_uncertain),
            substring = true
        ).assertIsDisplayed()
        compose.onNodeWithText(retry).performClick()
        compose.runOnIdle { assertEquals(321L, retried) }
        compose.onNodeWithText(retry).assertDoesNotExist()
    }

    @Test
    fun mediaNoticeKeepsCaptionAndExplicitRetryAtLargeFontInDarkTheme() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var phase by mutableStateOf(org.monogram.core.database.entity.OutgoingSendPhase.UNCERTAIN)
        var retried: Long? = null
        val message = org.monogram.core.database.entity.MessageEntity(
            chatId = 7,
            id = -1,
            senderId = null,
            text = "synthetic media caption with longer text",
            date = 100,
            outgoing = true,
            pending = true,
            randomId = 123,
            mediaCacheKey = "local:fixture",
            mediaKind = "photo"
        )
        val item = org.monogram.core.database.entity.OutgoingMediaItemEntity(
            123, 123, 0, "image/png",
            org.monogram.core.database.RetainedMediaSource(
                "00000000-0000-0000-0000-000000000001.bin",
                3,
                "0".repeat(64)
            ), message
        )
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    density.density,
                    1.5f
                )
            ) {
                MonogramTheme(darkTheme = true, dynamicColor = false) {
                    val send = outgoingBubbleSend(
                        message.toModel(), emptyList(), listOf(
                            org.monogram.core.database.entity.OutgoingMediaSend(
                                org.monogram.core.database.entity.OutgoingMediaBatchEntity(
                                    123,
                                    7,
                                    100,
                                    true,
                                    null,
                                    false,
                                    phase
                                ), listOf(item)
                            )
                        )
                    )!!
                    val failed =
                        send.phase == org.monogram.core.database.entity.OutgoingSendPhase.UNCERTAIN
                    MessageBubble(
                        message.toModel().copy(pending = !failed, failed = failed), null,
                        showSender = false, joinsMessageAbove = false, joinsMessageBelow = false,
                        mediaRepository = null, sendUncertain = failed,
                        onRetrySend = if (failed) ({
                            retried = send.id
                            phase = org.monogram.core.database.entity.OutgoingSendPhase.READY
                        }) else null,
                    )
                }
            }
        }
        compose.onNodeWithText("synthetic media caption", substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(org.monogram.core.ui.R.string.status_retry))
            .performClick()
        compose.runOnIdle { assertEquals(123L, retried) }
        compose.onNodeWithText(context.getString(org.monogram.core.ui.R.string.status_retry))
            .assertDoesNotExist()
    }

    @Test
    fun scheduleCloseAndActionStayVisibleWhenSwitchingTimeFields() {
        var visible by mutableStateOf(true)
        var dismissed = 0
        compose.setContent {
            MonogramTheme(darkTheme = false, dynamicColor = false) {
                if (visible) ScheduleMessageSheet(
                    onDismiss = { visible = false; dismissed++ },
                    onSchedule = { error("unexpected schedule") })
            }
        }
        val close =
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.dialog_cancel))
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performClick()
        compose.waitForIdle()
        close.assertIsDisplayed()
        val top = close.fetchSemanticsNode().boundsInWindow.top
        fields[1].performClick()
        close.assertIsDisplayed()
        assertEquals(top, close.fetchSemanticsNode().boundsInWindow.top, 2f)
        val button = SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.Role,
            androidx.compose.ui.semantics.Role.Button
        )
        compose.onNode(hasText(compose.activity.getString(R.string.dialog_send_schedule)) and button)
            .assertIsDisplayed()
        composerUiProof("schedule-keyboard.png")
        close.performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun scheduleSheetCancelDoesNotSendAndConfirmEmitsAValidatedTime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var scheduled: Int? = null
        var dismissed = 0
        compose.setContent {
            var visible by remember { mutableStateOf(true) }
            MonogramTheme(dynamicColor = false) {
                Button(onClick = { visible = true }) { Text("reopen synthetic sheet") }
                if (visible) ScheduleMessageSheet(
                    onDismiss = { dismissed++; visible = false },
                    onSchedule = { scheduled = it; visible = false },
                )
            }
        }
        composerUiProof("schedule-sheet.png")
        val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        compose.onNodeWithContentDescription(context.getString(R.string.dialog_cancel))
            .assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, dismissed); assertNull(scheduled) }
        compose.onNodeWithText("reopen synthetic sheet").performClick()
        val schedule = context.getString(R.string.dialog_send_schedule)
        compose.onNode(hasText(schedule) and button).performScrollTo().performClick()
        compose.runOnIdle {
            assertNotNull(scheduled); assertTrue(
            validSendSchedule(
                scheduled,
                System.currentTimeMillis() / 1000
            )
        )
        }
    }
}

internal fun composerUiProof(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val bitmap = instrumentation.uiAutomation.takeScreenshot()
    java.io.File(instrumentation.targetContext.cacheDir, name).outputStream().use {
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
    }
    bitmap.recycle()
}
