package org.monogram.feature.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.core.models.ForumTopic
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.ui.components.AppSyncStatus

class DialogNavigationTest {
    private fun msg(id: Int, outgoing: Boolean = false) = Message(
        id = MessageId(PeerId(1), id),
        senderId = PeerId(2),
        text = "m$id",
        date = id.toLong(),
        outgoing = outgoing,
    )

    @Test
    fun liftAnchorMovesTheBottomRowToTheTopEdge() {
        // Reversed window [10..20]: index 10 sits at the bottom edge, so the anchor is mid-screen.
        assertEquals(5, liftAnchorTarget((10..20).toList(), anchorIndex = 15))
    }

    @Test
    fun liftAnchorClampsAtTheNewestRow() {
        // Moving down to the newest edge is still progress while the bottom row is above index 0.
        assertEquals(0, liftAnchorTarget((2..12).toList(), anchorIndex = 6))
    }

    @Test
    fun liftAnchorIsDoneWhenNothingIsAboveTheAnchor() {
        // Nothing left to lift: no row above the anchor, or the newest edge is already reached.
        assertNull(liftAnchorTarget((5..15).toList(), anchorIndex = 15))
        assertNull(liftAnchorTarget((0..10).toList(), anchorIndex = 4))
        assertNull(liftAnchorTarget(emptyList(), anchorIndex = 3))
    }

    @Test
    fun unreadAnchorIsTheOldestUnreadWithKnownBoundary() {
        val messages = listOf(msg(30), msg(29), msg(28), msg(27), msg(26))
        assertEquals(
            28,
            unreadAnchorId(
                messages = messages,
                unreadCount = 2,
                readInboxMaxId = 27,
                hasOlder = true,
                hasNewer = false,
            ),
        )
    }

    @Test
    fun unreadAnchorWaitsUntilBoundaryIsLoaded() {
        val messages = listOf(msg(30), msg(29), msg(28))
        // Everything loaded is unread and older history exists: the true first unread is above.
        assertNull(
            unreadAnchorId(
                messages = messages,
                unreadCount = 3,
                readInboxMaxId = 20,
                hasOlder = true,
                hasNewer = false,
            ),
        )
        // At the end of history the oldest loaded message is the best anchor available.
        assertEquals(
            28,
            unreadAnchorId(
                messages = messages,
                unreadCount = 3,
                readInboxMaxId = 20,
                hasOlder = false,
                hasNewer = false,
            ),
        )
    }

    @Test
    fun unreadAnchorUsesCountOnlyInsideLiveEdgeWindow() {
        val messages = listOf(msg(30), msg(29), msg(28), msg(27), msg(26))
        assertEquals(
            28,
            unreadAnchorId(
                messages = messages,
                unreadCount = 2,
                readInboxMaxId = 0,
                hasOlder = false,
                hasNewer = false,
            ),
        )
        // Count larger than the window proves the cluster continues past its oldest row.
        assertNull(
            unreadAnchorId(
                messages = messages,
                unreadCount = 5,
                readInboxMaxId = 0,
                hasOlder = true,
                hasNewer = false,
            ),
        )
        assertNull(
            unreadAnchorId(
                messages = messages,
                unreadCount = 2,
                readInboxMaxId = 0,
                hasOlder = false,
                hasNewer = true,
            ),
        )
    }

    @Test
    fun readReceiptUsesNewestVisibleMessage() {
        // Newest loaded is 30, but the user only scrolled up to 20: acknowledge 20, not 30.
        assertEquals(
            20,
            readReceiptTarget(
                newestVisibleId = 20,
                newestLoadedId = 30,
                readInboxMaxId = 10,
                hasNewer = false,
                searching = false,
            ),
        )
    }

    @Test
    fun readReceiptAcknowledgesWholePageAtLiveEdge() {
        assertEquals(
            30,
            readReceiptTarget(
                newestVisibleId = 30,
                newestLoadedId = 30,
                readInboxMaxId = 10,
                hasNewer = false,
                searching = false,
            ),
        )
    }

    @Test
    fun readReceiptSkipsAlreadyReadMessages() {
        assertNull(
            readReceiptTarget(
                newestVisibleId = 12,
                newestLoadedId = 30,
                readInboxMaxId = 12,
                hasNewer = false,
                searching = false,
            ),
        )
    }

