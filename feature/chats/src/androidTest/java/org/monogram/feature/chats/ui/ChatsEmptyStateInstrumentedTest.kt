package org.monogram.feature.chats.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import android.graphics.Bitmap
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.monogram.core.ui.components.FolderChipItem
import org.monogram.core.ui.components.FolderChipRow
import org.monogram.core.ui.components.FolderChips

class ChatsEmptyStateInstrumentedTest {
    @get:Rule
    val rule = createComposeRule()

    private val chips = FolderChips(
        listOf(
            FolderChipItem(id = null, label = "All chats", isAll = true),
            FolderChipItem(id = 7, label = "Unread", isUnreadFilter = true),
        ),
    )

    @Test
    fun chipsStayReachableWhileTheFolderIsEmpty() {
        var selected: Int? = null
        rule.setContent {
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "folder-chips") {
                        FolderChipRow(
                            chips = chips,
                            selectedId = 7,
                            onSelect = { selected = it },
                        )
                    }
                    item(key = "empty-state") {
                        FolderEmptyState(
                            archive = false,
                            filtered = true,
                            query = "",
                            onShowAll = { selected = null },
                            onClearSearch = {},
                        )
                    }
                }
            }
        }

        rule.onNodeWithText("Unread").assertIsDisplayed()
        rule.onNodeWithText("This folder is empty").assertIsDisplayed()
        capture("G-empty-folder-with-chips")
        rule.onNodeWithText("All chats").performClick()
        rule.runOnIdle { assertEquals(null, selected) }
    }

    private fun capture(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val bitmap = automation.takeScreenshot()
        val directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "chats-qa",
        ).apply { mkdirs() }
        val file = File(directory, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        automation.executeShellCommand("mkdir -p /sdcard/Download/monogram-qa").close()
        automation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/monogram-qa/$name.png").close()
    }

    @Test
    fun emptyStateOffersASearchEscapeWhenQuerying() {
        var cleared: String? = null
        rule.setContent {
            MaterialTheme {
                FolderEmptyState(
                    archive = false,
                    filtered = false,
                    query = "zzz",
                    onShowAll = {},
                    onClearSearch = { cleared = it },
                )
            }
        }
        rule.onNodeWithText("Nothing found").assertIsDisplayed()
        rule.onNodeWithText("Clear search").performClick()
        rule.runOnIdle { assertEquals("", cleared) }
    }

    @Test
    fun archiveEmptyStateExplainsWhereArchivedChatsLive() {
        rule.setContent {
            MaterialTheme {
                FolderEmptyState(
                    archive = true,
                    filtered = false,
                    query = "",
                    onShowAll = {},
                    onClearSearch = {},
                )
            }
        }
        rule.onNodeWithText("No archived chats").assertIsDisplayed()
        rule.onNodeWithText("Chats you archive disappear from the main list and live here.")
            .assertIsDisplayed()
    }
}
