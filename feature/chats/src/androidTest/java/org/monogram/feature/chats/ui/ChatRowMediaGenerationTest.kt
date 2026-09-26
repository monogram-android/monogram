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
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.monogram.core.models.Chat
import org.monogram.core.models.PeerId
import org.monogram.core.ui.perf.RecompositionProbe
import org.monogram.network.http.MediaRepository

class ChatRowMediaGenerationTest {
    @get:Rule
    val rule = createComposeRule()

    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val descriptor: ParcelFileDescriptor = automation.executeShellCommand(command)
        return descriptor.use { pfd ->
            FileInputStream(pfd.fileDescriptor).bufferedReader().readText()
        }
    }

    private fun window(text: String): String {
        assertTrue("marker missing from logcat — cannot attribute the counts", text.contains("chatRow:start"))
        return text.substringAfterLast("chatRow:start", "")
    }

    private fun count(text: String) = text.lineSequence().count {
        it.contains("recomp ChatRow ") || it.endsWith("recomp ChatRow")
    }

    private fun chat(): Chat = Chat(
        id = PeerId(1),
        title = "Perf chat",
        unreadCount = 0,
        lastMessageDate = 1,
        emojiStatusDocumentId = null,
        lastMessageMediaKind = null,
    )

    @Test
    fun unrelatedPassesSkipButAGenerationChangeStillRestarts() {
        shell("setprop log.tag.monogram.perf DEBUG")
        Thread.sleep(1_500)

        val cacheRoot = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "perf-chat-row-cache",
        )
        cacheRoot.mkdirs()
        val repository = MediaRepository(cacheRoot = cacheRoot)

        val chat = chat()
        val contentPasses = AtomicInteger(0)
        var tick by mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                Column {
                    contentPasses.incrementAndGet()
                    RecompositionProbe("TestParent", tick)
                    ChatRow(
                        chat = chat,
                        selected = false,
                        savedMessages = false,
                        mediaRepository = repository,
                        showAvatar = false,
                        showReadStatus = true,
                        texts = chatListTexts(),
                        onClick = { },
                        onAvatarClick = null,
                    )
                }
            }
        }
        rule.waitForIdle()
        Thread.sleep(1_000)
        Log.i("recomp.marker", "chatRow:start")
        rule.waitForIdle()
        val afterFirstComposition = contentPasses.get()

        val passes = 20
        repeat(passes) { rule.runOnIdle { tick++ } }
        rule.waitForIdle()
        Thread.sleep(400)
        val afterTicks = window(shell("logcat -d -s monogram.perf:I recomp.marker:I"))
        val rowTicks = count(afterTicks)
        val tickPasses = contentPasses.get() - afterFirstComposition

        rule.runOnIdle { repository.clearCache() }
        rule.waitForIdle()
        Thread.sleep(400)
        val rowDelta = count(window(shell("logcat -d -s monogram.perf:I recomp.marker:I"))) - rowTicks

        shell("setprop log.tag.monogram.perf SILENT")
        Log.i(
            "recomp.count",
            "chatRow unrelatedPasses=$tickPasses rowRestarts=$rowTicks; generationChange rowRestarts=$rowDelta",
        )

        assertTrue("unrelated phase only recomposed $tickPasses times", tickPasses >= passes / 2)
        assertTrue(
            "ChatRow restarted $rowTicks times across $tickPasses unrelated passes after settling",
            rowTicks <= 5,
        )
        assertTrue(
            "ChatRow restarted $rowDelta times on an unrelated media cache generation change",
            rowDelta <= 5,
        )
    }
}
