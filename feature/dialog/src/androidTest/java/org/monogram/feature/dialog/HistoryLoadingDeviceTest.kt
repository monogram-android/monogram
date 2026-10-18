package org.monogram.feature.dialog

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.*
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.ui.DialogContent
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

class HistoryLoadingDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val peer = PeerId(500001)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val events = MutableSharedFlow<MtprotoUpdate>(extraBufferCapacity = 32)
    private var offline = false
    private var historyCalls = 0
    private var olderCalls = 0
    private var newerCalls = 0
    private fun message(id: Int) = Message(
        id = MessageId(peer, id), senderId = peer, text = "Fixture $id",
        date = 1700000000L + id, outgoing = false,
    )

    @Suppress("UNCHECKED_CAST")
    private fun client() = Proxy.newProxyInstance(
        MtprotoClient::class.java.classLoader, arrayOf(MtprotoClient::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString", "libraryVersion" -> "SyntheticHistoryClient"
            "updates" -> events
            "sessionLost" -> emptyFlow<Unit>()
            "getHistory", "getHistoryPage", "getHistoryPageBackground" -> {
                val limit = args!![1] as Int
                check(limit <= 100)
                val offset = if (method.name == "getHistory") 0 else args[2] as Int
                val addOffset = if (method.name == "getHistory") 0 else args[4] as Int
                if (method.name == "getHistory") historyCalls++
                else if (addOffset < 0) newerCalls++ else olderCalls++
                val rows = if (offset == 0) (240 downTo 1).take(limit)
                else if (addOffset < 0) ((offset - addOffset - 1).coerceAtMost(240) downTo 1).take(
                    limit
                )
                else ((offset - 1) downTo 1).take(limit)
                val continuation = args.last() as Continuation<Any?>
                val failure = offline
                scope.launch {
                    delay(180)
                    continuation.resume(
                        if (failure) Outcome.Err("offline") else Outcome.Ok(
                            rows.map(
                                ::message
                            )
                        )
                    )
                }
                COROUTINE_SUSPENDED
            }

            "getPinnedMessages", "getSavedGifs" -> Outcome.Ok(emptyList<Message>())
            "getProfile", "getNotifySettings", "getForumTopics", "getForumTopicsById" -> Outcome.Err(
                "unsupported fixture metadata"
            )

            "setDialogForeground", "close", "setForeground" -> Unit
            else -> if (method.returnType == Void.TYPE) Unit else Outcome.Err("unsupported fixture method")
        }
    } as MtprotoClient

    private fun scenario(cached: Boolean, unread: Boolean, body: (DialogComponent) -> Unit) {
        val lifecycle = LifecycleRegistry()
        val frames = mutableListOf<Long>()
        val frameListener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames += metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION)
        }
        lateinit var component: DialogComponent
        val cache = object : OfflineWarmup() {
            override suspend fun chat(chatId: PeerId) = Chat(
                peer, "Synthetic conversation", lastMessageId = 240,
                unreadCount = if (unread) 20 else 0, readInboxMaxId = if (unread) 220 else 240,
            )

            override suspend fun messages(chatId: PeerId, limit: Int) =
                if (cached) (240 downTo 201).map(::message) else emptyList()
        }
        compose.runOnUiThread {
            compose.activity.window.addOnFrameMetricsAvailableListener(
                frameListener,
                android.os.Handler(android.os.Looper.getMainLooper())
            )
            lifecycle.resume()
            component = DialogComponent(
                DefaultComponentContext(lifecycle), DefaultStoreFactory(), client(), cache,
                sessionStore = null, mediaRepository = null, chatId = peer, isForum = false,
                onBack = {},
            )
        }
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                DialogContent(
                    component,
                    Modifier.fillMaxSize()
                )
            }
        }
        try {
            body(component)
        } finally {
            compose.runOnUiThread {
                compose.activity.window.removeOnFrameMetricsAvailableListener(frameListener)
                val sorted = frames.sorted()
                val p95 = sorted.getOrNull(((sorted.size - 1) * 0.95).toInt()) ?: 0L
                android.util.Log.i(
                    "SyntheticHistoryFrames",
                    "cached=$cached unread=$unread frames=${sorted.size} p95Ms=${p95 / 1000000.0}"
                )
                android.util.Log.i(
                    "SyntheticHistoryTrace",
                    "cached=$cached unread=$unread history=$historyCalls older=$olderCalls newer=$newerCalls rows=${component.state.value.messages.size} loading=${component.state.value.loading} anchor=${component.state.value.anchorMessageId}"
                )
                lifecycle.destroy()
                scope.cancel()
            }
        }
    }

    @Test
    fun uncachedOpeningPagingAndIncomingMessagesRender() = scenario(false, false) { component ->
        compose.waitUntil(15000) { !component.state.value.loading && component.state.value.messages.isNotEmpty() }
        compose.onNodeWithText("Fixture 240", substring = true).assertIsDisplayed()
        compose.runOnUiThread { events.tryEmit(MtprotoUpdate.NewMessage(message(241))) }
        compose.waitUntil(10000) { component.state.value.messages.any { it.id.id == 241 } }
        compose.onNodeWithText("Fixture 241", substring = true).assertIsDisplayed()
        compose.runOnUiThread { component.onLoadOlder() }
        compose.waitUntil(10000) { component.state.value.messages.size > 40 }
        compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeDown() }
        compose.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText("Fixture 210", substring = true))
        compose.onNodeWithText("Fixture 210", substring = true).assertIsDisplayed()
        compose.runOnUiThread { events.tryEmit(MtprotoUpdate.NewMessage(message(242))) }
        compose.waitUntil(10000) { component.state.value.messages.any { it.id.id == 242 } }
        compose.onNodeWithText("Fixture 210", substring = true).assertIsDisplayed()
        assertEquals(
            component.state.value.messages.size,
            component.state.value.messages.map { it.id }.distinct().size
        )
        compose.runOnUiThread { component.onJumpToMessage(50) }
        compose.waitUntil(10000) { component.state.value.anchorMessageId == 50 }
        compose.runOnUiThread { component.onLoadNewer() }
        compose.waitUntil(10000) { newerCalls >= 2 }
        compose.runOnUiThread { component.onLoadOlder() }
        compose.waitUntil(10000) { olderCalls > 0 }
        assertTrue(historyCalls >= 1)
    }

    @Test
    fun cachedUnreadOpeningSurvivesOfflineAndRetry() = scenario(true, true) { component ->
        compose.waitUntil(15000) { component.state.value.messages.isNotEmpty() && !component.state.value.loading }
        compose.waitUntil(10000) { component.state.value.anchorMessageId == 221 }
        compose.onNodeWithText("Fixture 221", substring = true).assertIsDisplayed()
        compose.runOnUiThread { offline = true; component.onRefresh() }
        compose.waitUntil(10000) { historyCalls >= 2 && !component.state.value.loading }
        assertTrue(component.state.value.messages.isNotEmpty())
        compose.runOnUiThread { offline = false; component.onRefresh() }
        compose.waitUntil(10000) { historyCalls >= 3 && !component.state.value.loading }
        assertTrue(component.state.value.messages.isNotEmpty())
    }
}
