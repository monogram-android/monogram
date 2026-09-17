package org.monogram

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.http.MediaRepository
import org.monogram.network.http.TelegramMediaFetcher
import org.monogram.root.RootComponent
import org.monogram.root.RootContent
import java.lang.reflect.Proxy
import java.io.File
import kotlin.math.abs

class TabletStateTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var expanded by mutableStateOf(false)
    private lateinit var root: RootComponent
    private var scenario: ActivityScenario<UiStateTestActivity>? = null
    private var repository: MediaRepository? = null
    private val selectable = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    @Test
    fun longMarkdownOpensMenuAtBottomAndSelectsWithoutBottomBar() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val message = Message(
            id = MessageId(PeerId(1), 1), senderId = null,
            text = "# Long message\n\n" + (1..45).joinToString("\n\n") { "Paragraph $it with **formatted text**." } +
                "\n\n```kotlin\nval bottom = 42\n```\n\nBottom target",
            date = 1_800_000_000L, outgoing = false,
        )
        launch(false, listOf(message), fullWindow = true)
        row("Chat 001").performClick()
        compose.onNodeWithText("Bottom target").assertIsDisplayed()
        compose.onNodeWithText("val bottom = 42").performTouchInput { click() }
        compose.onNodeWithText(context.getString(org.monogram.feature.dialog.R.string.dialog_select_text)).assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Bottom target").performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(org.monogram.feature.dialog.R.string.dialog_select_text)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(org.monogram.feature.dialog.R.string.dialog_select_text)).performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText(context.getString(org.monogram.feature.dialog.R.string.dialog_copy_selected)).assertDoesNotExist()
        compose.onNodeWithText("Bottom target").performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.apply {
            File(context.getExternalFilesDir(null), "markdown-selection.png").outputStream().use {
                compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            recycle()
        }
    }

    @Test
    fun profileReturnPreservesHistoryPosition() {
        val messages = (60 downTo 1).map { id ->
            Message(id = MessageId(PeerId(1), id), senderId = PeerId(1),
                text = "History row $id", date = 1L, outgoing = false)
        }
        launch(false, messages)
        row("Chat 001").performClick()
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("History row 30"))
        val top = compose.onNodeWithText("History row 30").getBoundsInRoot().top
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithContentDescription(context.getString(org.monogram.feature.dialog.R.string.dialog_profile)).performClick()
        swipeBack()
        compose.onNodeWithText("History row 30").assertIsDisplayed()
        assertTrue(abs(compose.onNodeWithText("History row 30").getBoundsInRoot().top.value - top.value) < 2f)
        compose.onNodeWithContentDescription(context.getString(org.monogram.feature.dialog.R.string.dialog_profile)).performClick()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("History row 30").assertIsDisplayed()
        assertTrue(abs(compose.onNodeWithText("History row 30").getBoundsInRoot().top.value - top.value) < 2f)
    }

    @Test
    fun forwardedMessageShowsSenderFirstAndOpensOriginalSource() {
        var sourceOpened = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val forwarded = context.getString(org.monogram.feature.dialog.R.string.dialog_forwarded_from, "Original author")
        UiStateTestActivity.content = {
            MonogramTheme {
                org.monogram.feature.dialog.ui.MessageBubble(
                    message = Message(
                        id = MessageId(PeerId(1), 1), senderId = PeerId(2),
                        text = "Forwarded message text", date = 1_800_000_000L, outgoing = false,
                        fwdFrom = "Original author", fwdFromId = 3L, fwdDate = 1_700_000_000L,
                    ),
                    sender = Profile(PeerId(2), "user", "Current sender"),
                    showSender = true,
                    joinsMessageAbove = false,
                    joinsMessageBelow = false,
                    mediaRepository = null,
                    onOpenForwardSource = { sourceOpened = true },
                    modifier = Modifier.padding(top = 48.dp, start = 16.dp, end = 16.dp),
                )
            }
        }
        scenario = ActivityScenario.launch(UiStateTestActivity::class.java)
        val senderBounds = compose.onNodeWithText("Current sender").getBoundsInRoot()
        val forwardBounds = compose.onNodeWithText(forwarded, useUnmergedTree = true).getBoundsInRoot()
        assertTrue(senderBounds.bottom <= forwardBounds.top)
        compose.onNodeWithText(forwarded).performClick()
        compose.runOnIdle { assertTrue(sourceOpened) }
        val originalDate = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.getDefault())
            .format(java.util.Date(1_700_000_000_000L))
        compose.onNodeWithText(originalDate, substring = true).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.apply {
            File(context.getExternalFilesDir(null), "forward-header.png").outputStream().use {
                compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            recycle()
        }
    }

    @Test
    fun profileSwipeReturnsToChatAndSettingsSwipeRespectsNestedPage() {
        launch(false)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        row("Chat 001").performClick()
        compose.onNodeWithContentDescription(context.getString(org.monogram.feature.dialog.R.string.dialog_profile)).performClick()
        compose.runOnIdle { assertTrue(root.stack.value.active.configuration is RootComponent.Config.Profile) }
        swipeBack()
        compose.runOnIdle { assertTrue(root.stack.value.active.configuration is RootComponent.Config.Dialog); root.onBack() }
        compose.onNodeWithContentDescription(context.getString(org.monogram.feature.chats.R.string.chats_settings)).performClick()
        compose.onNodeWithText(context.getString(org.monogram.feature.settings.R.string.settings_chat)).performClick()
        swipeBack()
        compose.runOnIdle { assertTrue(root.stack.value.active.configuration is RootComponent.Config.Settings) }
        compose.onNodeWithText(context.getString(org.monogram.feature.settings.R.string.settings_chat_sub)).assertExists()
        swipeBack()
        compose.runOnIdle { assertEquals(RootComponent.Config.Home, root.stack.value.active.configuration) }
    }

    private fun swipeBack() {
        compose.onNode(androidx.compose.ui.test.hasTestTag("navigation-test"))
            .performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.6f),
                    androidx.compose.ui.geometry.Offset(width * 0.8f, height * 0.6f), 600)
            }
        compose.waitForIdle()
    }

    @Test
    fun nativeMarkdownAndLatexRenderInsideMessageBubbles() {
        val source = "## Native rendering\n\n**formatted** and \$x^2\$\n\n```kotlin\nval result = 42\n```\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\n\$\$\n\\frac{x_i}{y_j}\n\$\$"
        val messages = listOf(
            Message(id = MessageId(PeerId(1), 2), senderId = null, text = "**literal**", date = 2L, outgoing = false,
                entities = listOf(org.monogram.core.models.TextEntity("bold", 2, 7))),
            Message(id = MessageId(PeerId(1), 1), senderId = null, text = source, date = 1L, outgoing = false),
        )
        launch(false, messages, fullWindow = true)
        row("Chat 001").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Native rendering")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Native rendering").assertIsDisplayed()
        compose.onNodeWithText("val result = 42").assertIsDisplayed()
        compose.onNodeWithText("**literal**").assertIsDisplayed()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("x^2")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("x^2").assertIsDisplayed()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("\n\\frac{x_i}{y_j}\n")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("\n\\frac{x_i}{y_j}\n").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = File(instrumentation.targetContext.getExternalFilesDir(null), "bubble-native-render.png")
        instrumentation.uiAutomation.takeScreenshot().apply {
            screenshot.outputStream().use { compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
    }

    @Test
    fun complexDraftOpensEditorWithoutLosingPastedTextOrReopeningAfterClose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        launch(false, fullWindow = true)
        row("Chat 001").performClick()
        val draft = "```kotlin\nval number = 1\n```"
        compose.onNode(hasSetTextAction()).performTextInput(draft)
        compose.mainClock.advanceTimeBy(500)
        val editor = hasSetTextAction() and hasAnyAncestor(isDialog())
        compose.waitUntil(5_000) { compose.onAllNodes(editor).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(editor).assertTextContains(draft)
        compose.onNodeWithContentDescription(context.getString(org.monogram.feature.dialog.R.string.dialog_markdown_close)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput(" ")
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun markdownEditorOverlaysChatAndRestoresDraftAcrossRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        launch(false, fullWindow = true)
        row("Chat 001").performClick()
        compose.onNodeWithContentDescription(context.getString(org.monogram.feature.dialog.R.string.dialog_markdown_expand)).assertDoesNotExist()
        val original = "A longer draft with enough text to open the full screen editor."
        compose.onNode(hasSetTextAction()).performTextInput(original)
        compose.onNodeWithContentDescription(context.getString(org.monogram.feature.dialog.R.string.dialog_markdown_expand)).performClick()
        val editor = hasSetTextAction() and hasAnyAncestor(isDialog())
        compose.onNode(editor).performTextReplacement("**changed**")
        val bounds = compose.onNode(isDialog()).getBoundsInRoot()
        assertTrue("Editor must start above the chat content", bounds.top.value < 60f)
        scenario!!.recreate()
        compose.onNode(editor).assertTextContains("**changed**")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.apply {
            File(context.getExternalFilesDir(null), "editor-redesign.png").outputStream().use {
                compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            recycle()
        }
        compose.onNodeWithContentDescription(context.getString(org.monogram.feature.dialog.R.string.dialog_markdown_close)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasSetTextAction()).assertTextContains(original)
        compose.onNodeWithContentDescription(context.getString(org.monogram.feature.dialog.R.string.dialog_markdown_expand)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(editor).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(editor).performTextReplacement("applied")
        compose.onNodeWithContentDescription(context.getString(org.monogram.feature.dialog.R.string.dialog_markdown_apply)).performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("applied")
    }

    @After
    fun tearDown() {
        scenario?.close()
        repository?.shutdown()
        UiStateTestActivity.content = {}
    }

    @Test
    fun messagePhotoStaysOpenAcrossActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = File(context.cacheDir, "dialog-state-photo.png")
        android.graphics.Bitmap.createBitmap(800, 600, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.GREEN)
            photo.outputStream().use { compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val message = Message(id = MessageId(PeerId(1), 1), senderId = null, text = "Photo caption", date = 1L, outgoing = false,
            mediaKind = "photo", mediaCacheKey = "state-photo", mediaWidth = 800, mediaHeight = 600)
        repository = MediaRepository(File(context.cacheDir, "dialog-state-media"), telegramFetcher = TelegramMediaFetcher { _, _, dest, _, _ ->
            photo.copyTo(File(dest), overwrite = true)
            Outcome.Ok(dest)
        })
        launch(false, listOf(message))
        row("Chat 001").performClick()
        val description = context.getString(org.monogram.feature.dialog.R.string.dialog_media_photo)
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(description).performClick()
        compose.onNode(isDialog()).assertExists()
        scenario!!.recreate()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithContentDescription(context.getString(org.monogram.core.ui.R.string.media_preview_close)).performClick()
        compose.runOnIdle {
            assertEquals(1L, (root.stack.value.active.configuration as RootComponent.Config.Dialog).chatId)
        }
        photo.delete()
    }

    @Test
    fun actualTabletWindowKeepsBothPanesAfterRotation() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("tabletWindow") == "true")
        launch(true, fullWindow = true)
        row("Chat 001").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Tablet draft")
        row("Chat 001").assertIsSelected()
        capture("tablet-portrait.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("wm size 2560x1600"),
        ).use { it.readBytes() }
        compose.waitUntil(10_000) {
            var landscape = false
            scenario!!.onActivity { landscape = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            landscape
        }
        row("Chat 001").assertIsSelected()
        compose.onNode(hasSetTextAction()).assertTextContains("Tablet draft")
        capture("tablet-landscape.png")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(1_000, 5_000)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "viewer-qa").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test
    fun openChatAndDraftSurviveActivityRecreationAndPaneChanges() {
        launch(expandedInitially = false)
        row("Chat 001").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Retained draft")
        scenario!!.recreate()
        compose.onNode(hasSetTextAction()).assertTextContains("Retained draft")
        compose.runOnIdle {
            assertEquals(1L, (root.stack.value.active.configuration as RootComponent.Config.Dialog).chatId)
            expanded = true
        }
        row("Chat 001").assertIsSelected()
        row("Chat 002").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("Retained draft")
        compose.runOnIdle { expanded = false }
        compose.onNode(hasSetTextAction()).assertTextContains("Retained draft")
        compose.runOnIdle { root.onBack() }
        row("Chat 001").assertIsDisplayed()
    }

    @Test
    fun tabletFolderScrollSelectionAndDetailReplacementSurviveRecreation() {
        launch(expandedInitially = true)
        compose.onNodeWithText("Work").performClick()
        compose.onNodeWithText("Work").assertIsSelected()
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Chat 030"))
        row("Chat 030").performClick()
        row("Chat 030").assertIsSelected()
        val rowTop = row("Chat 030").getBoundsInRoot().top.value
        compose.runOnIdle { expanded = false }
        compose.runOnIdle { expanded = true }
        compose.onNodeWithText("Work").assertIsSelected()
        row("Chat 030").assertIsSelected()
        assertTrue(abs(row("Chat 030").getBoundsInRoot().top.value - rowTop) < 2f)
        scenario!!.recreate()
        compose.onNodeWithText("Work").assertIsSelected()
        row("Chat 030").assertIsSelected()
        assertTrue(abs(row("Chat 030").getBoundsInRoot().top.value - rowTop) < 2f)
        row("Chat 029").performClick()
        row("Chat 029").assertIsSelected()
        compose.runOnIdle {
            assertEquals(
                listOf(RootComponent.Config.Home, RootComponent.Config.Dialog(29)),
                root.stack.value.items.map { it.configuration },
            )
            root.onBack()
        }
        compose.onNodeWithText("Work").assertIsSelected()
        row("Chat 030").assertIsDisplayed()
    }

    private fun row(title: String) = compose.onNode(hasText(title) and selectable)

    private fun launch(expandedInitially: Boolean, messages: List<Message> = emptyList(), fullWindow: Boolean = false) {
        expanded = expandedInitially
        val client = tabletClient(messages)
        UiStateTestActivity.content = { context ->
            val component = remember(context) {
                RootComponent(
                    componentContext = context,
                    storeFactory = DefaultStoreFactory(),
                    client = client,
                    warmup = null,
                    sessionStore = null,
                    mediaRepository = repository,
                    startOnHome = true,
                )
            }
            root = component
            // A fixed density makes both window classes fit a phone test target.
            CompositionLocalProvider(LocalDensity provides if (fullWindow) LocalDensity.current else Density(1f)) {
                MonogramTheme {
                    Box(if (fullWindow) Modifier.fillMaxSize() else Modifier.requiredSize(if (expanded) 1000.dp else 480.dp, 720.dp)) {
                        RootContent(component, Modifier.testTag("navigation-test"))
                    }
                }
            }
        }
        scenario = ActivityScenario.launch(UiStateTestActivity::class.java)
        compose.onNodeWithText("Work").assertIsDisplayed()
    }

    private fun tabletClient(messages: List<Message>): MtprotoClient {
        val chats = (1L..50L).map { id ->
            Chat(PeerId(id), "Chat ${id.toString().padStart(3, '0')}", lastMessageDate = 1000L - id)
        }
        val folders = listOf(Folder(2, "Work", chatIds = chats.drop(10).map { it.id }))
        return Proxy.newProxyInstance(
            MtprotoClient::class.java.classLoader,
            arrayOf(MtprotoClient::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "TabletStateFakeClient"
                "libraryVersion" -> "test"
                "updates", "sessionLost" -> emptyFlow<Nothing>()
                "getChats" -> Outcome.Ok(chats)
                "getFolders" -> Outcome.Ok(folders)
                "getHistory", "getHistoryPage" -> Outcome.Ok(messages)
                "getPinnedMessages" -> Outcome.Ok(emptyList<Message>())
                "getProfile" -> {
                    val id = args!![0] as PeerId
                    Outcome.Ok(Profile(id, "user", chats.firstOrNull { it.id == id }?.title ?: "Test user"))
                }
                "getGroupAdminTags" -> Outcome.Ok(emptyMap<PeerId, String>())
                "setDialogForeground", "close" -> Unit
                "updateStatus", "setTyping", "readHistory" -> Outcome.Ok(Unit)
                else -> Outcome.Err("Unsupported in tablet UI test: ${method.name}")
            }
        } as MtprotoClient
    }
}
