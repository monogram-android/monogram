package org.monogram.feature.dialog

import org.monogram.core.models.CompactJson
import org.monogram.core.models.ForumTopic
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.ui.components.AppSyncStatus

/** https://core.telegram.org/api/offsets — `add_offset = unreadCount - 1` for the next unread mention/reaction. */
fun unreadJumpAddOffset(count: Int): Int = (count - 1).coerceAtLeast(0)

/** Newest-first list: unread cluster sits at the bottom; divider is the first older row.
 * The count-index fallback is only valid on the latest window. On a stale window
 * (jumped to pinned/date, newer messages not loaded) the count would point at an
 * arbitrary old row, so no divider is shown until the latest page loads. */

fun unreadDividerIndex(
    messages: List<Message>,
    unreadCount: Int,
    readInboxMaxId: Int,
    hasNewer: Boolean = false,
): Int? {
    if (readInboxMaxId > 0) {
        val byInbox = messages.indexOfLast { !it.outgoing && it.id.id > readInboxMaxId }
        if (byInbox >= 0) return byInbox
        return null
    }
    if (!hasNewer && unreadCount in 1 until messages.size) return unreadCount
    return null
}

/**
 * Oldest unread message id, or null while older history still has to be fetched.
 *
 * With a known read boundary the answer is exact once any message at or below the boundary is
 * loaded. Without one (`readInboxMaxId == 0`) the unread count is the only pointer and it is
 * trusted only while the whole unread cluster sits inside the loaded window.
 */
fun unreadAnchorId(
    messages: List<Message>,
    unreadCount: Int,
    readInboxMaxId: Int,
    hasOlder: Boolean,
    hasNewer: Boolean,
): Int? {
    if (messages.isEmpty()) return null
    if (readInboxMaxId > 0) {
        val index = messages.indexOfLast { !it.outgoing && it.id.id > readInboxMaxId }
        if (index < 0) return null
        val boundaryLoaded = messages.any { it.id.id <= readInboxMaxId }
        if (!boundaryLoaded && hasOlder) return null
        return messages[index].id.id
    }
    if (hasNewer || unreadCount <= 0) return null
    if (unreadCount >= messages.size) return null
    return messages[unreadCount].id.id
}

/**
 * Next scroll position that lifts [anchorIndex] to the top edge of a reversed list.
 *
 * A reversed list aligns a scrolled row to the bottom edge, while Telegram opens a history with
 * the first unread row at the top edge and the unread messages below it. Returns null once the
 * anchor has no rows above it, or when the list is already clamped at the newest row.
 */
fun liftAnchorTarget(visibleIndices: List<Int>, anchorIndex: Int): Int? {
    if (visibleIndices.isEmpty()) return null
    if (visibleIndices.max() <= anchorIndex) return null
    val bottom = visibleIndices.min()
    val target = (anchorIndex - (visibleIndices.size - 1)).coerceAtLeast(0)
    return target.takeIf { it != bottom }
}

/** How long a read receipt waits before it is sent, unless the live edge is on screen. */
const val READ_RECEIPT_DEBOUNCE_MS = 500L

/**
 * Read receipt target for the window the user is looking at.
 *
 * Telegram acknowledges only messages the user actually saw: the newest visible message is the
 * receipt, and the whole page is acknowledged only when its newest message is itself visible (the
 * live edge). A stale window (jumped into history) or an active search never sends a receipt, and
 * a target that is already acknowledged sends nothing.
 */
fun readReceiptTarget(
    newestVisibleId: Int,
    newestLoadedId: Int,
    readInboxMaxId: Int,
    hasNewer: Boolean,
    searching: Boolean,
): Int? {
    if (hasNewer || searching) return null
    if (newestVisibleId <= 0 || newestLoadedId <= 0) return null
    val target = minOf(newestVisibleId, newestLoadedId)
    return target.takeIf { it > readInboxMaxId }
}

fun suppressLiveEdgeRead(
    anchoringUnread: Boolean,
    pendingUnreadAnchorId: Int?,
    newestLoadedId: Int,
    atLiveEdge: Boolean,
): Boolean {
    if (anchoringUnread) return true
    if (!atLiveEdge) return false
    val pending = pendingUnreadAnchorId ?: return false
    return newestLoadedId > pending
}

fun nextPinnedIndex(current: Int, size: Int): Int =
    if (size <= 0) 0 else (current + 1).mod(size)

fun pinnedIndexAfterReload(previousMessageId: Int?, messages: List<Message>): Int {
    if (messages.isEmpty()) return 0
    if (previousMessageId == null) return 0
    val idx = messages.indexOfFirst { it.id.id == previousMessageId }
    return if (idx >= 0) idx else 0
}

