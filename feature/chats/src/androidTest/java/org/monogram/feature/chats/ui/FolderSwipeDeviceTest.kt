package org.monogram.feature.chats.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FolderSwipeDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private val selections = mutableListOf<Int?>()
    private val swipe get() = rule.onNodeWithTag("folder-swipe")

    @Test
    fun leftDragMovesBothLayersAndCommitsNextFolderInLtr() {
        assertForwardDrag(LayoutDirection.Ltr, direction = -1)
    }

    @Test
    fun rightDragMovesBothLayersAndCommitsNextFolderInRtl() {
        assertForwardDrag(LayoutDirection.Rtl, direction = 1)
    }

    @Test
    fun reversedDragPreviewsOtherNeighborButSettlesBackWithoutSelecting() {
        showFolders(LayoutDirection.Ltr)
        val viewport = swipe.getBoundsInRoot()
        swipe.performTouchInput {
            down(center)
            moveTo(center - Offset(width * 0.4f, 0f))
        }
        val next = page(2).getBoundsInRoot()
        assertTrue(next.left > viewport.left && next.left < viewport.right)
        assertEquals(viewport.right, next.right)

        swipe.performTouchInput { moveTo(center + Offset(width * 0.1f, 0f)) }
        page(2).assertDoesNotExist()
        val previous = page(0).getBoundsInRoot()
        assertEquals(viewport.left, previous.left)
        assertTrue(previous.right > viewport.left && previous.right < viewport.right)
        rule.runOnIdle { assertTrue(selections.isEmpty()) }

        swipe.performTouchInput { up() }
        assertSettled(1, expectedSelections = emptyList())
    }

    private fun assertForwardDrag(
        layoutDirection: LayoutDirection,
        direction: Int,
    ) {
        showFolders(layoutDirection)
        val viewport = swipe.getBoundsInRoot()
        swipe.performTouchInput {
            down(center)
            moveTo(center + Offset(direction * width * 0.2f, 0f))
        }
        val firstCurrentWidth = page(1).getBoundsInRoot().width
        val firstIncomingWidth = page(2).getBoundsInRoot().width
        assertTrue(firstCurrentWidth < viewport.width && firstIncomingWidth > viewport.width * 0.05f)

        swipe.performTouchInput { moveTo(center + Offset(direction * width * 0.4f, 0f)) }
        val current = page(1).getBoundsInRoot()
        val incoming = page(2).getBoundsInRoot()
        assertTrue(current.width < firstCurrentWidth && incoming.width > firstIncomingWidth)
        assertEquals(
            if (direction < 0) current.right.value else current.left.value,
            if (direction < 0) incoming.left.value else incoming.right.value,
            1f,
        )
        rule.runOnIdle { assertTrue(selections.isEmpty()) }

        swipe.performTouchInput { up() }
        assertSettled(2, expectedSelections = listOf(2))
    }

    private fun showFolders(layoutDirection: LayoutDirection) {
        rule.setContent {
            var selectedId by remember { mutableIntStateOf(1) }
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                FolderTransition(
                    folderIds = listOf(0, 1, 2),
                    selectedId = selectedId,
                    enabled = true,
                    onSelect = { id ->
                        selections += id
                        selectedId = requireNotNull(id)
                    },
                    modifier = Modifier.testTag("folder-swipe"),
                ) { id, _ ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag("folder-page-$id")
                    )
                }
            }
        }
    }

    private fun assertSettled(
        id: Int,
        expectedSelections: List<Int>,
    ) {
        assertEquals(swipe.getBoundsInRoot(), page(id).getBoundsInRoot())
        for (other in 0..2) {
            if (other != id) page(other).assertDoesNotExist()
        }
        rule.runOnIdle { assertEquals(expectedSelections, selections) }
    }

    private fun page(id: Int) = rule.onNodeWithTag("folder-page-$id")

    private val DpRect.width get() = right - left
}
