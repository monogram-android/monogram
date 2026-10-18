package org.monogram.feature.chats

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.Chat
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PeerId
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.chats.ui.ChatsContent
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
    fun cachedStartupAndReconnectKeepUpdatingUntilRecoveryCompletes() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val lifecycle = LifecycleRegistry()
        val events = MutableSharedFlow<MtprotoUpdate>(replay = 1)
        events.tryEmit(MtprotoUpdate.SyncState(true))
        val row =
            Chat(PeerId(600001), "Synthetic startup chat", lastMessageDate = 1, lastMessageId = 1)
        val cache = object : OfflineWarmup() {
            override suspend fun chatsWindow(limit: Int, archiveLimit: Int) = listOf(row)
            override suspend fun mainListCount() = 1
        }

        @Suppress("UNCHECKED_CAST")
        val client = Proxy.newProxyInstance(
            MtprotoClient::class.java.classLoader,
            arrayOf(MtprotoClient::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString", "libraryVersion" -> "SyntheticCatchupClient"
                "updates" -> events
                "sessionLost" -> emptyFlow<Unit>()
                "proxyConnectionStatus" -> ProxyConnectionStatus.Disabled
                "getChats" -> {
                    val continuation = args!!.last() as Continuation<Any?>
                    scope.launch { delay(300); continuation.resume(Outcome.Ok(listOf(row))) }
                    COROUTINE_SUSPENDED
                }

                "loadMoreChats", "loadMoreFolderChats", "getFolders" -> Outcome.Ok(emptyList<Chat>())
                "getNotifySettings" -> Outcome.Ok(NotifySettings())
                "close" -> Unit
                else -> Outcome.Err("unsupported fixture method")
            }
        } as MtprotoClient
        lateinit var component: ChatsComponent
        compose.runOnUiThread {
            lifecycle.resume()
            component = ChatsComponent(
                DefaultComponentContext(lifecycle), DefaultStoreFactory(), client, cache,
                sessionStore = null, onOpenChat = { _, _, _ -> })
        }
        compose.setContent { MonogramTheme(dynamicColor = false) { ChatsContent(component) } }
        try {
            compose.onNodeWithText("Synthetic startup chat").assertIsDisplayed()
            compose.waitUntil(15000) { !component.state.value.loading && !component.state.value.fromCache }
            compose.onNodeWithText("Updating").assertIsDisplayed()
            compose.runOnUiThread { events.tryEmit(MtprotoUpdate.SyncState(false)) }
            compose.waitUntil(10000) { !component.state.value.syncing }
            compose.onNodeWithText("Updating").assertDoesNotExist()
            compose.runOnUiThread { events.tryEmit(MtprotoUpdate.SyncState(true)); component.onRefresh() }
            compose.waitUntil(10000) { component.state.value.syncing && !component.state.value.loading }
            compose.onNodeWithText("Updating").assertIsDisplayed()
            compose.runOnUiThread { events.tryEmit(MtprotoUpdate.SyncState(false)) }
            compose.waitUntil(10000) { !component.state.value.syncing }
            compose.onNodeWithText("Synthetic startup chat").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { lifecycle.destroy(); scope.cancel() }
        }
    }
}