internal data class PinnedBarSnapshot(
    val messages: List<Message>,
    val index: Int,
)

fun mergeSenderTags(
    admin: Map<PeerId, String>,
    senders: Map<PeerId, Profile>,
): Map<PeerId, String> {
    val merged = admin.toMutableMap()
    senders.forEach { (id, profile) ->
        if (id !in merged && profile.isBot) {
            merged[id] = "role:bot"
        }
    }
    return merged
}

internal object SenderTagMemory {
    private val byChat = mutableMapOf<Long, Map<PeerId, String>>()

    fun get(chatId: Long): Map<PeerId, String> = byChat[chatId].orEmpty()

    fun put(chatId: Long, tags: Map<PeerId, String>) {
        if (tags.isEmpty()) byChat.remove(chatId) else byChat[chatId] = tags
    }

    fun clear() = byChat.clear()
}

internal object TopicListMemory {
    private val byChat = mutableMapOf<Long, List<ForumTopic>>()

    fun get(chatId: Long): List<ForumTopic> = byChat[chatId].orEmpty()

    fun put(chatId: Long, topics: List<ForumTopic>) {
        if (topics.isEmpty()) byChat.remove(chatId) else byChat[chatId] = topics
    }

    fun clear() = byChat.clear()
}

internal object PinnedBarMemory {
    private val byChat = mutableMapOf<Long, PinnedBarSnapshot>()

    fun get(chatId: Long): PinnedBarSnapshot? = byChat[chatId]

    fun put(chatId: Long, messages: List<Message>, index: Int) {
        if (messages.isEmpty()) {
            byChat.remove(chatId)
        } else {
            byChat[chatId] = PinnedBarSnapshot(
                messages = messages,
                index = index.coerceIn(0, messages.lastIndex),
            )
        }
    }

    fun clear() = byChat.clear()
}

fun pinnedMetaKey(chatId: Long): String = "pinned.$chatId"

fun tagsMetaKey(chatId: Long): String = "tags.$chatId"

fun topicsMetaKey(chatId: Long): String = "topics.$chatId"

fun parsePinnedIds(raw: String?): List<Int> =
    raw?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()

fun encodeSenderTags(tags: Map<PeerId, String>): String {
    val body = tags.entries.joinToString(",") { (id, tag) ->
        "\"${id.value}\":\"${CompactJson.escape(tag)}\""
    }
    return "{$body}"
}

fun parseSenderTags(raw: String?): Map<PeerId, String> {
    val obj = CompactJson.parse(raw.orEmpty()) as? Map<*, *> ?: return emptyMap()
    val out = LinkedHashMap<PeerId, String>()
    obj.forEach { (key, value) ->
        val id = key.toString().toLongOrNull() ?: return@forEach
        val tag = value as? String ?: return@forEach
        if (tag.isNotEmpty()) out[PeerId(id)] = tag
    }
    return out
}

fun encodeForumTopics(topics: List<ForumTopic>): String {
    val rows = topics.joinToString(",") { topic ->
        buildString {
            append("{\"id\":").append(topic.id)
            append(",\"title\":\"").append(CompactJson.escape(topic.title)).append('"')
            append(",\"iconColor\":").append(topic.iconColor)
            topic.iconEmojiId?.let { append(",\"iconEmojiId\":").append(it) }
            append(",\"top\":").append(topic.topMessageId)
            append(",\"date\":").append(topic.date)
            append(",\"unread\":").append(topic.unreadCount)
            append(",\"mentions\":").append(topic.unreadMentionsCount)
            append(",\"reactions\":").append(topic.unreadReactionsCount)
            append(",\"readInbox\":").append(topic.readInboxMaxId)
            if (topic.pinned) append(",\"pinned\":true")
            if (topic.closed) append(",\"closed\":true")
            if (topic.hidden) append(",\"hidden\":true")
            if (topic.short) append(",\"short\":true")
            if (topic.deleted) append(",\"deleted\":true")
            topic.lastMessagePreview?.takeIf { it.isNotEmpty() }?.let {
                append(",\"preview\":\"").append(CompactJson.escape(it)).append('"')
            }
            append('}')
        }
    }
    return "[$rows]"
}

