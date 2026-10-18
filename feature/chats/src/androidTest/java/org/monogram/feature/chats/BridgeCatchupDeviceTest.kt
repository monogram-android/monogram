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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.monogram.core.common.TelegramCredentials
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.Chat
import org.monogram.core.models.PeerId
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.chats.ui.ChatsContent
import org.monogram.mtproto.MtprotoNative
import org.monogram.network.bridge.BridgedMtprotoClient
import uniffi.monogram_mtproto.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BridgeCatchupDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class Native : MtprotoNative by MtprotoNative.Stub {
        val connects = AtomicInteger()
        val failNext = AtomicBoolean()
        val nextId = AtomicInteger(1)
        val acknowledged = AtomicInteger()
        private val pending = mutableListOf<UpdateBatchDto>()
        override fun createClient(apiId: Int, apiHash: String, sessionPath: String) = 1L
        override fun connect(handle: Long) {
            connects.incrementAndGet()
        }

        override fun isAuthorized(handle: Long) = true
        override fun startUpdates(handle: Long) = Unit
        override fun getChats(handle: Long) = listOf(chat())
        @Synchronized
        fun armGap() {
            nextId.set(100); failNext.set(true)
        }

        @Synchronized
        override fun pendingUpdateBatches(handle: Long) = pending.toList()
        @Synchronized
        override fun acknowledgeUpdateBatch(handle: Long, accountGeneration: Long, batchId: Long) {
            check(pending.removeAll { it.batchId == batchId })
            acknowledged.incrementAndGet()
        }

        @Synchronized
        override fun drainUpdateBatches(handle: Long): UpdateDrainDto {
            if (failNext.getAndSet(false)) throw MtprotoException.Message("connection closed")
            val id = nextId.getAndIncrement()
            if (id > 2 && id != 100) return UpdateDrainDto(emptyList(), emptyList(), false)
            val batch = UpdateBatchDto(
                7,
                id.toLong(),
                listOf(UpdateEventDto.ReadInbox(600001, id, 0)),
                UpdatesStateDto(id, 0, id, id),
                emptyList()
            )
            pending += batch
            return UpdateDrainDto(listOf(batch), listOf(UpdateEventDto.SyncState(id == 1)), false)
        }

        companion object {
            fun chat() = ChatDto(
                id = 600001,
                title = "Synthetic bridge startup",
                isChannel = false,
                isGroup = false,
                isForum = false,
                left = false,
                unreadCount = 0,
                lastMessagePreview = "Synthetic preview",
                lastMessageDate = 1,
                archived = false,
                muted = false,
                isContact = false,
                isBot = false,
                isVerified = false,
                photoCacheKey = null,
                pinned = false,
                readInboxMaxId = 0,
                readOutboxMaxId = 0,
                peerStatus = null,
                peerStatusAt = null,
                lastMediaThumbCacheKey = null,
                lastMessageId = 1,
                canView = true,
                canSendPlain = true,
                canSendPhotos = true,
                canForward = true,
                canDeleteOthers = false,
                emojiStatusDocumentId = null,
                lastMessageOutgoing = false,
                muteOverride = false,
                unreadMark = false,
                unreadMentionsCount = 0,
                unreadReactionsCount = 0,
                canManageTopics = false,
            )
        }
    }

    @Test
    fun coldStartupAndReconnectExerciseProductionBridge() = scenario(false)
    @Test
    fun warmStartupAndReconnectExerciseProductionBridge() = scenario(true)

    private fun scenario(warm: Boolean) {
        val baselineTrace = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("baselineTrace") == "true"
        val native = Native()
        val permit = AtomicBoolean(false)
        val attempts = AtomicInteger()
        val lifecycle = LifecycleRegistry()
        val client = BridgedMtprotoClient(
            TelegramCredentials(1, "synthetic"),
            "unused-synthetic.session",
            native,
            refreshDcSidecar = {})
        client.installDurableUpdateSink { batch ->
            check(batch.accountGeneration == 7L && batch.cursor?.pts == batch.batchId.toInt())
            if (batch.batchId == 1L) true else {
                attempts.incrementAndGet(); permit.get()
            }
        }
        val cache = object : OfflineWarmup() {
            override suspend fun chatsWindow(limit: Int, archiveLimit: Int) =
                if (warm) listOf(Chat(PeerId(600001), "Synthetic bridge startup")) else emptyList()

            override suspend fun mainListCount() = if (warm) 1 else 0
        }
        lateinit var component: ChatsComponent
        compose.runOnUiThread {
            lifecycle.resume()
            component = ChatsComponent(
                DefaultComponentContext(lifecycle),
                DefaultStoreFactory(),
                client,
                cache,
                null,
                onOpenChat = { _, _, _ -> })
        }
        compose.setContent { MonogramTheme(dynamicColor = false) { ChatsContent(component) } }
        try {
            compose.waitUntil(15000) { attempts.get() >= 2 && !component.state.value.loading }
            compose.onNodeWithText("Synthetic bridge startup").assertIsDisplayed()
            android.util.Log.i(
                "SyntheticBridgeTrace",
                "phase=startupBlocked warm=$warm baseline=$baselineTrace sync=${component.state.value.syncing} acks=${native.acknowledged.get()} rows=${component.state.value.chats.size}"
            )
            if (!baselineTrace) compose.onNodeWithText("Updating").assertIsDisplayed()
            assertEquals(1, native.acknowledged.get())
            permit.set(true)
            compose.waitUntil(10000) { !component.state.value.syncing && native.acknowledged.get() == 2 }
            permit.set(false)
            val prior = attempts.get()
            native.armGap()
            compose.waitUntil(15000) { native.connects.get() >= 2 && attempts.get() > prior }
            android.util.Log.i(
                "SyntheticBridgeTrace",
                "phase=reconnectBlocked warm=$warm baseline=$baselineTrace sync=${component.state.value.syncing} acks=${native.acknowledged.get()} rows=${component.state.value.chats.size}"
            )
            if (!baselineTrace) compose.onNodeWithText("Updating").assertIsDisplayed()
            assertEquals(2, native.acknowledged.get())
            permit.set(true)
            compose.waitUntil(10000) { !component.state.value.syncing && native.acknowledged.get() == 3 }
            compose.onNodeWithText("Synthetic bridge startup").assertIsDisplayed()
            android.util.Log.i(
                "SyntheticBridgeTrace",
                "warm=$warm connects=${native.connects.get()} commits=${native.acknowledged.get()} failedAttempts=${attempts.get()} finalSync=${component.state.value.syncing}"
            )
        } finally {
            permit.set(true)
            compose.runOnUiThread { lifecycle.destroy(); client.close() }
        }
    }
}
