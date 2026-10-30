package org.monogram.root

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.monogram.UiStateTestActivity
import org.monogram.core.common.Outcome
import org.monogram.core.models.*
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.network.bridge.MtprotoClient
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

class ChecklistTitleInteractionTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun editExistingTitlePreservesItemsAndRequiresExplicitRetryAfterFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var message = Message(
            MessageId(PeerId(7), 37), null, null, 100, outgoing = true, mediaKind = "todo",
            checklist = Checklist(
                "Synthetic checklist", entities = listOf(TextEntity("bold", 0, 9)), items = listOf(
                    ChecklistItem(
                        0,
                        "Fixture task",
                        done = true,
                        completedBy = 42,
                        completedAt = 9,
                        entities = listOf(TextEntity("italic", 0, 7))
                    ),
                    ChecklistItem(-7, "Keep task", done = true, completedBy = 43, completedAt = 10)
                )
            )
        )
        val mutations = AtomicInteger()
        val removals = AtomicInteger()
        val limitFailures = AtomicInteger()
        val client = Proxy.newProxyInstance(
            MtprotoClient::class.java.classLoader,
            arrayOf(MtprotoClient::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString", "libraryVersion" -> "SyntheticChecklistClient"
                "proxyConnectionStatus" -> org.monogram.network.bridge.session.ProxyConnectionStatus.Disabled
                "updates", "sessionLost" -> emptyFlow<Nothing>()
                "getChats" -> Outcome.Ok(listOf(Chat(PeerId(7), "Fixture chat")))
                "getProfile" -> Outcome.Ok(Profile(PeerId(7), "fixture", "Fixture chat"))
                "getHistory", "getHistoryPage", "getHistoryPageBackground" -> Outcome.Ok(
                    listOf(
                        message
                    )
                )

                "getMessage" -> Outcome.Ok(message)
                "getPinnedMessages", "getSavedGifs" -> Outcome.Ok(emptyList<Message>())
                "getGroupAdminTags" -> Outcome.Ok(emptyMap<PeerId, String>())
                "editChecklistText" -> {
                    assertEquals(37, args!![1])
                    val spec = args[2] as ChecklistTextEdit
                    val list = message.checklist!!
                    val item = spec.itemId?.let { id -> list.items.single { it.id == id } }
                    assertEquals(item?.text ?: list.title, spec.expectedText)
                    assertEquals(item?.entities ?: list.entities, spec.expectedEntities)
                    assertEquals(spec.expectedEntities, spec.entities)
                    if (spec.itemId == null && spec.text.length > 32) {
                        limitFailures.incrementAndGet(); Outcome.Err("checklist text too long")
                    } else if (mutations.incrementAndGet() == 1) Outcome.Err("RPC 500: INTERNAL")
                    else {
                        message = message.copy(
                            checklist = if (spec.itemId == null) list.copy(
                            title = spec.text,
                            entities = spec.entities
                        )
                        else list.copy(items = list.items.map {
                            if (it.id == spec.itemId) it.copy(
                                text = spec.text,
                                entities = spec.entities
                            ) else it
                        })
                        )
                        Outcome.Ok(Unit)
                    }
                }

                "removeChecklistItem" -> {
                    val spec = args!![2] as ChecklistTextEdit
                    assertEquals(0, spec.itemId)
                    assertEquals("Fixture task edited", spec.expectedText)
                    if (removals.incrementAndGet() == 1) Outcome.Err("RPC 500: INTERNAL")
                    else {
                        message =
                            message.copy(checklist = message.checklist!!.let { it.copy(items = it.items.filterNot { row -> row.id == spec.itemId }) }); Outcome.Ok(
                            Unit
                        )
                    }
                }

                "editText" -> error("checklist must use media edit")
                "setDialogForeground", "close", "setForeground" -> Unit
                "updateStatus", "setTyping", "readHistory" -> Outcome.Ok(Unit)
                else -> Outcome.Err("unsupported synthetic checklist method")
            }
        } as MtprotoClient
        var scenario: ActivityScenario<UiStateTestActivity>? = null
        try {
            UiStateTestActivity.content = { componentContext ->
                val root = remember(componentContext) {
                    RootComponent(
                        componentContext,
                        DefaultStoreFactory(),
                        client,
                        null,
                        null,
                        null,
                        startOnHome = true,
                        bubbleChatId = 7
                    )
                }
                MonogramTheme(dynamicColor = false) { RootContent(root, Modifier.fillMaxSize()) }
            }
            scenario = ActivityScenario.launch(UiStateTestActivity::class.java)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Synthetic checklist").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(
                hasClickAction() and hasAnyDescendant(hasText("Synthetic checklist")),
                useUnmergedTree = true
            )
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            val edit = context.getString(org.monogram.feature.dialog.R.string.dialog_edit)
            try {
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText(edit).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (error: ComposeTimeoutException) {
                throw AssertionError(
                    compose.onAllNodes(isRoot())[0].printToString(maxDepth = 100),
                    error
                )
            }
            compose.onNodeWithText(edit).performClick()
            compose.onNode(hasSetTextAction()).assertTextContains("Synthetic checklist")
            compose.onNode(hasSetTextAction())
                .performTextInputSelection(androidx.compose.ui.text.TextRange(19))
            compose.onNode(hasSetTextAction()).performTextInput("x".repeat(33))
            compose.onNodeWithContentDescription(context.getString(org.monogram.feature.dialog.R.string.dialog_editing))
                .performClick()
            compose.waitUntil(5_000) { limitFailures.get() == 1 }
            compose.onNode(hasSetTextAction())
                .assertTextContains("Synthetic checklist" + "x".repeat(33))
            compose.onNode(hasSetTextAction())
                .performTextInputSelection(androidx.compose.ui.text.TextRange(19, 52))
            compose.onNode(hasSetTextAction())
                .performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Backspace) }
            compose.onNode(hasSetTextAction()).assertTextContains("Synthetic checklist")
            compose.onNode(hasSetTextAction())
                .performTextInputSelection(androidx.compose.ui.text.TextRange(19))
            compose.onNode(hasSetTextAction()).performTextInput(" edited")
            compose.onNodeWithContentDescription(context.getString(org.monogram.feature.dialog.R.string.dialog_editing))
                .performClick()
            compose.waitUntil(5_000) { mutations.get() == 1 }
            compose.onNode(hasSetTextAction()).assertTextContains("Synthetic checklist edited")
            scenario.recreate()
            compose.onNode(hasSetTextAction()).assertTextContains("Synthetic checklist edited")
            assertEquals(1, mutations.get())
            assertTrue(
                compose.onAllNodesWithText("Synthetic checklist").fetchSemanticsNodes().isNotEmpty()
            )
            compose.onNodeWithContentDescription(context.getString(org.monogram.feature.dialog.R.string.dialog_editing))
                .performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasSetTextAction() and hasText("")).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("Synthetic checklist edited").assertIsDisplayed()
            compose.onNodeWithText("Fixture task").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).assertTextEquals("")
            assertEquals(2, mutations.get())
            val originalItem = ChecklistItem(
                0,
                "Fixture task",
                done = true,
                completedBy = 42,
                completedAt = 9,
                entities = listOf(TextEntity("italic", 0, 7))
            )
            val keptItem =
                ChecklistItem(-7, "Keep task", done = true, completedBy = 43, completedAt = 10)
            assertEquals(listOf(originalItem, keptItem), message.checklist!!.items)
            compose.onNodeWithContentDescription(
                context.getString(
                    org.monogram.feature.dialog.R.string.dialog_checklist_edit_item,
                    "Fixture task"
                )
            ).performClick()
            compose.onNode(hasSetTextAction()).assertTextContains("Fixture task")
            compose.onNode(hasSetTextAction())
                .performTextInputSelection(androidx.compose.ui.text.TextRange(12))
            compose.onNode(hasSetTextAction()).performTextInput(" edited")
            scenario.recreate()
            compose.onNode(hasSetTextAction()).assertTextContains("Fixture task edited")
            assertEquals(2, mutations.get())
            compose.onNodeWithContentDescription(context.getString(org.monogram.feature.dialog.R.string.dialog_editing))
                .performClick()
            compose.waitUntil(5_000) {
                mutations.get() == 3 && compose.onAllNodes(
                    hasSetTextAction() and hasText(
                        ""
                    )
                ).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Fixture task edited").assertIsDisplayed()
            assertEquals("Synthetic checklist edited", message.checklist!!.title)
            assertEquals(listOf(TextEntity("bold", 0, 9)), message.checklist!!.entities)
            assertEquals(
                listOf(originalItem.copy(text = "Fixture task edited"), keptItem),
                message.checklist!!.items
            )
            val removeLabel = context.getString(
                org.monogram.feature.dialog.R.string.dialog_checklist_remove_item,
                "Fixture task edited"
            )
            val delete = context.getString(org.monogram.feature.dialog.R.string.dialog_delete)
            compose.onNodeWithContentDescription(removeLabel).performClick()
            compose.onNodeWithText(context.getString(org.monogram.feature.dialog.R.string.dialog_cancel))
                .performClick()
            assertEquals(0, removals.get())
            compose.onNodeWithContentDescription(removeLabel).performClick()
            compose.onNodeWithText(delete).performClick()
            compose.waitUntil(5_000) { removals.get() == 1 }
            compose.onNodeWithText("Fixture task edited").assertIsDisplayed()
            compose.onNodeWithContentDescription(removeLabel).performClick()
            compose.onNodeWithText(delete).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Fixture task edited").fetchSemanticsNodes().isEmpty()
            }
            assertEquals(2, removals.get())
            assertEquals(listOf(keptItem), message.checklist!!.items)
            compose.onNodeWithContentDescription(
                context.getString(
                    org.monogram.feature.dialog.R.string.dialog_checklist_remove_item,
                    "Keep task"
                )
            ).assertDoesNotExist()
        } finally {
            scenario?.close(); UiStateTestActivity.content = {}
        }
    }
}