fun parseForumTopics(raw: String?): List<ForumTopic> {
    val rows = CompactJson.parse(raw.orEmpty()) as? List<*> ?: return emptyList()
    return rows.mapNotNull { row ->
        val obj = row as? Map<*, *> ?: return@mapNotNull null
        val id = obj.jsonInt("id") ?: return@mapNotNull null
        ForumTopic(
            id = id,
            title = obj["title"] as? String ?: "",
            iconColor = obj.jsonInt("iconColor") ?: 0,
            iconEmojiId = obj.jsonLong("iconEmojiId"),
            topMessageId = obj.jsonInt("top") ?: 0,
            date = obj.jsonInt("date") ?: 0,
            unreadCount = obj.jsonInt("unread") ?: 0,
            unreadMentionsCount = obj.jsonInt("mentions") ?: 0,
            unreadReactionsCount = obj.jsonInt("reactions") ?: 0,
            readInboxMaxId = obj.jsonInt("readInbox") ?: 0,
            pinned = obj["pinned"] == true,
            closed = obj["closed"] == true,
            hidden = obj["hidden"] == true,
            short = obj["short"] == true,
            deleted = obj["deleted"] == true,
            lastMessagePreview = obj["preview"] as? String,
        )
    }
}

private fun Map<*, *>.jsonInt(key: String): Int? = when (val value = this[key]) {
    is Int -> value
    is Long -> value.toInt()
    is Double -> value.toInt()
    is String -> value.toIntOrNull()
    else -> null
}

private fun Map<*, *>.jsonLong(key: String): Long? = when (val value = this[key]) {
    is Long -> value
    is Int -> value.toLong()
    is Double -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}

fun shouldAutoscrollToNewest(firstVisibleIndex: Int, firstVisibleOffset: Int): Boolean =
    firstVisibleIndex == 0 && firstVisibleOffset < 80

/**
 * reverseLayout prepends the newest row at index 0. That shifts the previously
 * visible newest to index 1 before we can scroll, so do not re-check the current
 * index here; [followBottom] already captured that the user was on the latest.
 */
fun shouldFollowIncomingNewest(
    followBottom: Boolean,
    scrolling: Boolean,
    newestArrived: Boolean,
    olderPageGrew: Boolean,
): Boolean = followBottom && !scrolling && newestArrived && !olderPageGrew

/** Content inserts can bump [firstVisibleIndex] without a user gesture. Only drop
 * follow-bottom while a scroll is in progress; restore it when the latest row is
 * settled on screen. */
fun followBottomFromScroll(
    currentlyFollowing: Boolean,
    scrolling: Boolean,
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
): Boolean {
    if (scrolling) return shouldAutoscrollToNewest(firstVisibleIndex, firstVisibleOffset)
    if (shouldAutoscrollToNewest(firstVisibleIndex, firstVisibleOffset)) return true
    return currentlyFollowing
}

const val HISTORY_PAGE_LIMIT = 40
const val HISTORY_FIRST_LIMIT = 32
const val HISTORY_PAINT_LIMIT = 16

fun historyHasMore(pageSize: Int, limit: Int = HISTORY_PAGE_LIMIT): Boolean =
    pageSize >= limit

fun paintHistoryWindow(
    messages: List<Message>,
    limit: Int = HISTORY_PAINT_LIMIT,
): Pair<List<Message>, List<Message>> {
    val ordered = historyOrder(messages)
    if (ordered.size <= limit) return ordered to emptyList()
    var end = limit
    val group = ordered.getOrNull(end - 1)?.groupedId
    if (group != null) {
        while (end < ordered.size && ordered[end].groupedId == group) {
            end += 1
        }
    }
    return ordered.take(end) to ordered.drop(end)
}

fun atHistoryOldest(oldestMessageId: Int): Boolean = oldestMessageId <= 1

fun shouldPageOlder(
    lastVisibleIndex: Int,
    size: Int,
    hasOlder: Boolean,
    loadingOlder: Boolean,
    firstVisibleIndex: Int = 0,
): Boolean {
    if (!hasOlder || loadingOlder || size <= 0) return false
    if (lastVisibleIndex < size - 10) return false
    // reverseLayout: firstVisibleItemIndex 0 means the newest row is on screen
    // (visual bottom). Never page older from the latest edge / FAB.
    if (firstVisibleIndex == 0) return false
    return true
}

fun shouldPageNewer(
    firstVisibleIndex: Int,
    hasNewer: Boolean,
    loadingNewer: Boolean,
): Boolean = hasNewer && !loadingNewer && firstVisibleIndex <= 3

