package org.monogram.root

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.monogram.UiStateTestActivity
import org.monogram.core.common.Outcome
import org.monogram.core.database.*
import org.monogram.core.database.entity.OutgoingSendPhase
import org.monogram.core.models.*
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.network.bridge.MtprotoClient
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class DiceSendInteractionTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun composerDiceRetainsIdentityThroughUncertaintyAndExplicitRetryThenRendersServerResult() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "dice-ui-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, MonogramDatabase::class.java, name).build()
        val cache = OfflineWarmup(db)
        runBlocking {
            cache.upsertChats(
                listOf(
                    Chat(
                        PeerId(7),
                        "Synthetic dice chat",
                        lastMessageDate = 1000L
                    )
                )
            )
        }
        val events =
            kotlinx.coroutines.flow.MutableSharedFlow<org.monogram.network.bridge.MtprotoUpdate>(
                replay = 4
            )
        val attempts = AtomicInteger()
        val identities = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = Proxy.newProxyInstance(
            MtprotoClient::class.java.classLoader,
            arrayOf(MtprotoClient::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString", "libraryVersion" -> "SyntheticDiceClient"
                "proxyConnectionStatus" -> org.monogram.network.bridge.session.ProxyConnectionStatus.Disabled
                "updates" -> events
                "sessionLost" -> emptyFlow<Nothing>()
                "getChats" -> Outcome.Ok(
                    listOf(
                        Chat(
                            PeerId(7),
                            "Synthetic dice chat",
                            lastMessageDate = 1000L
                        )
                    )
                )

                "getFolders" -> Outcome.Ok(emptyList<Folder>())
                "getHistory", "getHistoryPage", "getHistoryPageBackground", "getPinnedMessages", "getSavedGifs" -> Outcome.Ok(
                    emptyList<Message>()
                )

                "getProfile" -> Outcome.Ok(Profile(PeerId(7), "fixture", "Synthetic dice chat"))
                "getGroupAdminTags" -> Outcome.Ok(emptyMap<PeerId, String>())
                "sendDiceWithOptions" -> {
                    val id = args!![2] as Long
                    identities += id
                    assertEquals("🎲", args[1])
                    if (attempts.incrementAndGet() == 1) Outcome.Err("RPC 500: INTERNAL")
                    else {
                        val message = Message(
                            MessageId(PeerId(7), 37),
                            null,
                            null,
                            System.currentTimeMillis() / 1000,
                            outgoing = true,
                            randomId = id,
                            mediaKind = "dice",
                            fileName = """{"e":"🎲","v":4}"""
                        )
                        val result = OutgoingMessageResult.Sent(message)
                        runBlocking {
                            cache.applyOutgoingResult(id, result)
                            events.emit(org.monogram.network.bridge.MtprotoUpdate.NewMessage(message))
                        }
                        Outcome.Ok(result)
                    }
                }

                "sendTextWithOptions", "sendText" -> error("dice must use typed media send")
                "setDialogForeground", "close", "setForeground" -> Unit
                "updateStatus", "setTyping", "readHistory" -> Outcome.Ok(Unit)
                else -> Outcome.Err("unsupported synthetic dice method")
            }
        } as MtprotoClient
        val worker = OutgoingTextSync(cache, { sendRetainedMessage(client, it) })
        var scenario: ActivityScenario<UiStateTestActivity>? = null
        try {
            UiStateTestActivity.content = { componentContext ->
                val root = remember(componentContext) {
                    RootComponent(
                        componentContext,
                        DefaultStoreFactory(),
                        client,
                        cache,
                        null,
                        null,
                        startOnHome = true,
                        bubbleChatId = 7
                    )
                }
                MonogramTheme(dynamicColor = false) { RootContent(root, Modifier.fillMaxSize()) }
            }
            scenario = ActivityScenario.launch(UiStateTestActivity::class.java)
            try {
                compose.waitUntil(5_000) {
                    compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (error: ComposeTimeoutException) {
                throw AssertionError(compose.onRoot().printToString(), error)
            }
            compose.onNode(hasSetTextAction()).performTextInput("🎲")
            compose.onNodeWithContentDescription(context.getString(org.monogram.feature.dialog.R.string.dialog_send))
                .performClick()
            val waiting =
                context.getString(org.monogram.feature.dialog.R.string.dialog_dice_pending)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(waiting).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(waiting).assertIsDisplayed()
            compose.onNodeWithText(
                context.getString(
                    org.monogram.feature.dialog.R.string.dialog_dice_result,
                    0
                )
            ).assertDoesNotExist()
            worker.start(scope)
            compose.waitUntil(5_000) {
                runBlocking {
                    db.outgoingTextSendDao().all()
                        .singleOrNull()?.phase == OutgoingSendPhase.UNCERTAIN
                }
            }
            assertEquals(1, attempts.get())
            compose.onNodeWithText(context.getString(org.monogram.core.ui.R.string.status_retry))
                .performClick()
            val result =
                context.getString(org.monogram.feature.dialog.R.string.dialog_dice_result, 4)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(result).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(result).assertIsDisplayed()
            compose.onNodeWithText(waiting).assertDoesNotExist()
            assertEquals(2, attempts.get())
            assertEquals(identities.first(), identities.last())
            assertTrue(identities.first() != 0L)
            runBlocking { assertTrue(db.outgoingTextSendDao().all().isEmpty()) }
        } finally {
            runBlocking { worker.stop() }; scope.cancel(); scenario?.close(); UiStateTestActivity.content =
                {}
            db.close(); context.deleteDatabase(name)
        }
    }
}
