package org.monogram

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.network.bridge.MtprotoClient
import org.monogram.root.RootComponent
import org.monogram.root.RootContent
import java.lang.reflect.Proxy

class ComposerEditingTest {
    @get:Rule val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<UiStateTestActivity>? = null
    private lateinit var root: RootComponent

    @After
    fun cleanup() {
        scenario?.close()
        UiStateTestActivity.content = {}
    }

    @Test
    fun selectedMessagesOpenUnifiedRecipientPicker() {
        val messages = listOf(1, 2).map { id ->
            Message(org.monogram.core.models.MessageId(PeerId(1), id), null, "Message $id", id.toLong(), outgoing = true)
        }
        launch(messages)
        compose.runOnIdle { root.openChatForPlayback(1) }
        compose.onNodeWithText("Message 1").performTouchInput { longClick() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(org.monogram.feature.dialog.R.string.dialog_select_message)).performClick()
        compose.onNodeWithText("Message 2").performClick()
        compose.onNodeWithContentDescription(context.getString(org.monogram.feature.dialog.R.string.dialog_forward)).performClick()
        compose.onNodeWithText(context.getString(org.monogram.feature.chats.R.string.chats_share_recipient)).assertIsDisplayed()
        compose.runOnIdle {
            val picker = (root.stack.value.active.instance as RootComponent.Child.Recipients).component
            assertEquals(listOf(1, 2), picker.request.messageIds)
            assertEquals(1L, picker.request.fromChatId)
        }
    }

    @Test
    fun imeImageBecomesOnePendingAttachmentAndKeepsDraft() {
        launch()
        compose.runOnIdle { root.openChatForPlayback(1) }
        val editor = compose.onNode(hasSetTextAction())
        editor.performTextInput("Image caption")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = java.io.File(context.cacheDir, "ime-content-test.png")
        android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE)
            image.outputStream().use { compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", image)
        scenario!!.onActivity { activity ->
            fun findEditor(view: android.view.View): android.view.View? {
                if (view.onCheckIsTextEditor()) return view
                if (view is android.view.ViewGroup) {
                    repeat(view.childCount) { index -> findEditor(view.getChildAt(index))?.let { return it } }
                }
                return null
            }
            val info = android.view.inputmethod.EditorInfo()
            val connection = checkNotNull(checkNotNull(findEditor(activity.window.decorView)).onCreateInputConnection(info))
            assertTrue(androidx.core.view.inputmethod.EditorInfoCompat.getContentMimeTypes(info).isNotEmpty())
            assertTrue(androidx.core.view.inputmethod.InputConnectionCompat.commitContent(connection, info,
                androidx.core.view.inputmethod.InputContentInfoCompat(
                uri, android.content.ClipDescription("test image", arrayOf("image/png")), null,
            ), 0, null))
        }
        compose.waitUntil(10_000) {
            val dialog = (root.stack.value.active.instance as RootComponent.Child.Dialog).component
            dialog.state.value.pendingAttach.size == 1
        }
        compose.runOnIdle {
            val dialog = (root.stack.value.active.instance as RootComponent.Child.Dialog).component
            assertEquals(1, dialog.state.value.pendingAttach.size)
            assertEquals("photo", dialog.state.value.pendingAttach.single().kind)
        }
        assertTrue(editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text.contains("Image caption"))
        image.delete()
    }

    @Test
    fun imeRangeSelectionDoesNotOpenFormattingMenuUntilComposeRequestsIt() {
        launch()
        compose.runOnIdle { root.openChatForPlayback(1) }
        val editor = compose.onNode(hasSetTextAction())
        editor.performTextInput("First second third")
        scenario!!.onActivity { activity ->
            fun findEditor(view: android.view.View): android.view.View? {
                if (view.onCheckIsTextEditor()) return view
                if (view is android.view.ViewGroup) {
                    repeat(view.childCount) { index -> findEditor(view.getChildAt(index))?.let { return it } }
                }
                return null
            }
            val view = checkNotNull(findEditor(activity.window.decorView))
            val connection = checkNotNull(view.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            assertTrue(connection.setSelection(6, 12))
        }

        val format = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(org.monogram.feature.dialog.R.string.dialog_format)
        compose.waitForIdle()
        assertEquals(TextRange(6, 12), editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        compose.onNodeWithText(format).assertDoesNotExist()

        editor.performTouchInput { longClick(Offset(20f, center.y)) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(format)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(format).assertIsDisplayed()
    }

    private fun launch(messages: List<Message> = emptyList()) {
        val client = fakeClient(messages)
        UiStateTestActivity.content = { context ->
            val component = remember(context) {
                RootComponent(
                    componentContext = context,
                    storeFactory = DefaultStoreFactory(),
                    client = client,
                    warmup = null,
                    sessionStore = null,
                    mediaRepository = null,
                    startOnHome = true,
                )
            }
            root = component
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MonogramTheme {
                    Box(Modifier.fillMaxSize()) {
                        RootContent(component, Modifier.testTag("composer-editing-test"))
                    }
                }
            }
        }
        scenario = ActivityScenario.launch(UiStateTestActivity::class.java)
        compose.onNodeWithText("Work").assertIsDisplayed()
    }

    private fun fakeClient(messages: List<Message>): MtprotoClient {
        val chats = listOf(Chat(PeerId(1), "Chat 001", lastMessageDate = 1L))
        return Proxy.newProxyInstance(
            MtprotoClient::class.java.classLoader,
            arrayOf(MtprotoClient::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ComposerEditingFakeClient"
                "libraryVersion" -> "test"
                "updates", "sessionLost" -> emptyFlow<Nothing>()
                "getChats" -> Outcome.Ok(chats)
                "getFolders" -> Outcome.Ok(listOf(Folder(2, "Work", chatIds = chats.map { it.id })))
                "getHistory", "getHistoryPage" -> Outcome.Ok(messages)
                "getPinnedMessages" -> Outcome.Ok(emptyList<Message>())
                "getProfile" -> {
                    val id = args!![0] as PeerId
                    Outcome.Ok(Profile(id, "user", "Chat 001"))
                }
                "getGroupAdminTags" -> Outcome.Ok(emptyMap<PeerId, String>())
                "setDialogForeground", "close" -> Unit
                "updateStatus", "setTyping", "readHistory" -> Outcome.Ok(Unit)
                else -> Outcome.Err("Unsupported in composer UI test: ${method.name}")
            }
        } as MtprotoClient
    }
}
