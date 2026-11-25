package org.monogram

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
    private var failFirstTranslation = false
    private var restorePreferences: (() -> Unit)? = null
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dialog get() = (root.stack.value.active.instance as RootComponent.Child.Dialog).component

    @After
    fun cleanup() {
        scenario?.close()
        database?.close()
        restorePreferences?.invoke()
        UiStateTestActivity.content = {}
        TextToolsSettings.setDialog(account, peer, "es", false)
    }

    @Test
    fun messageMenuOpensTranslationAndSummaryWithoutReplacingOriginal() {
        failFirstTranslation = true
        launch()
        val translate = context.getString(DialogR.string.dialog_tools_translate)
        compose.runOnIdle {
            TextToolsSettings.setGlobal(false, true, emptySet())
            dialog.onTextTools(org.monogram.feature.dialog.TextToolsAction.Initialize)
        }
        compose.onNodeWithText(source).performTouchInput { click() }
        compose.onNodeWithText(translate).assertDoesNotExist()
        androidx.test.espresso.Espresso
            .pressBack()
        compose.runOnIdle {
            TextToolsSettings.setGlobal(true, true, emptySet())
            dialog.onTextTools(org.monogram.feature.dialog.TextToolsAction.Initialize)
        }
        compose.onNodeWithText(source).performTouchInput { click() }
        compose
            .onNodeWithText(context.getString(DialogR.string.dialog_tools_translate))
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithText(context.getString(DialogR.string.dialog_tools_retry_after, 37))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(DialogR.string.dialog_retry_load)).performClick()
        compose.onNodeWithText("Translated fixture").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(
                original,
                dialog.state.value.messages
                    .single(),
            )
        }
        compose.onNodeWithText(context.getString(DialogR.string.dialog_tools_show_original))
            .performClick()
        compose.onNodeWithText(source).performTouchInput { click() }
        compose
            .onNodeWithText(context.getString(DialogR.string.dialog_tools_summary))
            .assertIsDisplayed()
            .performClick()
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
        failAvailability = true
        launch()
        compose.waitUntil(10_000) { dialog.state.value.textTools.hasChatTranslationContent }
        compose.onNodeWithText(source).performTouchInput { click() }
        compose.onNodeWithText(context.getString(DialogR.string.dialog_tools_translate))
            .performClick()
        compose.waitUntil(10_000) {
            dialog.state.value.textTools.translations[1]
                ?.result
                ?.text == "Translated fixture"
        }
        compose.onNodeWithText("Translated fixture").assertIsDisplayed()
        compose.onNodeWithText(context.getString(DialogR.string.dialog_tools_show_original))
            .performClick()
        compose.runOnIdle { failAvailability = false }
        compose.onNodeWithText(context.getString(DialogR.string.dialog_retry_load)).performClick()
        compose.waitUntil(10_000) { dialog.state.value.textTools.chatAllowed }
        compose
            .onNodeWithText(
                context.getString(
                    DialogR.string.dialog_tools_target_language,
                    org.monogram.core.ui.components
                        .languageDisplayName("es"),
                ),
            ).performClick()
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
            assertEquals(2, translations)
        }
        compose
            .onNode(
                hasText(context.getString(DialogR.string.dialog_tools_show_original)) and
                        hasAnySibling(hasContentDescription(context.getString(DialogR.string.dialog_tools_settings))),
            ).performClick()
        compose.onNodeWithText(source).assertIsDisplayed()
        compose.runOnIdle { assertTrue(!TextToolsSettings.chatEnabled(account, peer)) }
    }

    private var database: org.monogram.core.database.MonogramDatabase? = null
    private var selfProfileFails = false

    @Test
    fun retriesUnknownPremiumInOpenChannel() {
        automaticChannel = false
        selfProfileFails = true
        val db =
            androidx.room.Room
                .inMemoryDatabaseBuilder(
                    context,
                    org.monogram.core.database.MonogramDatabase::class.java
                )
                .build()
        database = db
        val store = SessionMetadataStore(db)
        kotlinx.coroutines.runBlocking {
            val session =
                org.monogram.core.models
                    .AuthSession(PeerId(account), 2)
            store.saveAuthorized(session)
            store.savePremium(true)
            store.clearAuthorized()
            assertEquals(null, store.readMeta(SessionMetadataStore.KEY_PREMIUM))
            store.savePremium(false) // Legacy logout marker left by older versions.
            store.saveAuthorized(session)
            assertEquals(null, store.readMeta(SessionMetadataStore.KEY_PREMIUM))
        }
        launch(store)
        compose.waitUntil(10_000) {
            dialog.state.value.textTools.premiumLookupError != null &&
                    dialog.state.value.textTools.hasChatTranslationContent
        }
        compose.runOnIdle { selfProfileFails = false }
        compose.onNodeWithText(context.getString(DialogR.string.dialog_retry_load)).performClick()
        compose.waitUntil(10_000) { dialog.state.value.textTools.chatAllowed }
        compose
            .onNodeWithText(
                context.getString(
                    DialogR.string.dialog_tools_target_language,
                    org.monogram.core.ui.components
                        .languageDisplayName("es"),
                ),
            ).performClick()
        compose.waitUntil(10_000) {
            dialog.state.value.textTools.translations[1]
                ?.result
                ?.text == "Translated fixture"
        }
        compose.onNodeWithText("Translated fixture").assertIsDisplayed()
    }

    private var autoEnabled = true

    @Test
    fun premiumWithManualOnlyConfigTranslatesOneMessage() {
        autoEnabled = false
        launch()
        compose.runOnIdle {
            assertTrue(
                accountUpdates.tryEmit(
                    org.monogram.network.bridge.MtprotoUpdate
                        .AccountPremium(true),
                ),
            )
        }
        compose.waitUntil(10_000) { dialog.state.value.textTools.hasChatTranslationContent }
        compose.onNode(hasContentDescription(context.getString(DialogR.string.dialog_tools_settings)))
            .assertDoesNotExist()
        compose.onNodeWithText(source).performTouchInput { click() }
        compose.onNodeWithText(context.getString(DialogR.string.dialog_tools_translate))
            .performClick()
        compose.waitUntil(10_000) {
            dialog.state.value.textTools.translations[1]
                ?.result
                ?.text == "Translated fixture"
        }
        compose.onNodeWithText("Translated fixture").assertIsDisplayed()
    }

    private var failAvailability = false
    private var automaticChannel = true
    private val accountUpdates =
        kotlinx.coroutines.flow.MutableSharedFlow<org.monogram.network.bridge.MtprotoUpdate>(
            extraBufferCapacity = 1,
        )

    private var knownFree = false

    @Test
    fun premiumUpdateEnablesTranslationInOrdinaryChannel() {
        knownFree = true
        automaticChannel = false
        launch()
        val label =
            context.getString(
                DialogR.string.dialog_tools_target_language,
                org.monogram.core.ui.components
                    .languageDisplayName("es"),
            )
        compose.waitUntil(10_000) { dialog.state.value.textTools.hasChatTranslationContent }
        compose.onNodeWithText(label).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(
                accountUpdates.tryEmit(
                    org.monogram.network.bridge.MtprotoUpdate
                        .AccountPremium(true),
                ),
            )
        }
        compose.waitUntil(10_000) { dialog.state.value.textTools.chatAllowed }
        compose.onNodeWithText(label).performClick()
        compose.waitUntil(10_000) {
            dialog.state.value.textTools.translations[1]
                ?.result
                ?.text == "Translated fixture"
        }
        compose.onNodeWithText("Translated fixture").assertIsDisplayed()
    }

    private fun launch(store: SessionMetadataStore? = null) {
        TextToolsSettings.install(context)
        val manual = TextToolsSettings.manualTranslation()
        val chat = TextToolsSettings.chatTranslation()
        val excluded = TextToolsSettings.excludedLanguages()
        restorePreferences = { TextToolsSettings.setGlobal(manual, chat, excluded) }
        TextToolsSettings.setGlobal(true, true, emptySet())
        TextToolsSettings.setDialog(account, peer, "es", false)
        val metadata =
            store
                ?: object : SessionMetadataStore() {
                    override suspend fun readAuthorizedUserId() = PeerId(account)

                    override suspend fun isAuthorized() = true

                    override suspend fun readMeta(key: String) =
                        when (key) {
                            SessionMetadataStore.KEY_PREMIUM -> if (knownFree) "0" else null
                            SessionMetadataStore.KEY_AUTHORIZED -> "1"
                            SessionMetadataStore.KEY_USER_ID -> account.toString()
                            SessionMetadataStore.KEY_DC_ID -> "2"
                            else -> null
                        }
                }
        val warmup =
            object : org.monogram.core.database.OfflineWarmup() {
                override suspend fun chat(chatId: PeerId) =
                    Chat(chatId, "Translation fixture channel", isChannel = true)
            }
        val client = fakeClient()
        UiStateTestActivity.content = { componentContext ->
            val component =
                remember(componentContext) {
                    RootComponent(
                        componentContext,
                        DefaultStoreFactory(),
                        client,
                        warmup = warmup,
                        sessionStore = metadata,
                        mediaRepository = null,
                        startOnHome = true,
                    )
                }
            root = component
            MonogramTheme { Box(Modifier.fillMaxSize()) { RootContent(component) } }
        }
        scenario = ActivityScenario.launch(UiStateTestActivity::class.java)
        compose.waitUntil(10_000) { ::root.isInitialized }
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
                .isNotEmpty() &&
                    dialog.state.value.textTools
                        .run { availability != null || availabilityError != null }
        }
    }

    private fun fakeClient(): MtprotoClient {
        val chats =
            listOf(
                Chat(
                    PeerId(peer),
                    "Translation fixture channel",
                    isChannel = true,
                    lastMessageDate = 1,
                ),
            )
        return Proxy.newProxyInstance(
            MtprotoClient::class.java.classLoader,
            arrayOf(MtprotoClient::class.java),
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

                "updates" -> {
                    accountUpdates
                }

                "sessionLost", "observeConnectionStatus" -> {
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
                    if (args?.firstOrNull() == PeerId(0)) {
                        if (selfProfileFails) {
                            Outcome.Err(
                                "NETWORK_ERROR",
                            )
                        } else {
                            Outcome.Ok(Profile(PeerId(account), "user", "Self", isPremium = true))
                        }
                    } else {
                        Outcome.Ok(Profile(PeerId(peer), "channel", "Translation fixture channel"))
                    }
                }

                "getGroupAdminTags" -> {
                    Outcome.Ok(emptyMap<PeerId, String>())
                }

                "getTextToolsAvailability" -> {
                    Outcome.Ok(
                        TextToolsAvailability(
                            chatTranslation = autoEnabled,
                            channelAutoTranslation =
                                automaticChannel && !failAvailability,
                            peerLookupError = "NETWORK_ERROR".takeIf { failAvailability },
                        ),
                    )
                }

                "translateMessageTexts" -> {
                    translations++
                    if (failFirstTranslation && translations == 1) return@newProxyInstance Outcome.Err(
                        "FLOOD_WAIT_37"
                    )
                    Outcome.Ok(
                        listOf(
                            TextToolText(
                                "Translated fixture",
                                listOf(TextEntity("bold", 0, 10)),
                            ),
                        ),
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
