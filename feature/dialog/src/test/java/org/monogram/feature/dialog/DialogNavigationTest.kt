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
    @Test
    fun jumpToAlbumMemberUsesItsRenderedRow() {
        val messages = listOf(msg(9), msg(8).copy(groupedId = 1), msg(7).copy(groupedId = 1), msg(6))
        assertEquals(1, messageRowIndex(messages, 7))
        assertEquals(2, messageRowIndex(messages, 6))
        assertEquals(-1, messageRowIndex(messages, 5))
    }

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
    fun unreadBadgeHidesUntilARealUnreadMessageIsLoaded() {
        val cached = listOf(msg(10))
        assertEquals(0, visibleUnreadBadgeCount(cached, unreadCount = 4, readInboxMaxId = 10))
        assertEquals(
            4,
            visibleUnreadBadgeCount(
                listOf(msg(14), msg(13), msg(12), msg(11), msg(10)),
                unreadCount = 4,
                readInboxMaxId = 10,
            ),
        )
    }

    @Test
    fun unreadAnchorUsesCountOnlyInsideLiveEdgeWindow() {
        val messages = listOf(msg(30), msg(29), msg(28), msg(27), msg(26))
        assertEquals(
            29,
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
                unreadCount = 6,
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
    fun unreadCountAnchorSkipsOutgoingAndUsesTheWholeLoadedCluster() {
        val messages = listOf(msg(30, outgoing = true), msg(29), msg(28), msg(27))
        assertEquals(29, unreadAnchorId(messages, 1, 0, true, false))
        assertEquals(27, unreadAnchorId(messages, 3, 0, true, false))
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
    fun visualAndNonVisualAlbumIdentification() {
        val photoAlbum = listOf(
            msg(5).copy(groupedId = 1L, mediaKind = "photo", mediaCacheKey = "p1"),
            msg(4).copy(groupedId = 1L, mediaKind = "photo", mediaCacheKey = "p2"),
        )
        val docAlbum = listOf(
            msg(3).copy(groupedId = 2L, mediaKind = "document", fileName = "a.apk"),
            msg(2).copy(groupedId = 2L, mediaKind = "document", fileName = "b.apk"),
        )
        val singleDoc = listOf(
            msg(1).copy(mediaKind = "document", fileName = "c.apk"),
        )
        assertEquals(true, isVisualAlbum(photoAlbum))
        assertEquals(false, isNonVisualAlbum(photoAlbum))

        assertEquals(false, isVisualAlbum(docAlbum))
        assertEquals(true, isNonVisualAlbum(docAlbum))

        assertEquals(false, isVisualAlbum(singleDoc))
        assertEquals(false, isNonVisualAlbum(singleDoc))
    }

    @Test
    fun unreadDividerUsesCountWhenInboxUnknown() {
        val messages = listOf(msg(30), msg(20), msg(10), msg(5), msg(1))
        assertEquals(0, unreadJumpAddOffset(0))
        assertEquals(0, unreadJumpAddOffset(1))
        assertEquals(2, unreadJumpAddOffset(3))
        assertEquals(1, unreadDividerIndex(messages, unreadCount = 2, readInboxMaxId = 0))
    }

    @Test
    fun unreadDividerUsesInboxMaxId() {
        val messages = listOf(msg(30), msg(20), msg(10), msg(5))
        assertEquals(1, unreadDividerIndex(messages, unreadCount = 9, readInboxMaxId = 10))
    }

    @Test
    fun unreadDividerUsesAlbumHeadWhenReadBoundaryFallsInsideAlbum() {
        val messages = listOf(
            msg(30),
            msg(29).copy(groupedId = 1L),
            msg(28).copy(groupedId = 1L),
            msg(27).copy(groupedId = 1L),
            msg(26),
        )

        assertEquals(1, unreadDividerIndex(messages, unreadCount = 0, readInboxMaxId = 27))
    }

    @Test
    fun unreadDividerUsesAlbumHeadWhenWholeAlbumIsUnread() {
        val messages = listOf(
            msg(30).copy(groupedId = 1L),
            msg(29).copy(groupedId = 1L),
            msg(28).copy(groupedId = 1L),
            msg(27),
        )

        assertEquals(0, unreadDividerIndex(messages, unreadCount = 0, readInboxMaxId = 27))
    }

    @Test
    fun unreadDividerCountSkipsOutgoingMessages() {
        val messages = listOf(msg(30, outgoing = true), msg(29), msg(28), msg(27))

        assertEquals(2, unreadDividerIndex(messages, unreadCount = 2, readInboxMaxId = 0))
    }

    @Test
    fun unreadDividerCountUsesEveryIncomingAlbumMember() {
        val messages = listOf(
            msg(30),
            msg(29).copy(groupedId = 1L),
            msg(28).copy(groupedId = 1L),
            msg(27).copy(groupedId = 1L),
            msg(26),
        )

        assertEquals(1, unreadDividerIndex(messages, unreadCount = 4, readInboxMaxId = 0))
    }

    @Test
    fun visibleAlbumMessageIdsExpandsVisibleAlbumRowsAndSkipsInvalidRows() {
        val messages = listOf(
            msg(10),
            msg(9).copy(groupedId = 1L),
            msg(8).copy(groupedId = 1L),
            msg(7),
            msg(6).copy(groupedId = 2L),
            msg(5).copy(groupedId = 2L),
            msg(4).copy(groupedId = 2L),
            msg(3),
        )

        assertEquals(
            setOf(10, 9, 8, 6, 5, 4),
            visibleAlbumMessageIds(messages, visibleRowIndices = setOf(0, 1, 3, -1, 99)),
        )
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
        assertEquals(80, HISTORY_FIRST_LIMIT)
        assertEquals(80, HISTORY_PAGE_LIMIT)
        assertEquals(100, HISTORY_CACHE_LIMIT)
        assertEquals(40, HISTORY_PAINT_LIMIT)
        assertEquals(true, historyHasMore(80))
        assertEquals(false, historyHasMore(79))
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
        assertEquals(listOf(21, 20, 19, 22, 10), merged.map { it.id.id })
    }

    @Test
    fun liveEdgeKeepsRowsNewerThanTheFetchedWindow() {
        val cached = listOf(msg(25), msg(20), msg(19), msg(10))
        val incoming = listOf(msg(21), msg(20), msg(19))
        val merged = mergeLiveEdgeMessages(cached, incoming)
        assertEquals(listOf(21, 20, 19, 25, 10), merged.map { it.id.id })
    }

    @Test
    fun contiguousOlderCacheKeepsAMatchingPage() {
        val window = (161..240).map { msg(it) }
        val cached = (81..160).map { msg(it) }
        val run = contiguousOlderCache(msg(161), cached, window)
        assertEquals((160 downTo 81).toList(), run.map { it.id.id })
    }

    @Test
    fun contiguousOlderCacheRejectsASparseIsland() {
        val window = (161..240).map { msg(it) }
        val cached = (50..60).map { msg(it) }
        assertEquals(emptyList<Int>(), contiguousOlderCache(msg(161), cached, window).map { it.id.id })
    }

    @Test
    fun contiguousOlderCacheRejectsAFifteenDayDateHole() {
        val newest = msg(160).copy(date = 2_000_000)
        val older = (150..159).map { msg(it).copy(date = 2_000_000 - 15L * 86_400L) }
        assertEquals(
            emptyList<Int>(),
            contiguousOlderCache(newest, older, listOf(newest) + older).map { it.id.id },
        )
    }

    @Test
    fun contiguousHistoryFromNewestDropsAnOlderIsland() {
        val recent = (161..240).map { msg(it) }
        val island = (1..40).map { msg(it) }
        assertEquals(
            (240 downTo 161).toList(),
            contiguousHistoryFromNewest(recent + island).map { it.id.id },
        )
    }

    @Test
    fun contiguousNewerCacheRejectsASparseIsland() {
        val window = (61..100).map { msg(it) }
        val cached = (400..440).map { msg(it) }
        assertEquals(
            emptyList<Int>(),
            contiguousNewerCache(msg(100), cached, window).map { it.id.id },
        )
    }

    @Test
    fun dateJumpCacheRejectsAnIslandOlderThanTwoDays() {
        val yesterday = 1_800_000
        val island = (1..40).map { id -> msg(id).copy(date = yesterday - 15L * 86_400L) }
        assertEquals(emptyList<Int>(), dateJumpCacheWindow(island, yesterday).map { it.id.id })
    }

    @Test
    fun dateJumpCacheKeepsTheRequestedDay() {
        val yesterday = 1_800_000
        val page = (81..160).map { id -> msg(id).copy(date = yesterday - 3_600L) }
        assertEquals((160 downTo 81).toList(), dateJumpCacheWindow(page, yesterday).map { it.id.id })
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
                unreadMentionsCount = 1,
                unreadReactionsCount = 3,
                pinned = true,
                lastMessagePreview = "hi \"there\"",
            ),
            ForumTopic(id = 3, title = "Dev", closed = true),
        )
        val parsed = parseForumTopics(encodeForumTopics(topics))
        assertEquals(topics.map { it.id }, parsed.map { it.id })
        assertEquals("General", parsed[0].title)
        assertEquals(1, parsed[0].unreadMentionsCount)
        assertEquals(3, parsed[0].unreadReactionsCount)
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
    fun scrollTriggeredOlderPageHidesLoadingMore() {
        assertEquals(
            AppSyncStatus.Hidden,
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
            AppSyncStatus.Hidden,
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
