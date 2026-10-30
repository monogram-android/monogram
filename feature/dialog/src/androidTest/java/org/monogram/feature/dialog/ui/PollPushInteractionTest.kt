package org.monogram.feature.dialog.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.models.*
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.DialogStore
import org.monogram.feature.dialog.DialogStoreFactory
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

class PollPushInteractionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun externalPollUpdateRefreshesRealStoreAndRenderedSelectionWithoutSending() {
        val events = MutableSharedFlow<MtprotoUpdate>(replay = 16)
        val reads = AtomicInteger()
        val mutations = AtomicInteger()
        val before = Message(
            MessageId(PeerId(7), 37),
            null,
            null,
            100,
            outgoing = false,
            mediaKind = "poll",
            fileName = """{"q":"Synthetic external poll","id":"123","n":0,"a":[{"t":"Fixture answer","o":"00ff","c":false}]}"""
        )
        val after =
            before.copy(fileName = """{"q":"Synthetic external poll","id":"123","n":12,"a":[{"t":"Fixture answer","o":"00ff","c":true,"v":12}]}""")
        val client = Proxy.newProxyInstance(
            MtprotoClient::class.java.classLoader,
            arrayOf(MtprotoClient::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString", "libraryVersion" -> "SyntheticPollClient"
                "updates" -> events
                "sessionLost" -> emptyFlow<Nothing>()
                "getHistory", "getHistoryPage", "getHistoryPageBackground" -> Outcome.Ok(
                    listOf(
                        before
                    )
                )

                "getMessage" -> {
                    reads.incrementAndGet(); Outcome.Ok(after)
                }

                "getChats" -> Outcome.Ok(listOf(Chat(PeerId(7), "Fixture chat")))
                "getProfile" -> Outcome.Ok(Profile(PeerId(7), "fixture", "Fixture chat"))
                "getPinnedMessages", "getSavedGifs" -> Outcome.Ok(emptyList<Message>())
                "getGroupAdminTags" -> Outcome.Ok(emptyMap<PeerId, String>())
                "sendPollVote", "closePoll" -> {
                    mutations.incrementAndGet(); Outcome.Err("unexpected fixture mutation")
                }

                "setDialogForeground", "close" -> Unit
                "readHistory", "setTyping" -> Outcome.Ok(Unit)
                else -> Outcome.Err("unsupported poll fixture method")
            }
        } as MtprotoClient
        lateinit var store: DialogStore
        compose.setContent {
            val currentStore = remember {
                DialogStoreFactory(
                    DefaultStoreFactory(),
                    client,
                    null,
                    null,
                    PeerId(7),
                    seedIsForum = false
                ).create().also { store = it }
            }
            val flow = remember(currentStore) { currentStore.stateFlow }
            val state by flow.collectAsState()
            DisposableEffect(currentStore) { onDispose { currentStore.dispose() } }
            MonogramTheme(dynamicColor = false) {
                state.messages.firstOrNull()?.poll?.let { PollBubbleCard(it, {}, null) }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Synthetic external poll").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Synthetic external poll").assertIsDisplayed()
        compose.onNodeWithText("Fixture answer").assertIsOff()
        compose.runOnIdle { assertTrue(events.tryEmit(MtprotoUpdate.PollChanged(123))) }
        compose.waitUntil(5_000) { store.state.messages.firstOrNull()?.fileName == after.fileName }
        compose.onNodeWithText("Fixture answer").assertIsOn()
        compose.runOnIdle { assertEquals(1, reads.get()); assertEquals(0, mutations.get()) }
    }
}