    @Test
    fun readReceiptNeverFiresInStaleWindowOrSearch() {
        assertNull(
            readReceiptTarget(
                newestVisibleId = 30,
                newestLoadedId = 30,
                readInboxMaxId = 10,
                hasNewer = true,
                searching = false,
            ),
        )
        assertNull(
            readReceiptTarget(
                newestVisibleId = 30,
                newestLoadedId = 30,
                readInboxMaxId = 10,
                hasNewer = false,
                searching = true,
            ),
        )
        assertNull(
            readReceiptTarget(
                newestVisibleId = 0,
                newestLoadedId = 0,
                readInboxMaxId = 0,
                hasNewer = false,
                searching = false,
            ),
        )
    }

    @Test
    fun historyOrderIsNewestIdFirst() {
        val ordered = historyOrder(listOf(msg(1), msg(3), msg(2)))
        assertEquals(listOf(3, 2, 1), ordered.map { it.id.id })
    }

    @Test
    fun albumGroupsConsecutiveGroupedIds() {
        val messages = listOf(
            msg(5).copy(groupedId = 9L),
            msg(4).copy(groupedId = 9L),
            msg(3).copy(groupedId = 9L),
            msg(2),
        )
        assertEquals(true, isAlbumHead(messages, 0))
        assertEquals(false, isAlbumHead(messages, 1))
        assertEquals(false, isAlbumHead(messages, 2))
        assertEquals(true, isAlbumHead(messages, 3))
        assertEquals(listOf(5, 4, 3), albumSlice(messages, 0).map { it.id.id })
        assertEquals(listOf(2), albumSlice(messages, 3).map { it.id.id })
        assertEquals(
            listOf(3, 4, 5),
            albumVisualItems(albumSlice(messages, 0)).map { it.id.id },
        )
    }

    @Test
    fun unreadDividerUsesCountWhenInboxUnknown() {
        val messages = listOf(msg(30), msg(20), msg(10), msg(5), msg(1))
        assertEquals(0, unreadJumpAddOffset(0))
        assertEquals(0, unreadJumpAddOffset(1))
        assertEquals(2, unreadJumpAddOffset(3))
        assertEquals(2, unreadDividerIndex(messages, unreadCount = 2, readInboxMaxId = 0))
    }

    @Test
    fun unreadDividerUsesInboxMaxId() {
        val messages = listOf(msg(30), msg(20), msg(10), msg(5))
        assertEquals(1, unreadDividerIndex(messages, unreadCount = 9, readInboxMaxId = 10))
    }

    @Test
    fun unreadDividerIgnoresOutgoing() {
        val messages = listOf(msg(30), msg(25, outgoing = true), msg(20), msg(10))
        assertEquals(2, unreadDividerIndex(messages, unreadCount = 0, readInboxMaxId = 10))
    }

    @Test
    fun unreadDividerAbsentWhenCaughtUp() {
        val messages = listOf(msg(30), msg(20))
        assertNull(unreadDividerIndex(messages, unreadCount = 0, readInboxMaxId = 30))
    }

    @Test
    fun unreadDividerHiddenOnStaleWindow() {
        // Jumped to pinned/date: count points at an arbitrary old row, and the
        // real unread messages are newer than this window.
        val messages = listOf(msg(30), msg(20), msg(10), msg(5), msg(1))
        assertNull(
            unreadDividerIndex(messages, unreadCount = 2, readInboxMaxId = 0, hasNewer = true),
        )
    }

    @Test
    fun unreadDividerHiddenWhenInboxKnownAndNoMatch() {
        val messages = listOf(msg(9), msg(8), msg(7), msg(6), msg(5))
        assertNull(
            unreadDividerIndex(messages, unreadCount = 3, readInboxMaxId = 30),
        )
    }

    @Test
    fun editReplacesContentAndPreservesLocalReadState() {
        val current = msg(20).copy(text = "before", read = true)
        val edited = current.copy(text = "after", editDate = 99, read = false)

        val result = applyMessageEdit(listOf(current, msg(10)), edited)

        assertEquals("after", result.first().text)
        assertEquals(99L, result.first().editDate)
        assertEquals(true, result.first().read)
    }

