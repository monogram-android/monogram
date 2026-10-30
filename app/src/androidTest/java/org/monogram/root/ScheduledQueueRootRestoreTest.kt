package org.monogram.root

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.monogram.UiStateTestActivity
import org.monogram.core.common.Outcome
import org.monogram.core.models.*
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.core.database.*
import org.monogram.core.database.entity.*
import kotlinx.coroutines.runBlocking
import androidx.room.Room
import java.io.File
import org.monogram.network.bridge.MtprotoClient

class ScheduledQueueRootRestoreTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<UiStateTestActivity>? = null
    private lateinit var root: RootComponent
    private val mutations = AtomicInteger()
    private val reads = AtomicInteger()
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val scheduled = ScheduledMessage(
        Message(
            MessageId(PeerId(7), 37), null,
            "Synthetic queued caption", System.currentTimeMillis() / 1000 + 3600, outgoing = true
        ), false
    )

    @After
    fun close() {
        scenario?.close(); UiStateTestActivity.content = {}
    }

    @Test
    fun rootQueueAndUnsubmittedEditorSurviveActivityRecreationWithoutMutation() =
        checkRestoration(false)

    @Test
    fun russianDarkLargeTextQueueAndEditorSurviveActivityRecreationWithoutMutation() =
        checkRestoration(true)

    private fun launch(
        client: MtprotoClient,
        warmup: OfflineWarmup? = null,
        russianDarkLargeText: Boolean = false
    ): android.content.Context {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val context = if (russianDarkLargeText) targetContext.createConfigurationContext(
            android.content.res.Configuration(targetContext.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag("ru")); fontScale = 1.5f
            }) else targetContext
        UiStateTestActivity.content = { componentContext ->
            val component = remember(componentContext) {
                RootComponent(
                    componentContext,
                    DefaultStoreFactory(),
                    client,
                    warmup,
                    null,
                    null,
                    startOnHome = true
                )
            }
            root = component
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalResources provides context.resources,
                LocalConfiguration provides context.resources.configuration,
                LocalDensity provides Density(
                    density.density,
                    if (russianDarkLargeText) 1.5f else density.fontScale
                )
            ) {
                MonogramTheme(darkTheme = russianDarkLargeText, dynamicColor = false) {
                    RootContent(component, Modifier.fillMaxSize())
                }
            }
        }
        scenario = ActivityScenario.launch(UiStateTestActivity::class.java)
        try {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Synthetic queue chat").fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError(
                "Root=${root.stack.value.active.configuration}; calls=$calls\n${
                    compose.onRoot().printToString()
                }", error
            )
        }
        return context
    }

    private fun openQueue(): RootComponent.Config.Dialog {
        compose.onNodeWithText("Synthetic queue chat").performClick()
        lateinit var dialog: RootComponent.Config.Dialog
        compose.runOnIdle {
            dialog = root.stack.value.active.configuration as RootComponent.Config.Dialog
            assertEquals(7L, dialog.chatId)
            (root.stack.value.active.instance as RootComponent.Child.Dialog).component.onOpenScheduledQueue()
        }
        return dialog
    }

    private fun checkRestoration(russianDarkLargeText: Boolean) {
        val context = launch(client(), russianDarkLargeText = russianDarkLargeText)
        val dialog = openQueue()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(scheduled.message.text!!).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(context.getString(org.monogram.feature.dialog.R.string.dialog_scheduled_actions))
            .performClick()
        compose.onNodeWithText(context.getString(org.monogram.feature.dialog.R.string.dialog_edit))
            .performClick()
        val editor = hasSetTextAction() and hasText(scheduled.message.text!!)
        compose.onNode(editor).performTextReplacement("Local unsent caption after recreation")
        val readsBefore = reads.get()
        scenario!!.recreate()
        compose.onNode(hasSetTextAction() and hasText("Local unsent caption after recreation"))
            .assertTextContains("Local unsent caption after recreation")
        compose.runOnIdle {
            assertEquals(
                RootComponent.Config.ScheduledQueue(7),
                root.stack.value.active.configuration
            )
            assertEquals(dialog, root.stack.value.backStack.last().configuration)
            assertEquals(0, mutations.get())
            assertTrue(reads.get() >= readsBefore)
        }
        if (russianDarkLargeText) {
            compose.waitForIdle()
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            try {
                java.io.File(
                    context.getExternalFilesDir(null),
                    "scheduled-queue-root-russian-large-proof.png"
                )
                    .outputStream()
                    .use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                image.recycle()
            }
        }
        compose.onNodeWithContentDescription(context.getString(org.monogram.feature.dialog.R.string.dialog_markdown_close))
            .performClick()
        compose.onNodeWithText(scheduled.message.text!!).assertIsDisplayed()
        compose.runOnIdle {
            (root.stack.value.active.instance as RootComponent.Child.ScheduledQueue).component.onBack()
            assertEquals(dialog, root.stack.value.active.configuration)
            assertEquals(0, mutations.get())
        }
    }

    @Test
    fun queueEditorKeepsDraftAndControlsAboveKeyboardAcrossRotation() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }
        org.junit.Assume.assumeTrue(
            "An installed input method is required",
            shell("ime list -a -s").isNotBlank()
        )
        val oldKeyboard = shell("settings get secure show_ime_with_hard_keyboard")
        val oldRotation = shell("settings get system user_rotation")
        val oldAccelerometer = shell("settings get system accelerometer_rotation")
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            assertTrue(automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0))
            val context = launch(client())
            openQueue()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(scheduled.message.text!!).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithContentDescription(context.getString(org.monogram.feature.dialog.R.string.dialog_scheduled_actions))
                .performClick()
            compose.onNodeWithText(context.getString(org.monogram.feature.dialog.R.string.dialog_edit))
                .performClick()
            val draft = "Keyboard and rotation keep this unsent draft"
            compose.onNode(hasSetTextAction() and hasText(scheduled.message.text!!))
                .performTextReplacement(draft)
            fun verifyKeyboard() {
                compose.onNode(hasSetTextAction() and hasText(draft)).performClick()
                    .assertIsFocused()
                scenario!!.onActivity { activity ->
                    val manager =
                        activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    manager.showSoftInput(
                        activity.currentFocus,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT
                    )
                }
                try {
                    compose.waitUntil(5_000) {
                        var visible = false
                        scenario!!.onActivity { activity ->
                            visible =
                                androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                        }
                        visible
                    }
                } catch (error: ComposeTimeoutException) {
                    var window = ""
                    scenario!!.onActivity {
                        window =
                            "focus=${it.hasWindowFocus()} display=${it.window.decorView.display?.displayId} current=${it.currentFocus?.javaClass?.simpleName}"
                    }
                    val state = shell("dumpsys input_method").lineSequence().filter {
                        it.contains("mCurMethodId=") || it.contains("mShowRequested=") || it.contains(
                            "mInputShown="
                        ) ||
                                it.contains("mCurFocusedWindow=") || it.contains("mInteractive=")
                    }.joinToString("\n")
                    throw AssertionError("$window\n$state", error)
                }
                var keyboardTop = 0f
                var density = 1f
                scenario!!.onActivity { activity ->
                    val view = activity.window.decorView
                    val insets =
                        requireNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(view))
                    keyboardTop =
                        (view.height - insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom).toFloat()
                    density = activity.resources.displayMetrics.density
                }
                val apply =
                    compose.onNode(hasText(context.getString(org.monogram.feature.dialog.R.string.dialog_markdown_apply)) and hasClickAction())
                apply.assertIsDisplayed()
                assertTrue(
                    "Apply must remain above the keyboard",
                    apply.getBoundsInRoot().bottom.value * density <= keyboardTop
                )
                val field = compose.onNode(hasSetTextAction() and hasText(draft))
                field.assertTextContains(draft)
                assertTrue(
                    "Editor must remain above the keyboard",
                    field.getBoundsInRoot().bottom.value * density <= keyboardTop
                )
                compose.runOnIdle {
                    assertEquals(
                        RootComponent.Config.ScheduledQueue(7),
                        root.stack.value.active.configuration
                    )
                    assertEquals(0, mutations.get())
                }
            }
            verifyKeyboard()
            assertTrue(automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90))
            compose.waitUntil(10_000) {
                var landscape = false
                scenario!!.onActivity {
                    landscape =
                        it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                }
                landscape
            }
            verifyKeyboard()
        } finally {
            automation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
            for ((table, key, value) in listOf(
                Triple("secure", "show_ime_with_hard_keyboard", oldKeyboard),
                Triple("system", "user_rotation", oldRotation),
                Triple("system", "accelerometer_rotation", oldAccelerometer)
            )) {
                shell(if (value == "null") "settings delete $table $key" else "settings put $table $key $value")
            }
        }
    }

    @Test
    fun retainedAlbumSettlesIntoPersistentQueueAndLostCancelCallbackRefreshesWithoutReplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "scheduled-album-lifecycle.db"
        val directory = File(context.noBackupFilesDir, "scheduled-album-lifecycle")
        context.deleteDatabase(name)
        directory.deleteRecursively(); directory.mkdirs()
        fun database() = Room.databaseBuilder(context, MonogramDatabase::class.java, name).build()
        var db = database()
        val files = OutgoingMediaFiles(File(directory, "owned"))
        var cache = OfflineWarmup(db, outgoingMediaFiles = files)
        try {
            val send = runBlocking {
                val original =
                    File(directory, "synthetic-source").apply { writeText("synthetic-album-bytes") }
                val uploads = (0..1).map { index ->
                    OutgoingMediaUpload(
                        original.absolutePath, "application/octet-stream", MessageEntity(
                            chatId = 7,
                            id = -37 - index,
                            senderId = 99,
                            text = "Album caption $index",
                            date = 100,
                            outgoing = true,
                            pending = true,
                            randomId = 123L + index,
                            groupedId = 777,
                            mediaKind = "document",
                            mediaCacheKey = null,
                            fileName = "fixture-$index.bin",
                            entitiesJson = "[]"
                        )
                    )
                }
                val admitted = cache.retainAndEnqueueOutgoingMedia(
                    OutgoingMediaBatchEntity(
                        123,
                        7,
                        100,
                        true,
                        scheduled.message.date.toInt(),
                        true
                    ),
                    uploads, 128, cache.outgoingAccountEpoch()
                ).send
                assertTrue(original.delete())
                var attempts = 0
                val worker = OutgoingTextSync(
                    cache,
                    { error("unexpected text send") },
                    sendMedia = { batch ->
                        attempts++
                        assertTrue(batch.batch.silent && batch.batch.album)
                        assertEquals(scheduled.message.date.toInt(), batch.batch.scheduleDate)
                        assertEquals(listOf(123L, 124L), batch.items.map { it.randomId })
                        batch.items.forEach {
                            assertEquals(
                                "synthetic-album-bytes",
                                files.verifiedFile(it.source).readText()
                            )
                        }
                        Outcome.Ok(Unit)
                    })
                worker.syncOnce(); worker.stop()
                assertEquals(1, attempts)
                assertEquals(
                    OutgoingSendPhase.IN_FLIGHT,
                    db.outgoingMediaSendDao().get(123)!!.batch.phase
                )
                admitted
            }
            db.close(); db = database(); cache = OfflineWarmup(db, outgoingMediaFiles = files)
            val remote = java.util.concurrent.CopyOnWriteArrayList<ScheduledMessage>()
            runBlocking {
                assertEquals(
                    OutgoingSendPhase.IN_FLIGHT,
                    db.outgoingMediaSendDao().get(123)!!.batch.phase
                )
                send.items.forEach { files.verifiedFile(it.source) }
                val results = send.items.mapIndexed { index, item ->
                    val message = ScheduledMessage(
                        item.message.copy(
                            id = 37 + index,
                            pending = false,
                            date = scheduled.message.date,
                            mediaCacheKey = "synthetic-remote-${37 + index}"
                        ).toModel(), false
                    )
                    remote += message
                    item.randomId to OutgoingMessageResult.Scheduled(message)
                }
                assertTrue(cache.applyDurableBatch(5, 1, null, emptyMap()) {
                    cache.applyOutgoingMediaResults(
                        123,
                        results,
                        cache.outgoingAccountEpoch(),
                        5,
                        1
                    )
                })
                files.release(send.items.map { it.source })
                assertNull(db.outgoingMediaSendDao().get(123))
                assertEquals(
                    listOf(37, 38),
                    db.scheduledMessageDao().forChat(7).map { it.message.id })
                assertTrue(db.messageDao().byIds(7, listOf(37, 38)).isEmpty())
                assertTrue(File(directory, "owned").listFiles().orEmpty().isEmpty())
            }
            db.close(); db = database(); cache = OfflineWarmup(db, outgoingMediaFiles = files)
            runBlocking { assertEquals(2, db.scheduledMessageDao().forChat(7).size) }
            val uiContext = launch(client(history = { remote.toList() }, delete = { ids ->
                assertEquals(listOf(37), ids)
                remote.removeAll { it.message.id.id in ids }
                Outcome.Err("RPC 500: INTERNAL")
            }), cache)
            val queueIcon =
                uiContext.getString(org.monogram.feature.dialog.R.string.dialog_scheduled_messages)
            compose.onNodeWithText("Synthetic queue chat").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription(queueIcon).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithContentDescription(queueIcon).performClick()
            compose.waitUntil(5_000) {
                val state =
                    (root.stack.value.active.instance as RootComponent.Child.ScheduledQueue).component.state.value
                !state.loading && !state.fromCache && !state.needsRefresh && state.messages.size == 2
            }
            val cancel =
                uiContext.getString(org.monogram.feature.dialog.R.string.dialog_scheduled_cancel)
            compose.onAllNodesWithContentDescription(uiContext.getString(org.monogram.feature.dialog.R.string.dialog_scheduled_actions))
                .onFirst().performClick()
            compose.onNodeWithText(cancel).performClick()
            compose.runOnIdle { assertEquals(0, mutations.get()) }
            compose.onNode(hasText(cancel) and hasClickAction() and hasAnyAncestor(isDialog()))
                .performClick()
            compose.waitUntil(5_000) {
                val state =
                    (root.stack.value.active.instance as RootComponent.Child.ScheduledQueue).component.state.value
                state.needsRefresh && state.mutatingId == null
            }
            compose.runOnIdle { assertEquals(1, mutations.get()) }
            compose.onNodeWithContentDescription(uiContext.getString(org.monogram.feature.dialog.R.string.dialog_refresh))
                .performClick()
            compose.waitUntil(5_000) {
                val state =
                    (root.stack.value.active.instance as RootComponent.Child.ScheduledQueue).component.state.value
                !state.loading && !state.needsRefresh && state.messages.map { it.message.id.id } == listOf(
                    38
                )
            }
            compose.onNodeWithText("Album caption 1").assertIsDisplayed()
            compose.onNodeWithText("Album caption 0").assertDoesNotExist()
            runBlocking {
                assertEquals(
                    listOf(38),
                    db.scheduledMessageDao().forChat(7).map { it.message.id })
            }
            compose.runOnIdle { assertEquals(1, mutations.get()) }
            remote.clear()
            compose.runOnIdle { (root.stack.value.active.instance as RootComponent.Child.ScheduledQueue).component.onRefresh() }
            compose.waitUntil(5_000) {
                val state =
                    (root.stack.value.active.instance as RootComponent.Child.ScheduledQueue).component.state.value
                !state.loading && state.messages.isEmpty()
            }
            compose.runOnIdle { (root.stack.value.active.instance as RootComponent.Child.ScheduledQueue).component.onBack() }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(queueIcon).assertDoesNotExist()
        } finally {
            scenario?.close(); scenario = null
            db.close(); context.deleteDatabase(name); directory.deleteRecursively()
        }
    }

    private fun client(
        history: () -> List<ScheduledMessage> = { listOf(scheduled) },
        delete: ((List<Int>) -> Outcome<Unit>)? = null
    ) = Proxy.newProxyInstance(
        MtprotoClient::class.java.classLoader,
        arrayOf(MtprotoClient::class.java)
    ) { proxy, method, args ->
        calls += method.name
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString", "libraryVersion" -> "SyntheticQueueRestoreClient"
            "proxyConnectionStatus" -> org.monogram.network.bridge.session.ProxyConnectionStatus.Disabled
            "updates", "sessionLost" -> emptyFlow<Nothing>()
            "getChats" -> Outcome.Ok(
                listOf(
                    Chat(
                        PeerId(7),
                        "Synthetic queue chat",
                        lastMessageDate = 1000L
                    )
                )
            )

            "getFolders" -> Outcome.Ok(listOf(Folder(2, "Work", chatIds = listOf(PeerId(7)))))
            "getHistory", "getHistoryPage", "getHistoryPageBackground", "getPinnedMessages", "getSavedGifs" -> Outcome.Ok(
                emptyList<Message>()
            )

            "getProfile" -> {
                val peer = args?.firstOrNull() as? PeerId ?: PeerId(99)
                Outcome.Ok(
                    Profile(
                        peer,
                        "fixture",
                        if (peer == PeerId(7)) "Synthetic queue chat" else "Synthetic self"
                    )
                )
            }

            "getGroupAdminTags" -> Outcome.Ok(emptyMap<PeerId, String>())
            "getScheduledHistory", "getScheduledMessages" -> {
                reads.incrementAndGet(); Outcome.Ok(history())
            }

            "deleteScheduledMessages" -> {
                mutations.incrementAndGet()
                @Suppress("UNCHECKED_CAST")
                val ids = args!![1] as List<Int>
                delete?.invoke(ids) ?: Outcome.Err("unexpected mutation during recreation")
            }

            "sendScheduledMessages", "editScheduledMessage" -> {
                mutations.incrementAndGet(); Outcome.Err("unexpected mutation during recreation")
            }

            "setDialogForeground", "close", "setForeground" -> Unit
            "updateStatus", "setTyping", "readHistory", "logout" -> Outcome.Ok(Unit)
            else -> Outcome.Err("unsupported synthetic queue method")
        }
    } as MtprotoClient
}
