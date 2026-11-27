package org.monogram.feature.chats.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.monogram.core.models.Chat
import org.monogram.core.models.PeerId
import org.monogram.core.ui.components.FolderChips
import java.util.concurrent.atomic.AtomicInteger

private const val PAGE_SIZE = 25
private const val PAGE_COUNT = 3
private const val TOTAL_CHATS = PAGE_SIZE * PAGE_COUNT

class ChatsLazyListPagingTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun sameEightRowsOnlyRequestMoreInTheTallerViewport() {
        var tall by mutableStateOf(false)
        var rowCount by mutableIntStateOf(8)
        var remaining by mutableIntStateOf(3)
        val calls = AtomicInteger()
        rule.setContent {
            val list = rememberLazyListState()
            FolderUnderfillEffect(list, 7, remaining, rowCount, rowCount + 1) { _, _ ->
                calls.incrementAndGet()
                rowCount++
                remaining--
            }
            LazyColumn(state = list, modifier = Modifier.height(if (tall) 204.dp else 144.dp)) {
                items((1..rowCount).toList(), key = { it }) { Spacer(Modifier.height(24.dp)) }
                item { Spacer(Modifier.height(48.dp)) }
            }
        }
        rule.runOnIdle {
            assertEquals(0, calls.get())
            tall = true
        }
        rule.runOnIdle {
            assertEquals(1, calls.get())
            assertEquals(9, rowCount)
            assertEquals(2, remaining)
        }
    }

    @Test
    fun scrollingPagesUntilExhaustedThenStaysQuietWhileIdleAndReopened() {
        val allChats =
            (1..TOTAL_CHATS).map { id ->
                Chat(
                    id = PeerId(id.toLong()),
                    title = "Chat $id",
                    lastMessageDate = id.toLong(),
                )
            }
        val chats = ChatListSnapshot().also { it.replace(allChats.take(PAGE_SIZE)) }
        val loadCalls = AtomicInteger(0)
        val loadedPages = AtomicInteger(1)
        var hasMore by mutableStateOf(true)
        var contentVisible by mutableStateOf(true)
        var generation by mutableIntStateOf(0)

        rule.setContent {
            MaterialTheme {
                if (contentVisible) {
                    key(generation) {
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
                            hasMore = hasMore,
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
                            onLoadMore = {
                                loadCalls.incrementAndGet()
                                if (loadedPages.get() < PAGE_COUNT) {
                                    val page = loadedPages.incrementAndGet()
                                    chats.replace(allChats.take(PAGE_SIZE * page))
                                    if (page >= PAGE_COUNT) hasMore = false
                                }
                            },
                            markReadLabel = "Read",
                            markUnreadLabel = "Unread",
                            manageFoldersLabel = "Folders",
                            inlineFolderChips = false,
                        )
                    }
                }
            }
        }

        fun swipeList() {
            rule.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
            rule.waitForIdle()
        }

        fun scrollUntil(
            maxSwipes: Int,
            condition: () -> Boolean,
        ) {
            var swipes = 0
            while (swipes < maxSwipes && !condition()) {
                swipeList()
                swipes++
            }
        }

        fun reopenList() {
            rule.runOnIdle { contentVisible = false }
            rule.waitForIdle()
            rule.runOnIdle {
                generation++
                contentVisible = true
            }
            rule.waitForIdle()
            Thread.sleep(150)
            rule.waitForIdle()
        }

        rule.waitForIdle()
        Thread.sleep(250)
        rule.waitForIdle()
        assertEquals("idle initial render auto requested a page", 0, loadCalls.get())

        scrollUntil(maxSwipes = 20) { loadCalls.get() > 0 }
        val afterFirstEnd = loadCalls.get()
        assertTrue("scrolling to the end did not request the next page", afterFirstEnd > 0)
        assertTrue("requested pages did not grow the rendered list", chats.size > PAGE_SIZE)

        val callsBeforeReopen = loadCalls.get()
        reopenList()
        assertEquals("reopening the list auto requested a page", callsBeforeReopen, loadCalls.get())

        scrollUntil(maxSwipes = 60) { loadedPages.get() >= PAGE_COUNT }
        assertEquals("each remaining page must load exactly once", PAGE_COUNT - 1, loadCalls.get())
        assertEquals(TOTAL_CHATS, chats.size)
        assertFalse("more pages remain after the last page", hasMore)

        val exhaustedCalls = loadCalls.get()
        Thread.sleep(300)
        rule.waitForIdle()
        assertEquals("idle list auto requested after exhaustion", exhaustedCalls, loadCalls.get())

        reopenList()
        repeat(20) { swipeList() }
        assertEquals(
            "reopened exhausted list auto requested a page",
            exhaustedCalls,
            loadCalls.get(),
        )
        assertEquals(TOTAL_CHATS, chats.size)
    }
}