    @Test
    fun reactionDoesNotMarkMessageEdited() {
        val current = msg(20).copy(reactionsJson = "{}")
        val reacted = current.copy(
            reactionsJson = "{\"chosen\":true}",
            editDate = 50,
        )
        val result = applyMessageEdit(listOf(current), reacted)
        assertNull(result.single().editDate)
        assertEquals("{\"chosen\":true}", result.single().reactionsJson)
    }

    @Test
    fun reactionKeepsExistingEditedBadge() {
        val current = msg(20).copy(editDate = 10, reactionsJson = "{}")
        val reacted = current.copy(
            reactionsJson = "{\"chosen\":true}",
            editDate = null,
        )
        val result = applyMessageEdit(listOf(current), reacted)
        assertEquals(10L, result.single().editDate)
    }

    @Test
    fun deleteRemovesAllMatchingMessages() {
        val result = removeDeletedMessages(listOf(msg(30), msg(20), msg(10)), setOf(30, 10))
        assertEquals(listOf(20), result.map { it.id.id })
    }

    @Test
    fun historyPagingStopsDuringSearch() {
        assertEquals(true, historyPagingAllowed(""))
        assertEquals(false, historyPagingAllowed("grok"))
    }

    @Test
    fun jumpNeedsFetchOnlyOutsideWindow() {
        val messages = listOf(msg(30), msg(20), msg(10))
        assertEquals(false, jumpNeedsFetch(messages, 20))
        assertEquals(true, jumpNeedsFetch(messages, 25))
        assertEquals(true, jumpNeedsFetch(emptyList(), 10))
    }

    @Test
    fun pinnedIndexWraps() {
        assertEquals(0, nextPinnedIndex(2, 3))
        assertEquals(1, nextPinnedIndex(0, 3))
        assertEquals(0, nextPinnedIndex(0, 0))
    }

    @Test
    fun senderTagsKeepCustomRankAndAddBots() {
        val admin = PeerId(1)
        val bot = PeerId(2)
        val member = PeerId(3)
        val tags = mergeSenderTags(
            admin = mapOf(admin to "rank:Helper"),
            senders = mapOf(
                admin to Profile(admin, "user", "Ada"),
                bot to Profile(bot, "user", "Grok", isBot = true),
                member to Profile(member, "user", "Bob"),
            ),
        )
        assertEquals("rank:Helper", tags[admin])
        assertEquals("role:bot", tags[bot])
        assertEquals(false, tags.containsKey(member))
    }

    @Test
    fun pinnedReloadKeepsCurrentMessage() {
        val messages = listOf(msg(30), msg(20), msg(10))
        assertEquals(1, pinnedIndexAfterReload(20, messages))
        assertEquals(0, pinnedIndexAfterReload(99, messages))
        assertEquals(0, pinnedIndexAfterReload(null, messages))
        assertEquals(0, pinnedIndexAfterReload(20, emptyList()))
    }

    @Test
    fun autoscrollOnlyNearBottom() {
        assertEquals(true, shouldAutoscrollToNewest(0, 0))
        assertEquals(false, shouldAutoscrollToNewest(1, 0))
        assertEquals(true, shouldAutoscrollToNewest(0, 40))
        assertEquals(false, shouldAutoscrollToNewest(0, 80))
        assertEquals(false, shouldAutoscrollToNewest(8, 0))
        assertEquals(false, shouldAutoscrollToNewest(2, 0))
    }

    @Test
    fun followIncomingOnlyWhenNewestArrivesAtBottom() {
        assertEquals(
            true,
            shouldFollowIncomingNewest(
                followBottom = true,
                scrolling = false,
                newestArrived = true,
                olderPageGrew = false,
            ),
        )
        assertEquals(
            false,
            shouldFollowIncomingNewest(
                followBottom = true,
                scrolling = true,
                newestArrived = true,
                olderPageGrew = false,
            ),
        )
        assertEquals(
            false,
            shouldFollowIncomingNewest(
                followBottom = true,
                scrolling = false,
                newestArrived = false,
                olderPageGrew = true,
            ),
        )
        assertEquals(
            false,
            shouldFollowIncomingNewest(
                followBottom = false,
                scrolling = false,
                newestArrived = true,
                olderPageGrew = false,
            ),
        )
    }

