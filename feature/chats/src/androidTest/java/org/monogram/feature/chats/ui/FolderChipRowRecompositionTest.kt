package org.monogram.feature.chats.ui

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.monogram.core.ui.components.FolderChipItem
import org.monogram.core.ui.components.FolderChipRow
import org.monogram.core.ui.components.FolderChips
import org.monogram.core.ui.perf.RecompositionProbe

private const val MARKER = "recomp.marker"

class FolderChipRowRecompositionTest {
    @get:Rule
    val rule = createComposeRule()

    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val descriptor: ParcelFileDescriptor = automation.executeShellCommand(command)
        return descriptor.use { pfd ->
            FileInputStream(pfd.fileDescriptor).bufferedReader().readText()
        }
    }

    private fun chips(unread: Int) = FolderChips(
        listOf(
            FolderChipItem(id = null, label = "All chats", isAll = true),
            FolderChipItem(id = 7, label = "Work", unread = unread),
        ),
    )

    private fun window(text: String): String {
        assertTrue("marker missing from logcat — cannot attribute the counts", text.contains("chips:start"))
        return text.substringAfterLast("chips:start", "")
    }

    private fun chipRestarts(text: String) = text.lineSequence().count {
        it.contains("recomp FolderChip ") || it.endsWith("recomp FolderChip")
    }

    private fun count(text: String, needle: String) = text.lineSequence().count { it.contains(needle) }

    @Test
    fun equalContentRebuildsDoNotRestartTheRow() {
        shell("setprop log.tag.monogram.perf DEBUG")
        Log.i(MARKER, "chips:start")
        Thread.sleep(1_500)

        val contentPasses = AtomicInteger(0)
        var tick by mutableIntStateOf(0)
        var unread by mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                Column {
                    contentPasses.incrementAndGet()
                    RecompositionProbe("TestParent", tick)
                    FolderChipRow(
                        chips = chips(unread),
                        selectedId = null,
                        onSelect = { },
                    )
                }
            }
        }
        rule.waitForIdle()
        val afterFirstComposition = contentPasses.get()

        val tickPasses = 30
        repeat(tickPasses) { rule.runOnIdle { tick++ } }
        rule.waitForIdle()
        Thread.sleep(400)
        val contentAfterTicks = contentPasses.get()
        val afterTicksLog = window(shell("logcat -d -s monogram.perf:I $MARKER:I"))
        val rowTicks = count(afterTicksLog, "recomp FolderChipRow")
        val chipTicks = chipRestarts(afterTicksLog)

        repeat(3) {
            rule.runOnIdle { unread++ }
            rule.waitForIdle()
        }
        Thread.sleep(400)
        val log = window(shell("logcat -d -s monogram.perf:I $MARKER:I"))
        val contentDelta = contentPasses.get() - contentAfterTicks
        val rowDelta = count(log, "recomp FolderChipRow") - rowTicks
        val chipDelta = chipRestarts(log) - chipTicks

        shell("setprop log.tag.monogram.perf SILENT")

        val tickDelta = contentAfterTicks - afterFirstComposition
        Log.i(
            "recomp.count",
            "equalContent parentPasses=$tickDelta rowRestarts=$rowTicks chipRestarts=$chipTicks; " +
                "contentChange parentPasses=$contentDelta rowRestarts=$rowDelta chipRestarts=$chipDelta",
        )

        assertTrue(
            "equal-content phase only recomposed $tickDelta times, expected about $tickPasses",
            tickDelta >= tickPasses / 2,
        )
        assertTrue(
            "row restarted $rowTicks times across $tickDelta equal-content parent passes",
            rowTicks <= 3,
        )
        assertTrue("a chip unread change did not recompose the parent", contentDelta >= 1)
        assertTrue(
            "a chip unread change did not restart the row (delta $rowDelta), stale badges would be the result",
            rowDelta >= 1,
        )
        assertTrue("chip restarts for 3 unread changes: $chipDelta", chipDelta in 1..6)
    }
}
