package org.monogram.feature.chats.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.monogram.core.ui.components.FolderChipItem
import org.monogram.core.ui.components.FolderChipRow
import org.monogram.core.ui.components.FolderChips

class FolderChipRowInstrumentedTest {
    @get:Rule
    val rule = createComposeRule()

    private fun chips(unread: Int) = FolderChips(
        listOf(
            FolderChipItem(id = null, label = "All chats", isAll = true),
            FolderChipItem(id = 7, label = "Work", unread = unread),
        ),
    )

    @Test
    fun longPressSurvivesRecompositionUnderTheFinger() {
        var unread by mutableIntStateOf(0)
        var longPressed: Int? = null
        rule.setContent {
            MaterialTheme {
                Column {
                    FolderChipRow(
                        chips = chips(unread),
                        selectedId = null,
                        onSelect = { },
                        onLongPress = { longPressed = it.id },
                    )
                }
            }
        }

        val chip = rule.onNodeWithText("Work")
        chip.performTouchInput { down(center) }
        rule.runOnIdle { unread = 5 }
        rule.waitUntil(timeoutMillis = 2_000) { longPressed != null }
        chip.performTouchInput { up() }

        assertEquals(7, longPressed)
    }

    @Test
    fun tapSelectsTheTappedFolder() {
        var selected: Int? = -1
        rule.setContent {
            MaterialTheme {
                Column {
                    FolderChipRow(
                        chips = chips(unread = 2),
                        selectedId = null,
                        onSelect = { selected = it },
                    )
                }
            }
        }

        rule.onNodeWithText("Work").performClick()

        assertEquals(7, selected)
    }

    @Test
    fun equalContentRebuildKeepsTheSelection() {
        var unread by mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                Column {
                    FolderChipRow(
                        chips = chips(unread),
                        selectedId = 7,
                        onSelect = { },
                    )
                }
            }
        }

        rule.onNodeWithText("Work").assertExists()
        rule.onNodeWithText("Work").assertIsSelected()
        rule.runOnIdle { unread = 1 }
        rule.onNodeWithText("Work").assertExists()
        rule.onNodeWithText("Work").assertIsSelected()
    }
}