    @Test
    fun followBottomSurvivesContentInsertWithoutUserScroll() {
        assertEquals(
            true,
            followBottomFromScroll(
                currentlyFollowing = true,
                scrolling = false,
                firstVisibleIndex = 1,
                firstVisibleOffset = 0,
            ),
        )
        assertEquals(
            false,
            followBottomFromScroll(
                currentlyFollowing = true,
                scrolling = true,
                firstVisibleIndex = 1,
                firstVisibleOffset = 0,
            ),
        )
        assertEquals(
            true,
            followBottomFromScroll(
                currentlyFollowing = false,
                scrolling = false,
                firstVisibleIndex = 0,
                firstVisibleOffset = 0,
            ),
        )
        assertEquals(
            false,
            followBottomFromScroll(
                currentlyFollowing = false,
                scrolling = false,
                firstVisibleIndex = 4,
                firstVisibleOffset = 0,
            ),
        )
    }

    @Test
    fun pageNewerWhenHasGapNearNewest() {
        assertEquals(true, shouldPageNewer(0, hasNewer = true, loadingNewer = false))
        assertEquals(false, shouldPageNewer(0, hasNewer = false, loadingNewer = false))
        assertEquals(false, shouldPageNewer(12, hasNewer = true, loadingNewer = false))
    }

    @Test
    fun pageOlderNearOldest() {
        assertEquals(
            true,
            shouldPageOlder(18, size = 20, hasOlder = true, loadingOlder = false, firstVisibleIndex = 4),
        )
        assertEquals(
            false,
            shouldPageOlder(18, size = 20, hasOlder = false, loadingOlder = false, firstVisibleIndex = 4),
        )
        assertEquals(
            false,
            shouldPageOlder(18, size = 20, hasOlder = true, loadingOlder = true, firstVisibleIndex = 4),
        )
        assertEquals(
            false,
            shouldPageOlder(9, size = 10, hasOlder = true, loadingOlder = false, firstVisibleIndex = 0),
        )
        assertEquals(
            false,
            shouldPageOlder(48, size = 50, hasOlder = true, loadingOlder = false, firstVisibleIndex = 0),
        )
        assertEquals(32, HISTORY_FIRST_LIMIT)
        assertEquals(40, HISTORY_PAGE_LIMIT)
        assertEquals(true, historyHasMore(40))
        assertEquals(false, historyHasMore(39))
        assertEquals(true, atHistoryOldest(1))
        assertEquals(false, atHistoryOldest(400))
    }

    @Test
    fun jumpReplacesHistoryWindow() {
        val current = listOf(msg(30), msg(20), msg(10))
        val aroundPin = listOf(msg(12), msg(11), msg(10), msg(9))
        val replaced = mergeOrReplaceMessages(current, aroundPin, replace = true)
        assertEquals(listOf(12, 11, 10, 9), replaced.map { it.id.id })
        val merged = mergeOrReplaceMessages(current, aroundPin, replace = false)
        assertEquals(listOf(12, 11, 10, 9, 30, 20, 10), merged.map { it.id.id })
    }

    @Test
    fun historyReplaceKeepsLocalPending() {
        val pending = msg(-3, outgoing = true).copy(pending = true, text = "unsent")
        val current = listOf(pending, msg(30), msg(20))
        val replaced = mergeOrReplaceMessages(current, listOf(msg(31), msg(30)), replace = true)
        assertEquals(listOf(-3, 31, 30), replaced.map { it.id.id })
        assertEquals(true, replaced.first().pending)
    }

    @Test
    fun liveEdgeKeepsCachedOlderThanTheFetchedWindow() {
        val cached = (1..20).map { msg(it) }.reversed()
        val incoming = (11..20).map { msg(it) }.reversed()
        val merged = mergeLiveEdgeMessages(cached, incoming)
        assertEquals((20 downTo 1).toList(), merged.map { it.id.id })
    }

    @Test
    fun liveEdgeDropsCachedRowsInsideTheFetchedWindow() {
        val cached = listOf(msg(22), msg(20), msg(19), msg(10))
        val incoming = listOf(msg(21), msg(20), msg(19))
        val merged = mergeLiveEdgeMessages(cached, incoming)
        assertEquals(listOf(21, 20, 19, 10), merged.map { it.id.id })
    }

