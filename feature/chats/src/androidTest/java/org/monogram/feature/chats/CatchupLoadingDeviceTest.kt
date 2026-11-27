package org.monogram.feature.chats

import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.Chat
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PeerId
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.chats.ui.ChatsContent
import org.monogram.network.bridge.ConnectionStatus
import org.monogram.network.bridge.DialogPage
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.bridge.session.ProxyConnectionStatus
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

class CatchupLoadingDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun connectionTitleTracksSessionStatusWithoutFollowingListRefresh() {
        val startedAt = SystemClock.elapsedRealtime()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val lifecycle = LifecycleRegistry()
        val events = MutableSharedFlow<MtprotoUpdate>(replay = 1)
        events.tryEmit(MtprotoUpdate.SyncState(true))
        val connection = MutableStateFlow(ConnectionStatus.Updating)
        val allowPage = Channel<Unit>(Channel.UNLIMITED)
        check(allowPage.trySend(Unit).isSuccess)
        var proxyOpens = 0
        val row =
            Chat(PeerId(600001), "Synthetic startup chat", lastMessageDate = 1, lastMessageId = 1)
        val cache =
            object : OfflineWarmup() {
                override suspend fun chatsWindow(
                    limit: Int,
                    archiveLimit: Int,
                ) = listOf(row)

                override suspend fun mainListCount() = 1
            }

        @Suppress("UNCHECKED_CAST")
        val client =
            Proxy.newProxyInstance(
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

                    "toString", "libraryVersion" -> {
                        "SyntheticCatchupClient"
                    }

                    "updates" -> {
                        events
                    }

                    "observeConnectionStatus" -> {
                        connection
                    }

                    "sessionLost" -> {
                        emptyFlow<Unit>()
                    }

                    "proxyConnectionStatus" -> {
                        ProxyConnectionStatus.Disabled
                    }

                    "getChatsPage" -> {
                        val continuation = args!!.last() as Continuation<Any?>
                        scope.launch {
                            allowPage.receive()
                            continuation.resume(Outcome.Ok(DialogPage(listOf(row), null)))
                        }
                        COROUTINE_SUSPENDED
                    }

                    "loadMoreFolderChatsPage" -> {
                        Outcome.Ok(DialogPage(emptyList(), null))
                    }

                    "getFolders" -> {
                        Outcome.Ok(emptyList<Chat>())
                    }

                    "getNotifySettings" -> {
                        val continuation = args!!.last() as Continuation<Any?>
                        scope.launch {
                            delay(150)
                            continuation.resume(Outcome.Ok(NotifySettings()))
                        }
                        COROUTINE_SUSPENDED
                    }

                    "close" -> {
                        Unit
                    }

                    else -> {
                        Outcome.Err("unsupported fixture method")
                    }
                }
            } as MtprotoClient
        lateinit var component: ChatsComponent
        compose.runOnUiThread {
            lifecycle.resume()
            component =
                ChatsComponent(
                    DefaultComponentContext(lifecycle),
                    DefaultStoreFactory(),
                    client,
                    cache,
                    sessionStore = null,
                    onOpenChat = { _, _, _ -> },
                    onOpenProxy = { proxyOpens++ },
                )
        }
        compose.setContent { MonogramTheme(dynamicColor = false) { ChatsContent(component) } }
        val updating = compose.activity.getString(org.monogram.core.ui.R.string.status_syncing)
        val idleTitle = compose.activity.getString(R.string.chats_brand)

        fun showStatus(status: ConnectionStatus, title: String) {
            compose.runOnUiThread { connection.value = status }
            compose.waitUntil(5000) { component.state.value.connectionStatus == status }
            compose.onNodeWithText(title).assertIsDisplayed()
        }

        try {
            compose.onNodeWithText("Synthetic startup chat").assertIsDisplayed()
            compose.waitUntil(15000) { !component.state.value.loading && !component.state.value.fromCache }
            compose.onNodeWithText("Synthetic startup chat").assertIsDisplayed()
            Log.i(
                "LoadingUiTiming",
                "operation=chats_initial_render ms=${SystemClock.elapsedRealtime() - startedAt}",
            )
            showStatus(ConnectionStatus.Updating, updating)
            showStatus(ConnectionStatus.Connected, idleTitle)
            compose.onNodeWithText(updating).assertDoesNotExist()
            compose.runOnUiThread { events.tryEmit(MtprotoUpdate.SyncState(false)) }
            compose.waitUntil(10000) { !component.state.value.syncing }

            val refreshAt = SystemClock.elapsedRealtime()
            compose.runOnUiThread { component.onRefresh() }
            compose.waitUntil(10000) { component.state.value.syncing }
            compose.onNodeWithText(idleTitle).assertIsDisplayed()
            compose.onNodeWithText(updating).assertDoesNotExist()

            showStatus(
                ConnectionStatus.WaitingForNetwork,
                compose.activity.getString(R.string.chats_waiting_network)
            )
            showStatus(
                ConnectionStatus.Connecting,
                compose.activity.getString(org.monogram.core.ui.R.string.status_connecting)
            )
            val proxyTitle = compose.activity.getString(R.string.chats_connecting_to_proxy)
            showStatus(ConnectionStatus.ConnectingToProxy, proxyTitle)
            compose.onNodeWithText(proxyTitle).performClick()
            compose.runOnIdle { assertEquals(1, proxyOpens) }
            showStatus(ConnectionStatus.Updating, updating)
            showStatus(ConnectionStatus.Connected, idleTitle)
            showStatus(ConnectionStatus.Idle, idleTitle)
            check(allowPage.trySend(Unit).isSuccess)
            compose.waitUntil(10000) { !component.state.value.syncing }
            compose.onNodeWithText("Synthetic startup chat").assertIsDisplayed()
            Log.i(
                "LoadingUiTiming",
                "operation=chats_refresh_render ms=${SystemClock.elapsedRealtime() - refreshAt}",
            )
        } finally {
            compose.runOnUiThread {
                lifecycle.destroy()
                scope.cancel()
            }
        }
    }
}