fun applyMessageEdit(messages: List<Message>, edited: Message): List<Message> =
    messages.map { current ->
        if (current.id != edited.id) {
            current
        } else {
            val reactionOnly = current.text == edited.text &&
                current.mediaKind == edited.mediaKind &&
                current.mediaCacheKey == edited.mediaCacheKey &&
                current.reactionsJson != edited.reactionsJson &&
                current.editDate == null
            edited.copy(
                pending = current.pending,
                read = current.read,
                failed = current.failed,
                editDate = if (reactionOnly) null else (edited.editDate ?: current.editDate),
            )
        }
    }

fun removeDeletedMessages(messages: List<Message>, ids: Set<Int>): List<Message> =
    messages.filterNot { it.id.id in ids }

/** True when the target is outside the loaded window and needs a history fetch. */
fun jumpNeedsFetch(messages: List<Message>, targetId: Int): Boolean =
    messages.none { it.id.id == targetId }

fun historyPagingAllowed(searchQuery: String): Boolean = searchQuery.isBlank()

/**
 * Header sync state for an open history.
 *
 * The automatic older-page prefetch keeps [prefetchingOlder] set but stays invisible:
 * it is background work triggered by opening the chat, not by scrolling, so it must not
 * replace the peer status subtitle or flash a progress bar.
 */
fun dialogSyncStatus(
    loading: Boolean,
    searching: Boolean,
    messagesEmpty: Boolean,
    loadingOlder: Boolean,
    loadingNewer: Boolean,
    prefetchingOlder: Boolean,
): AppSyncStatus = when {
    loading && messagesEmpty -> AppSyncStatus.Connecting
    loading || searching -> AppSyncStatus.Syncing
    (loadingOlder && !prefetchingOlder) || loadingNewer -> AppSyncStatus.LoadingMore
    else -> AppSyncStatus.Hidden
}

const val SEARCH_DEBOUNCE_MS = 300L

fun mergeOrReplaceMessages(
    current: List<Message>,
    incoming: List<Message>,
    replace: Boolean,
): List<Message> {
    val merged = if (replace || current.isEmpty()) incoming else incoming + current
    val incomingIds = merged.mapTo(HashSet()) { it.id.id }
    val local = current.filter { message ->
        (message.pending || message.failed || message.id.id <= 0) && message.id.id !in incomingIds
    }
    return if (local.isEmpty()) merged else local + merged
}

fun mergeLiveEdgeMessages(current: List<Message>, incoming: List<Message>): List<Message> {
    if (incoming.isEmpty()) return current
    if (current.isEmpty()) return incoming
    val minIncoming = incoming.minOf { it.id.id }
    val incomingIds = incoming.mapTo(HashSet()) { it.id.id }
    val keep = current.filter { message ->
        message.id.id < minIncoming ||
            ((message.pending || message.failed || message.id.id <= 0) && message.id.id !in incomingIds)
    }
    return incoming + keep
}

fun historyOrder(messages: List<Message>): List<Message> =
    messages.sortedWith(
        compareByDescending<Message> { it.pending || it.id.id <= 0 }
            .thenByDescending { it.id.id },
    )

/** Newest-first: first row of an album is the newest member. */
fun isAlbumHead(messages: List<Message>, index: Int): Boolean {
    val group = messages.getOrNull(index)?.groupedId ?: return true
    return messages.getOrNull(index - 1)?.groupedId != group
}

fun albumSlice(messages: List<Message>, start: Int): List<Message> {
    val head = messages.getOrNull(start) ?: return emptyList()
    val group = head.groupedId ?: return listOf(head)
    val out = ArrayList<Message>()
    var i = start
    while (i < messages.size && messages[i].groupedId == group) {
        out += messages[i]
        i += 1
    }
    return out
}

/** A grouped message shares its rendered row with the album head. */
fun messageRowIndex(messages: List<Message>, messageId: Int): Int {
    val index = messages.indexOfFirst { it.id.id == messageId }
    if (index < 0) return -1
    return (0..index).count { isAlbumHead(messages, it) } - 1
}

private val ALBUM_VISUAL_KINDS = setOf("photo", "video", "gif")

fun isVisualAlbum(album: List<Message>): Boolean =
    album.size > 1 && album.any { it.mediaKind in ALBUM_VISUAL_KINDS }

fun isNonVisualAlbum(album: List<Message>): Boolean =
    album.size > 1 && !isVisualAlbum(album)

/** Newest-first slice -> send order (oldest first) for Telegram mosaic cells. */
fun albumVisualItems(album: List<Message>): List<Message> {
    val visual = album.asReversed().filter { message ->
        message.mediaKind in ALBUM_VISUAL_KINDS && !message.mediaCacheKey.isNullOrBlank()
    }
    return visual.ifEmpty { album.asReversed() }
}
