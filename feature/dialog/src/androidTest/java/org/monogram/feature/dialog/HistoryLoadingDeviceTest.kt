package org.monogram.feature.dialog

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
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

    @Test
    fun attachmentOnlyComposerOffersSilentSendAndRetainsOutboxStatus() {
        val context =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(
            context.noBackupFilesDir,
            "media-composer-fixture-${java.util.UUID.randomUUID()}"
        ).apply { mkdirs() }
        val photo = java.io.File(directory, "fixture-photo.png")
        android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
            .let { bitmap ->
                photo.outputStream()
                    .use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        val cache = OfflineWarmup(
            org.monogram.core.database.DatabaseProvider.get(context),
            outgoingMediaFiles = org.monogram.core.database.OutgoingMediaFiles(
                java.io.File(
                    directory,
                    "owned"
                )
            )
        )
        try {
            scenario(cached = false, unread = false, draftCache = cache) { component ->
                compose.runOnUiThread {
                    component.onAttachMedia(
                        listOf(
                            UploadItem(
                                photo.absolutePath,
                                "photo",
                                fileName = photo.name
                            )
                        )
                    )
                }
                compose.waitUntil(5_000) { component.state.value.pendingAttach.size == 1 }
                compose.onNodeWithContentDescription(context.getString(R.string.dialog_send))
                    .performSemanticsAction(
                        androidx.compose.ui.semantics.SemanticsActions.OnLongClick
                    ) { it() }
                compose.onNodeWithText(context.getString(R.string.dialog_send_silent))
                    .assertIsDisplayed().performClick()
                compose.waitUntil(5_000) { component.state.value.outgoingMediaSends.size == 1 && component.state.value.pendingAttach.isEmpty() }
                compose.runOnIdle {
                    assertTrue(component.state.value.outgoingMediaSends.single().batch.silent)
                    assertFalse(component.state.value.outgoingMediaSends.single().batch.album)
                    assertTrue(component.state.value.messages.none { it.pending })
                }
                compose.onNodeWithText(
                    context.getString(R.string.dialog_outbox_ready),
                    substring = true
                ).assertIsDisplayed()
                val proof = java.io.File(
                    context.getExternalFilesDir(null),
                    "media-composer-silent-proof.png"
                )
                proof.outputStream().use {
                    compose.onRoot().captureToImage().asAndroidBitmap()
                        .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                val output = androidx.test.platform.app.InstrumentationRegistry.getArguments()
                    .getString("additionalTestOutputDir")
                if (output != null) {
                    java.io.File(output).mkdirs()
                    proof.copyTo(java.io.File(output, proof.name), overwrite = true)
                }
            }
        } finally {
            runBlocking { cache.clearAccountCache() }; directory.deleteRecursively()
        }
    }

    private fun scenario(
        cached: Boolean,
        unread: Boolean,
        draftCache: OfflineWarmup? = null,
        body: (DialogComponent) -> Unit,
    ) {
        val lifecycle = LifecycleRegistry()
        val frames = mutableListOf<Long>()
        val frameListener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames += metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION)
        }
        lateinit var component: DialogComponent
        val cache = draftCache ?: object : OfflineWarmup() {
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
    fun restoredDraftTypingReplyAndSilentSyncRecoveryRemainConsistent() {
        val drafts = MutableStateFlow(
            CloudDraftState(
                CloudDraft(
                    peer,
                    text = "Draft fixture",
                    entitiesJson = TextEntities.serialize(listOf(TextEntity("bold", 0, 5))),
                    replyToMessageId = 17,
                    date = 100,
                    noWebpage = true,
                )
            )
        )
        var writeGate: CompletableDeferred<Unit>? = null
        val recovered = CompletableDeferred<Unit>()
        val retried = java.util.concurrent.atomic.AtomicInteger()
        val cache = object : OfflineWarmup() {
            override suspend fun chat(chatId: PeerId) =
                Chat(peer, "Synthetic draft", lastMessageId = 240, readInboxMaxId = 240)

            override fun observeCloudDraft(chatId: PeerId, threadId: Int) = drafts
            override suspend fun cloudDraftState(chatId: PeerId, threadId: Int) = drafts.value
            override suspend fun saveLocalCloudDraft(
                draft: CloudDraft,
                markdown: Boolean?
            ): CloudDraftState {
                writeGate?.await()
                return drafts.value.localEdit(draft, markdown = markdown).also { drafts.value = it }
            }
        }
        try {
            scenario(false, false, cache) { component ->
                compose.waitUntil(15_000) { component.state.value.draft == "Draft fixture" && !component.state.value.loading }
                compose.onNode(hasSetTextAction() and hasText("Draft fixture")).assertIsDisplayed()
                assertEquals(listOf(TextEntity("bold", 0, 5)), component.state.value.draftEntities)
                assertEquals(17, component.state.value.draftReplyId)
                val pending = CompletableDeferred<Unit>()
                compose.runOnUiThread { writeGate = pending }
                compose.onNode(hasSetTextAction()).performTextReplacement("Edited fixture")
                compose.waitUntil { component.state.value.draft == "Edited fixture" }
                compose.runOnUiThread {
                    drafts.value = drafts.value.mergeRemote(
                        CloudDraft(
                            peer,
                            text = "Concurrent remote",
                            date = 200
                        )
                    )
                }
                compose.onNode(hasSetTextAction() and hasText("Edited fixture")).assertIsDisplayed()
                pending.complete(Unit)
                compose.waitUntil { drafts.value.draft.text == "Edited fixture" }
                assertEquals(17, drafts.value.draft.replyToMessageId)
                assertTrue(drafts.value.draft.noWebpage)
                val cancel = compose.activity.getString(R.string.dialog_cancel)
                compose.onNodeWithContentDescription(cancel).performClick()
                compose.waitUntil { drafts.value.draft.replyToMessageId == 0 }
                compose.runOnUiThread {
                    scope.launch {
                        cache.draftSync.retries.collect {
                            retried.incrementAndGet()
                            val token = cache.draftSync.begin(peer to 0)
                            recovered.await()
                            drafts.value = drafts.value.acknowledge(drafts.value.revision)
                            cache.draftSync.finish(peer to 0, token)
                        }
                    }
                    val token = cache.draftSync.begin(peer to 0)
                    cache.draftSync.finish(
                        peer to 0, token,
                        org.monogram.core.common.telegram.TelegramError.parse("NETWORK_ERROR")
                    )
                }
                val retry = compose.activity.getString(org.monogram.core.ui.R.string.status_retry)
                compose.waitUntil { component.state.value.draftSync.error != null }
                compose.onNodeWithTag("draft-sync").assertDoesNotExist()
                compose.onNodeWithText(retry).assertDoesNotExist()
                compose.onNodeWithText(compose.activity.getString(org.monogram.core.ui.R.string.status_network))
                    .assertDoesNotExist()
                compose.runOnUiThread { cache.draftSync.requestRetry() }
                compose.waitUntil { retried.get() == 1 && component.state.value.draftSync.syncing }
                compose.onNodeWithTag("draft-sync").assertDoesNotExist()
                compose.onNodeWithText(compose.activity.getString(org.monogram.core.ui.R.string.status_syncing))
                    .assertDoesNotExist()
                compose.onNodeWithText(retry).assertDoesNotExist()
                assertEquals("Edited fixture", component.state.value.draft)
                recovered.complete(Unit)
                compose.waitUntil { !component.state.value.draftSync.syncing && component.state.value.draftSync.error == null }
                compose.runOnUiThread {
                    drafts.value = drafts.value.mergeRemote(
                        CloudDraft(
                            peer,
                            text = "Remote after retry",
                            date = 300
                        )
                    )
                }
                compose.waitUntil { component.state.value.draft == "Remote after retry" }
                compose.onNode(hasSetTextAction() and hasText("Remote after retry"))
                    .assertIsDisplayed()
            }
        } finally {
            writeGate?.complete(Unit)
            recovered.complete(Unit)
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
