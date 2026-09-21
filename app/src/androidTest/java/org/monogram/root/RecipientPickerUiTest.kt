package org.monogram.root

import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithContentDescription
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.flow.emptyFlow
import org.monogram.core.models.Folder
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.Profile
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.monogram.R
import org.monogram.UiStateTestActivity
import org.monogram.core.common.Outcome
import org.monogram.core.models.Chat
import org.monogram.core.models.PeerId
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.network.bridge.MtprotoClient

class RecipientPickerUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<UiStateTestActivity>? = null
    private lateinit var root: RootComponent

    @After fun cleanup() {
        scenario?.close()
        UiStateTestActivity.content = {}
    }

    @Test fun selectsMultipleRecipientsAndKeepsForwardOptions() {
        launch()
        compose.onNodeWithText("Work").performClick()
        val home = root.stack.value.active.instance as RootComponent.Child.Home
        compose.runOnIdle { root.openChatForPlayback(9) }
        compose.runOnIdle {
            (root.stack.value.active.instance as RootComponent.Child.Dialog).component.onForwardMessages(
                listOf(3, 4, 5, 6).map { Message(MessageId(PeerId(9), it), null, "Message $it", 1, outgoing = true) },
            )
        }
        val picker = (root.stack.value.active.instance as RootComponent.Child.Recipients).component
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(org.monogram.feature.chats.R.string.chats_share_recipient)).assertIsDisplayed()
        compose.onNodeWithText("Work").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.recipient_send, 0)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.recipient_messages, 4)).assertIsDisplayed()
        capture("recipient-empty.png")
        compose.onNodeWithText("Alice").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText(context.getString(R.string.recipient_send, 1)).assertIsEnabled()
        compose.onNodeWithText("Bob").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText(context.getString(R.string.recipient_hide_author)).performClick()
        compose.onNodeWithText(context.getString(R.string.recipient_comment)).performTextInput("Comment")
        compose.onNodeWithText(context.getString(R.string.recipient_send, 2)).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.recipient_messages, 4)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(setOf(1L, 2L), picker.state.value.selected)
            assertEquals("Comment", picker.state.value.comment)
            assertTrue(picker.state.value.dropAuthor)
        }
        capture("recipient-picker.png")
        compose.onNodeWithText("Bob").performClick().assertIsOff()
        compose.onNodeWithText(context.getString(R.string.recipient_send, 1)).assertIsEnabled()
        compose.onNodeWithContentDescription(context.getString(org.monogram.feature.chats.R.string.chats_share_cancel)).performClick()
        compose.runOnIdle {
            assertTrue(root.stack.value.active.instance is RootComponent.Child.Dialog)
            assertTrue(root.stack.value.items.any { it.instance === home })
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        var captured: android.graphics.Bitmap? = null
        compose.waitUntil(5_000) {
            captured = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            captured != null
        }
        val screenshot = checkNotNull(captured)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    @Test fun externalShareUsesExistingChatList() {
        launch()
        compose.runOnIdle { root.openIncomingShare(IncomingShare("Shared text", emptyList())) }
        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.onNodeWithText("Alice").performClick()
        scenario!!.recreate()
        compose.waitForIdle()
        val home = root.stack.value.items.first { it.instance is RootComponent.Child.Home }.instance
        compose.runOnIdle {
            val picker = (root.stack.value.active.instance as RootComponent.Child.Recipients).component
            assertEquals(setOf(1L), picker.state.value.selected)
            assertEquals("Shared text", picker.state.value.comment)
            picker.onClose()
        }
        compose.runOnIdle { assertTrue(root.stack.value.active.instance === home) }
    }

    private fun launch() {
        val chats = listOf(Chat(PeerId(1), "Alice"), Chat(PeerId(2), "Bob"))
        val client = Proxy.newProxyInstance(MtprotoClient::class.java.classLoader, arrayOf(MtprotoClient::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "RecipientUiFake"
                "libraryVersion" -> "test"
                "updates", "sessionLost" -> emptyFlow<Nothing>()
                "getChats" -> Outcome.Ok(chats)
                "loadMoreChats", "loadMoreFolderChats" -> Outcome.Ok(emptyList<Chat>())
                "getFolders" -> Outcome.Ok(listOf(Folder(2, "Work", chatIds = chats.map { it.id })))
                "getHistory", "getHistoryPage" -> Outcome.Ok(listOf(3, 4).map {
                    Message(MessageId(PeerId(9), it), null, "Message $it", 1, outgoing = true)
                })
                "getPinnedMessages" -> Outcome.Ok(emptyList<Message>())
                "getProfile" -> Outcome.Ok(Profile(args!![0] as PeerId, "source", "Source chat"))
                "getGroupAdminTags" -> Outcome.Ok(emptyMap<PeerId, String>())
                "setDialogForeground", "close" -> Unit
                "updateStatus", "setTyping", "readHistory" -> Outcome.Ok(Unit)
                else -> Outcome.Err("Unsupported in UI test: ${method.name}")
            }
        } as MtprotoClient
        UiStateTestActivity.content = { context ->
            root = remember(context) {
                RootComponent(context, DefaultStoreFactory(), client, null, null, null, startOnHome = true)
            }
            val arguments = InstrumentationRegistry.getArguments()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,
                arguments.getString("recipientFontScale")?.toFloatOrNull() ?: density.fontScale)) {
                MonogramTheme(darkTheme = arguments.getString("recipientDark") == "true") { RootContent(root) }
            }
        }
        scenario = ActivityScenario.launch(UiStateTestActivity::class.java)
        compose.onNodeWithText("Work").assertIsDisplayed()
    }
}
