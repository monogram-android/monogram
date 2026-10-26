package org.monogram.root

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.monogram.UiStateTestActivity
import org.monogram.core.common.Outcome
import org.monogram.core.database.*
import org.monogram.core.models.*
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import java.lang.reflect.Proxy
import java.util.UUID

class StructuredCreationRootTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun attachmentFormsAdmitRealRowsAndTypedSenderSettlesAuthoritativePollAndChecklistWithoutErasingDraft() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "structured-root-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, MonogramDatabase::class.java, name).build()
        val cache = OfflineWarmup(db)
        val events = MutableSharedFlow<MtprotoUpdate>(replay = 8)
        val delivered = java.util.concurrent.CopyOnWriteArrayList<Message>()
        val identities = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val client = Proxy.newProxyInstance(
            MtprotoClient::class.java.classLoader,
            arrayOf(MtprotoClient::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString", "libraryVersion" -> "SyntheticStructuredClient"
                "proxyConnectionStatus" -> org.monogram.network.bridge.session.ProxyConnectionStatus.Disabled
                "updates" -> events
                "sessionLost" -> emptyFlow<Nothing>()
                "getChats" -> Outcome.Ok(
                    listOf(
                        Chat(
                            PeerId(7),
                            "Fixture chat",
                            isBot = true,
                            canSendPolls = true
                        )
                    )
                )

                "getFolders" -> Outcome.Ok(emptyList<Folder>())
                "getHistory", "getHistoryPage", "getHistoryPageBackground" -> Outcome.Ok(delivered.toList())
                "getPinnedMessages", "getSavedGifs" -> Outcome.Ok(emptyList<Message>())
                "getProfile" -> Outcome.Ok(Profile(PeerId(7), "fixture", "Fixture chat"))
                "getGroupAdminTags" -> Outcome.Ok(emptyMap<PeerId, String>())
                "sendPollWithOptions", "sendChecklistWithOptions" -> {
                    val id = args!![2] as Long
                    identities += id
                    val row = runBlocking { requireNotNull(db.outgoingTextSendDao().get(id)) }
                    assertEquals(
                        org.monogram.core.database.entity.OutgoingSendPhase.IN_FLIGHT,
                        row.phase
                    )
                    assertTrue(id != 0L)
                    val options = args[3] as MessageSendOptions
                    assertTrue(options.silent)
                    val pending = row.message.toModel()
                    val message = if (method.name == "sendPollWithOptions") {
                        val spec = args[1] as PollSendSpec
                        assertEquals("Synthetic poll", spec.question)
                        assertEquals(listOf("First", "Second"), spec.answers)
                        pending.copy(
                            id = MessageId(PeerId(7), 37), pending = false, text = null,
                            fileName = """{"id":"987","q":"Synthetic poll","a":[{"t":"First","o":"30"},{"t":"Second","o":"31"}]}"""
                        )
                    } else {
                        val spec = args[1] as ChecklistSendSpec
                        assertEquals(
                            ChecklistSendSpec(
                                "Synthetic list",
                                listOf("Fixture task"),
                                othersCanAppend = true
                            ), spec
                        )
                        pending.copy(id = MessageId(PeerId(7), 38), pending = false, text = null)
                    }
                    val result = OutgoingMessageResult.Sent(message)
                    delivered += message
                    runBlocking {
                        cache.applyOutgoingResult(id, result)
                        events.emit(MtprotoUpdate.OutgoingMessageAcknowledged(id, result))
                    }
                    Outcome.Ok(result)
                }

                "sendTextWithOptions", "sendText" -> throw AssertionError("creation must use typed media send")
                "setDialogForeground", "close", "setForeground" -> Unit
                "updateStatus", "setTyping", "readHistory" -> Outcome.Ok(Unit)
                else -> Outcome.Err("unsupported synthetic structured method")
            }
        } as MtprotoClient
        var rootComponent: RootComponent? = null
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
                rootComponent = root
                MonogramTheme(dynamicColor = false) { RootContent(root, Modifier.fillMaxSize()) }
            }
            scenario = ActivityScenario.launch(UiStateTestActivity::class.java)
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasSetTextAction()).performTextInput("Keep draft")
            fun text(id: Int) = context.getString(id)
            fun fill(label: String, value: String) {
                compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo()
                    .performTextInput(value)
            }

            fun rights(plain: Boolean, photos: Boolean, polls: Boolean) = runBlocking {
                val chat = Chat(
                    PeerId(7),
                    "Fixture chat",
                    isBot = polls,
                    canSendPlain = plain,
                    canSendPhotos = photos,
                    canSendPolls = polls
                )
                cache.upsertChats(listOf(chat)); events.emit(MtprotoUpdate.ChatsChanged(listOf(chat)))
            }

            val worker = OutgoingTextSync(cache, { sendRetainedMessage(client, it) })
            for (kind in 0..1) {
                val checklist = kind == 1
                if (kind == 0) {
                    rights(false, false, true)
                    compose.waitUntil(5_000) {
                        compose.onAllNodes(hasText("Keep draft") and !isEnabled())
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                }
                compose.onNodeWithContentDescription(text(org.monogram.feature.dialog.R.string.dialog_attach))
                    .performClick()
                val create =
                    text(if (checklist) org.monogram.feature.dialog.R.string.dialog_checklist_create else org.monogram.feature.dialog.R.string.dialog_poll_create)
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithContentDescription(create).fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithContentDescription(create).performScrollTo().performClick()
                if (checklist) {
                    fill(
                        text(org.monogram.feature.dialog.R.string.dialog_checklist_title),
                        "Synthetic list"
                    )
                    fill(
                        context.getString(
                            org.monogram.feature.dialog.R.string.dialog_checklist_item,
                            1
                        ), "Fixture task"
                    )
                    compose.onNodeWithText(text(org.monogram.feature.dialog.R.string.dialog_checklist_allow_append))
                        .performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
                } else {
                    fill(
                        text(org.monogram.feature.dialog.R.string.dialog_poll_question),
                        "Synthetic poll"
                    )
                    fill(
                        context.getString(
                            org.monogram.feature.dialog.R.string.dialog_poll_option,
                            1
                        ), "First"
                    )
                    fill(
                        context.getString(
                            org.monogram.feature.dialog.R.string.dialog_poll_option,
                            2
                        ), "Second"
                    )
                }
                if (kind == 0) {
                    val send = text(org.monogram.feature.dialog.R.string.dialog_send)
                    rights(false, false, false)
                    compose.waitUntil(5_000) {
                        compose.onAllNodes(hasText(send) and !isEnabled()).fetchSemanticsNodes()
                            .isNotEmpty()
                    }
                    compose.runOnIdle {
                        (rootComponent!!.stack.value.active.instance as RootComponent.Child.Dialog).component
                            .onSendPoll(PollSendSpec("Rejected poll", listOf("First", "Second")))
                    }
                    runBlocking { assertTrue(db.outgoingTextSendDao().all().isEmpty()) }
                    rights(false, false, true)
                    compose.waitUntil(5_000) {
                        compose.onAllNodes(hasText(send) and isEnabled()).fetchSemanticsNodes()
                            .isNotEmpty()
                    }
                    compose.onNode(hasSetTextAction() and hasText("Synthetic poll"))
                        .assertTextContains("Synthetic poll")
                }
                compose.onNodeWithText(text(org.monogram.feature.dialog.R.string.dialog_send_silent))
                    .performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
                compose.onNodeWithText(text(org.monogram.feature.dialog.R.string.dialog_send))
                    .performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
                compose.waitUntil(5_000) {
                    runBlocking {
                        db.outgoingTextSendDao().all().size == 1
                    }
                }
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText(create).fetchSemanticsNodes().isEmpty()
                }
                compose.onNodeWithText("Keep draft").assertTextContains("Keep draft")
                if (kind == 0) rights(true, true, false)
                runBlocking { worker.syncOnce() }
                val expected = kind + 1
                compose.waitUntil(5_000) {
                    delivered.size == expected && compose.onAllNodesWithText(
                        if (checklist) "Fixture task" else "First"
                    ).fetchSemanticsNodes().isNotEmpty()
                }
                runBlocking {
                    assertTrue(db.outgoingTextSendDao().all().isEmpty())
                    assertEquals(
                        "Keep draft",
                        cache.captureOutgoingTextDraft(
                            PeerId(7),
                            0,
                            cache.outgoingAccountEpoch()
                        ).draft.text
                    )
                }
            }
            assertEquals(2, identities.size); assertEquals(2, identities.toSet().size)
            runBlocking { assertEquals(2, db.messageDao().byIds(7, listOf(37, 38)).size) }
        } finally {
            scenario?.close(); UiStateTestActivity.content = {}; db.close(); context.deleteDatabase(
                name
            )
        }
    }
}
