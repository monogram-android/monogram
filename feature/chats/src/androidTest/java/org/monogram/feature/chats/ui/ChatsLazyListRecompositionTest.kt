package org.monogram.feature.chats.ui

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.rememberLazyListState
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
import org.monogram.core.models.Chat
import org.monogram.core.models.PeerId
import org.monogram.core.ui.components.FolderChips
import org.monogram.core.ui.perf.RecompositionProbe

private const val MARKER = "recomp.marker"

class ChatsLazyListRecompositionTest {
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
        assertTrue("marker missing from logcat — cannot attribute the counts", text.contains("list:start"))
        return text.substringAfterLast("list:start", "")
    }

    private fun count(text: String, needle: String) = text.lineSequence().count { it.contains(needle) }

    private fun list(
        firstTitle: String,
        secondTitle: String = "Other",
    ) = listOf(
        Chat(id = PeerId(1), title = firstTitle, lastMessageDate = 1),
        Chat(id = PeerId(2), title = secondTitle, lastMessageDate = 1),
    )

    @Test
    fun equalIdsSkipTheListAndUnchangedRows() {
        shell("setprop log.tag.monogram.perf DEBUG")
        Log.i(MARKER, "list:start")
        Thread.sleep(1_500)

        val contentPasses = AtomicInteger(0)
        var tick by mutableIntStateOf(0)
        val chats = ChatListSnapshot().also { it.replace(list("Perf")) }
        rule.setContent {
            MaterialTheme {
                Column {
                    contentPasses.incrementAndGet()
                    RecompositionProbe("TestParent", tick)
                    ChatsLazyList(
                        chats = chats,
                        listState = rememberLazyListState(),
                        archive = false,
                        searchOpen = false,
                        query = "",
                        homeFolderId = null,
                        chips = FolderChips(emptyList()),
                        allChats = chats,
                        folders = emptyList(),
                        archivedTitles = null,
                        archivedUnmuted = 0,
                        archivedMuted = 0,
                        showArchiveRow = false,
                        loading = false,
                        error = null,
                        hasMore = false,
                        loadingMore = false,
                        selectedChatId = null,
                        selfPeerId = null,
                        showAvatar = false,
                        showReadStatus = true,
                        openAvatarsInProfile = false,
                        texts = chatListTexts(),
                        mediaRepository = null,
                        folderMenu = null,
                        rowMenuId = null,
                        onSelectFolder = {},
                        onManageFolders = {},
                        onFolderLongPress = {},
                        onDismissFolderMenu = {},
                        onMarkFolderRead = {},
                        onEditFolders = {},
                        onOpenArchive = {},
                        onOpenChat = {},
                        onOpenAvatar = {},
                        onRowMenu = {},
                        onDismissRowMenu = {},
                        onMarkRead = {},
                        onMarkUnread = {},
                        onClearSearch = {},
                        onLoadMore = {},
                        markReadLabel = "Read",
                        markUnreadLabel = "Unread",
                        manageFoldersLabel = "Folders",
                    )
                }
            }
        }
        rule.waitForIdle()
        val afterFirst = contentPasses.get()

        val tickPasses = 20
        repeat(tickPasses) { rule.runOnIdle { tick++ } }
        rule.waitForIdle()
        Thread.sleep(400)
        val afterTicks = contentPasses.get()
        val afterTicksLog = window(shell("logcat -d -s monogram.perf:I $MARKER:I"))
        val listTicks = count(afterTicksLog, "recomp ChatsLazyList")
        val itemOneTicks = count(afterTicksLog, "recomp ChatListItem 1")
        val itemTwoTicks = count(afterTicksLog, "recomp ChatListItem 2")

        rule.runOnIdle { chats.replace(list("Changed")) }
        rule.waitForIdle()
        Thread.sleep(400)
        val log = window(shell("logcat -d -s monogram.perf:I $MARKER:I"))
        val listDelta = count(log, "recomp ChatsLazyList") - listTicks
        val itemOneDelta = count(log, "recomp ChatListItem 1") - itemOneTicks
        val itemTwoDelta = count(log, "recomp ChatListItem 2") - itemTwoTicks

        shell("setprop log.tag.monogram.perf SILENT")

        val tickDelta = afterTicks - afterFirst
        assertTrue("parent only ticked $tickDelta times, expected about $tickPasses", tickDelta >= tickPasses / 2)
        assertTrue(
            "list restarted $listTicks times across $tickDelta equal-content parent passes",
            listTicks <= 3,
        )
        assertTrue("a same-id title change restarted the list (delta $listDelta)", listDelta <= 1)
        assertTrue("changed row did not restart (delta $itemOneDelta)", itemOneDelta >= 1)
        assertTrue("unchanged row restarted (delta $itemTwoDelta)", itemTwoDelta <= 1)
    }
}