    @Test
    fun paintHistoryWindowKeepsNewestRows() {
        val messages = (1..20).map { msg(it) }
        val (paint, tail) = paintHistoryWindow(messages, limit = 16)
        assertEquals((20 downTo 5).toList(), paint.map { it.id.id })
        assertEquals((4 downTo 1).toList(), tail.map { it.id.id })
    }

    @Test
    fun paintHistoryWindowDoesNotSplitAnAlbum() {
        val messages = (1..20).map { id ->
            msg(id).copy(groupedId = if (id in 3..6) 99L else null)
        }
        val (paint, tail) = paintHistoryWindow(messages, limit = 16)
        assertEquals((20 downTo 3).toList(), paint.map { it.id.id })
        assertEquals(listOf(2, 1), tail.map { it.id.id })
    }

    @Test
    fun parsePinnedIdsSkipsJunk() {
        assertEquals(listOf(9, 3), parsePinnedIds("9, 3,x,"))
        assertEquals(emptyList<Int>(), parsePinnedIds(null))
        assertEquals(emptyList<Int>(), parsePinnedIds(""))
    }

    @Test
    fun senderTagsRoundTrip() {
        val tags = mapOf(PeerId(7) to "admin", PeerId(8) to "role:bot")
        assertEquals(tags, parseSenderTags(encodeSenderTags(tags)))
        assertEquals(emptyMap<PeerId, String>(), parseSenderTags(null))
    }

    @Test
    fun forumTopicsRoundTrip() {
        val topics = listOf(
            ForumTopic(
                id = 1,
                title = "General",
                iconColor = 12,
                topMessageId = 44,
                date = 9,
                unreadCount = 2,
                pinned = true,
                lastMessagePreview = "hi \"there\"",
            ),
            ForumTopic(id = 3, title = "Dev", closed = true),
        )
        val parsed = parseForumTopics(encodeForumTopics(topics))
        assertEquals(topics.map { it.id }, parsed.map { it.id })
        assertEquals("General", parsed[0].title)
        assertEquals(true, parsed[0].pinned)
        assertEquals("hi \"there\"", parsed[0].lastMessagePreview)
        assertEquals(true, parsed[1].closed)
    }

    @Test
    fun backgroundOlderPrefetchStaysInvisible() {
        assertEquals(
            AppSyncStatus.Hidden,
            dialogSyncStatus(
                loading = false,
                searching = false,
                messagesEmpty = false,
                loadingOlder = true,
                loadingNewer = false,
                prefetchingOlder = true,
            ),
        )
    }

    @Test
    fun scrollTriggeredOlderPageShowsLoadingMore() {
        assertEquals(
            AppSyncStatus.LoadingMore,
            dialogSyncStatus(
                loading = false,
                searching = false,
                messagesEmpty = false,
                loadingOlder = true,
                loadingNewer = false,
                prefetchingOlder = false,
            ),
        )
        assertEquals(
            AppSyncStatus.LoadingMore,
            dialogSyncStatus(
                loading = false,
                searching = false,
                messagesEmpty = false,
                loadingOlder = false,
                loadingNewer = true,
                prefetchingOlder = false,
            ),
        )
    }

    @Test
    fun firstPageAndSearchOutrankOlderPaging() {
        assertEquals(
            AppSyncStatus.Connecting,
            dialogSyncStatus(
                loading = true,
                searching = false,
                messagesEmpty = true,
                loadingOlder = true,
                loadingNewer = false,
                prefetchingOlder = true,
            ),
        )
        assertEquals(
            AppSyncStatus.Syncing,
            dialogSyncStatus(
                loading = true,
                searching = false,
                messagesEmpty = false,
                loadingOlder = false,
                loadingNewer = false,
                prefetchingOlder = false,
            ),
        )
        assertEquals(
            AppSyncStatus.Syncing,
            dialogSyncStatus(
                loading = false,
                searching = true,
                messagesEmpty = false,
                loadingOlder = false,
                loadingNewer = false,
                prefetchingOlder = false,
            ),
        )
        assertEquals(
            AppSyncStatus.Hidden,
            dialogSyncStatus(
                loading = false,
                searching = false,
                messagesEmpty = false,
                loadingOlder = false,
                loadingNewer = false,
                prefetchingOlder = false,
            ),
        )
    }
}
