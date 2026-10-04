package org.monogram.feature.settings.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.monogram.core.common.DebugLog
import org.monogram.core.ui.theme.MonogramTheme

@OptIn(ExperimentalMaterial3Api::class)
class SettingsLogsLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    @After
    fun tearDown() {
        DebugLog.resetForTests(enabled = false)
    }

    @Test
    fun filtersSearchAndActionsKeepTheirPlaces() {
        DebugLog.resetForTests(enabled = true)
        DebugLog.ingestApi("messages.getHistory", "chat=1")
        DebugLog.ingestPerf("recomp", "ChatRow", null)
        DebugLog.ingestPerf("cache_hit", "photo", null)
        DebugLog.ingestPerf("bridge:connect", "dc=2", 8L)
        DebugLog.ingestWarn("updates", "gap")

        var query by mutableStateOf("")
        var kindName by mutableStateOf("all")
        var confirm by mutableStateOf(false)
        rule.setContent {
            MonogramTheme(dynamicColor = false) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Logs") },
                            actions = {
                                LogsTopActions(
                                    onShare = {},
                                    onClear = { confirm = true },
                                )
                            },
                        )
                    },
                ) { padding ->
                    SettingsLogs(
                        query = query,
                        kind = debugLogKind(kindName),
                        shareFailed = false,
                        onQuery = { query = it },
                        onKind = { kind -> kindName = kind?.name?.lowercase() ?: "all" },
                        modifier = Modifier.padding(padding),
                    )
                    if (confirm) {
                        AlertDialog(
                            onDismissRequest = { confirm = false },
                            title = { Text("Clear logs") },
                            text = { Text("Clear the in-app log? This cannot be undone.") },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        confirm = false
                                        DebugLog.clear()
                                    },
                                ) { Text("Clear") }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirm = false }) {
                                    Text("Cancel")
                                }
                            },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        val search = rule.onNodeWithText("Search logs").fetchSemanticsNode().boundsInRoot
        val all = rule.onNodeWithTag("logs-filter-all").fetchSemanticsNode().boundsInRoot
        val api = rule.onNodeWithTag("logs-filter-api").fetchSemanticsNode().boundsInRoot
        val share = rule.onNodeWithContentDescription("Share logs").fetchSemanticsNode().boundsInRoot
        val clear = rule.onNodeWithContentDescription("Clear logs").fetchSemanticsNode().boundsInRoot
        assertTrue(search.bottom < all.top)
        assertTrue(all.left < api.left)
        assertTrue(abs(all.top - api.top) < 8f)
        assertTrue(share.right < clear.left)
        assertTrue(share.bottom < search.top)
        assertTrue(all.bottom < rule.onNodeWithText("messages.getHistory", substring = true).fetchSemanticsNode().boundsInRoot.top)

        rule.onNodeWithTag("logs-filter-recomp").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("ChatRow", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("messages.getHistory", substring = true).assertCountEquals(0)

        rule.onNodeWithContentDescription("Clear logs").performClick()
        rule.onNodeWithText("Clear the in-app log? This cannot be undone.").assertIsDisplayed()
        rule.onNodeWithText("Clear").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("No events yet", useUnmergedTree = true).assertIsDisplayed()
    }
}
