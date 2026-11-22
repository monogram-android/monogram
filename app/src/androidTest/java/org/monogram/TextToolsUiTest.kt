package org.monogram

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.TextEntity
import org.monogram.core.models.TextToolText
import org.monogram.core.models.TextToolsAvailability
import org.monogram.core.ui.TextToolsSettings
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.DialogComponent
import org.monogram.network.bridge.MtprotoClient
import org.monogram.root.RootComponent
import org.monogram.root.RootContent
import java.lang.reflect.Proxy
import org.monogram.feature.dialog.R as DialogR

class TextToolsUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<UiStateTestActivity>? = null
    private lateinit var root: RootComponent
    private val account = 9_999_241L
    private val peer = -1_000_000_000_241L
    private val source =
        "Source fixture for translating and summarizing a channel message. ".repeat(3)
    private val original =
        Message(
            MessageId(PeerId(peer), 1),
            null,
            source,
            1,
            outgoing = false,
            entities = listOf(TextEntity("bold", 0, 6)),
            summaryFromLanguage = "en",
        )
    private var translations = 0
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dialog get() = (root.stack.value.active.instance as RootComponent.Child.Dialog).component

    @After
    fun cleanup() {
        scenario?.close()
        UiStateTestActivity.content = {}
        TextToolsSettings.setDialog(account, peer, "es", false)
    }

    @Test
    fun messageMenuOpensTranslationAndSummaryWithoutReplacingOriginal() {
        launch()
        compose.onNodeWithText(source).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(DialogR.string.dialog_tools_translate))
            .assertIsDisplayed().performClick()
        compose.onNodeWithText("Translated fixture").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(
                original,
                dialog.state.value.messages
                    .single(),
            )
        }
        compose.onNodeWithText(context.getString(DialogR.string.dialog_cancel)).performClick()
        compose.onNodeWithText(source).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(DialogR.string.dialog_tools_summary))
            .assertIsDisplayed().performClick()
        compose.onNodeWithText("Summary fixture").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(
                original,
                dialog.state.value.messages
                    .single(),
            )
        }
    }

    @Test
    fun channelAutoTranslationIsAllowedWithoutPremiumAndPersistsPerChannel() {
        launch()
        compose.onNodeWithText(context.getString(DialogR.string.dialog_tools_translate_chat))
            .performClick()
        compose.waitUntil(10_000) {
            dialog.state.value.textTools.translations
                .isNotEmpty()
        }
        compose.onNodeWithText("Translated fixture").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(dialog.state.value.textTools.chatAllowed)
            assertTrue(TextToolsSettings.chatEnabled(account, peer))
            assertEquals("es", TextToolsSettings.targetLanguage(account, peer))
            assertEquals(
                original,
                dialog.state.value.messages
                    .single(),
            )
            assertEquals(1, translations)
        }
        compose.onNodeWithText(context.getString(DialogR.string.dialog_tools_show_original))
            .performClick()
        compose.onNodeWithText(source).assertIsDisplayed()
        compose.runOnIdle { assertTrue(!TextToolsSettings.chatEnabled(account, peer)) }
    }

    private fun launch() {
        TextToolsSettings.install(context)
        TextToolsSettings.setDialog(account, peer, "es", false)
        val metadata =
            object : SessionMetadataStore() {
                override suspend fun readAuthorizedUserId() = PeerId(account)

                override suspend fun isAuthorized() = true

                override suspend fun readMeta(key: String) =
                    when (key) {
                        SessionMetadataStore.KEY_AUTHORIZED -> "1"
                        SessionMetadataStore.KEY_USER_ID -> account.toString()
                        SessionMetadataStore.KEY_DC_ID -> "2"
                        else -> null
                    }
            }
        val client = fakeClient()
        UiStateTestActivity.content = { componentContext ->
            val component =
                remember(componentContext) {
                    RootComponent(
                        componentContext,
                        DefaultStoreFactory(),
                        client,
                        warmup = null,
                        sessionStore = metadata,
                        mediaRepository = null,
                        startOnHome = true,
                    )
                }
            root = component
            MonogramTheme { Box(Modifier.fillMaxSize()) { RootContent(component) } }
        }
        scenario = ActivityScenario.launch(UiStateTestActivity::class.java)
        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.runOnIdle { root.openChatForPlayback(peer) }
        compose.waitUntil(10_000) {
            root.stack.value.active.instance is RootComponent.Child.Dialog
        }
        compose.runOnIdle {
            dialog.onRefresh()
            dialog.onTextTools(org.monogram.feature.dialog.TextToolsAction.Initialize)
        }
        compose.waitUntil(10_000) {
            dialog.state.value.messages
                .isNotEmpty() && dialog.state.value.textTools.availability != null
        }
    }

    private fun fakeClient(): MtprotoClient {
        val chats = listOf(
            Chat(
                PeerId(peer),
                "Translation fixture channel",
                isChannel = true,
                lastMessageDate = 1
            )
        )
        return Proxy.newProxyInstance(
            MtprotoClient::class.java.classLoader,
            arrayOf(MtprotoClient::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> {
                    proxy === args?.firstOrNull()
                }

                "hashCode" -> {
                    System.identityHashCode(proxy)
                }

                "toString" -> {
                    "TextToolsFakeClient"
                }

                "libraryVersion" -> {
                    "test"
                }

                "updates", "sessionLost" -> {
                    emptyFlow<Nothing>()
                }

                "proxyConnectionStatus" -> {
                    org.monogram.network.bridge.session.ProxyConnectionStatus.Disabled
                }

                "getChats" -> {
                    Outcome.Ok(chats)
                }

                "getFolders" -> {
                    Outcome.Ok(listOf(Folder(2, "Work", chatIds = chats.map { it.id })))
                }

                "getHistory", "getHistoryPage", "getHistoryPageBackground" -> {
                    Outcome.Ok(listOf(original))
                }

                "getPinnedMessages" -> {
                    Outcome.Ok(emptyList<Message>())
                }

                "getProfile" -> {
                    Outcome.Ok(Profile(PeerId(peer), "channel", "Translation fixture channel"))
                }

                "getGroupAdminTags" -> {
                    Outcome.Ok(emptyMap<PeerId, String>())
                }

                "getTextToolsAvailability" -> {
                    Outcome.Ok(TextToolsAvailability(channelAutoTranslation = true))
                }

                "translateMessageTexts" -> {
                    translations++
                    Outcome.Ok(
                        listOf(
                            TextToolText(
                                "Translated fixture",
                                listOf(TextEntity("bold", 0, 10))
                            )
                        )
                    )
                }

                "summarizeMessageText" -> {
                    Outcome.Ok(TextToolText("Summary fixture"))
                }

                "setDialogForeground", "close" -> {
                    Unit
                }

                "updateStatus", "setTyping", "readHistory" -> {
                    Outcome.Ok(Unit)
                }

                else -> {
                    Outcome.Err("Unsupported in text tools UI test: ${method.name}")
                }
            }
        } as MtprotoClient
    }
}
