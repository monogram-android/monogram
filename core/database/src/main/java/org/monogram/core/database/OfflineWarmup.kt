package org.monogram.core.database

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.monogram.core.common.AppLog
import org.monogram.core.common.PerfLog
import org.monogram.core.database.dao.ChatReadState
import org.monogram.core.database.entity.MessageHoleEntity
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.chatListPreviewSource
import org.monogram.core.models.isPlaceholderPeerTitle
import org.monogram.core.models.mergeLocalCache

/**
 * Reads cached dialogs/folders/messages for UI hydration before network sync.
 *
 * Auth keys / api_hash are intentionally NOT stored in Room plaintext.
 * Session secrets belong in encrypted storage (follow-up).
 */
open class OfflineWarmup(
    private val db: MonogramDatabase? = null,
) {
    private val cleanupMutex = Mutex()
    @Volatile
    private var firstPaintSnapshot: OfflineWarmupSnapshot? = null

    fun startupSnapshot(): OfflineWarmupSnapshot? = firstPaintSnapshot

    fun takeStartupSnapshot(): OfflineWarmupSnapshot? = synchronized(this) {
        firstPaintSnapshot.also { firstPaintSnapshot = null }
    }

    suspend fun prepareStartupSnapshot(sessionStore: SessionMetadataStore) {
        ensureStartupCleanup()
        val started = PerfLog.nowMs()
        val chats = chatsWindow(40)
        val selfId = sessionStore.readAuthorizedUserId()
        val snapshot = OfflineWarmupSnapshot(
            chats = chats,
            folders = folders(),
            self = selfId?.let { sessionStore.readProfile(it.value) },
            mainCount = mainListCount(),
        )
        synchronized(this) { firstPaintSnapshot = snapshot }
        PerfLog.mark("cache:startupSnapshot", PerfLog.nowMs() - started, "count=${chats.size}")
    }

    open suspend fun chatsAfter(cursor: ChatCacheCursor, limit: Int): List<Chat> = roomIo {
        db?.chatDao()?.afterCursor(
            cursor.archived, cursor.pinned, cursor.pinnedOrder,
            cursor.lastMessageDate, cursor.id, limit
        )?.map { it.toModel() }.orEmpty()
    }

    /** Callers use this to skip redundant dispatcher hops for in-memory cache adapters. */
    open val usesIoDispatcher: Boolean get() = db != null

    private suspend inline fun <T> roomIo(crossinline block: suspend () -> T): T =
        if (db == null) block() else withContext(Dispatchers.IO) { block() }

    @Volatile
    private var startupCleanupDone = false

    /**
     * Drops rows left by process death before the first warmup read returns.
     */
    suspend fun ensureStartupCleanup() {
        if (startupCleanupDone) return
        cleanupMutex.withLock {
            if (startupCleanupDone) return@withLock
            val started = PerfLog.nowMs()
            try {
                dropUnsentAfterRestart()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                AppLog.warn("warmup", "startup cleanup failed")
            }
            startupCleanupDone = true
            PerfLog.mark("cache:startupCleanup", PerfLog.nowMs() - started)
        }
    }

    open fun observeReadStates(): Flow<List<ChatReadState>> =
        db?.chatDao()?.observeReadStates() ?: emptyFlow()

    open suspend fun draft(chatId: PeerId, threadId: Int = 0): String = roomIo {
        db?.metaDao()?.get(draftMetaKey(chatId.value, threadId))?.value.orEmpty()
    }

    open suspend fun setDraft(chatId: PeerId, threadId: Int = 0, text: String) = roomIo {
        val database = db ?: return@roomIo
        val key = draftMetaKey(chatId.value, threadId)
        if (text.isEmpty()) {
            database.metaDao().delete(key)
        } else {
            database.metaDao().upsert(
                org.monogram.core.database.entity.MetaEntity(key, text),
            )
        }
    }

    open suspend fun discussionReadMax(chatId: PeerId, topId: Int): Int = roomIo {
        db?.metaDao()?.get("discussion_read:${chatId.value}:$topId")?.value?.toIntOrNull() ?: 0
    }

    open suspend fun applyDiscussionRead(chatId: PeerId, topId: Int, maxId: Int) = roomIo {
        val database = db ?: return@roomIo
        database.withTransaction {
            val confirmed = maxOf(
                database.metaDao()
                    .get("discussion_read:${chatId.value}:$topId")
                    ?.value
                    ?.toIntOrNull() ?: 0,
                maxId,
            )
            database.metaDao().upsert(
                org.monogram.core.database.entity.MetaEntity(
                    "discussion_read:${chatId.value}:$topId",
                    confirmed.toString(),
                ),
            )
        }
    }

    open suspend fun applyOutboxRead(chatId: PeerId, maxId: Int) = roomIo {
        db?.chatDao()?.updateOutboxRead(chatId.value, maxId)
    }

    open suspend fun applyReactions(chatId: PeerId, messageId: Int, json: String) = roomIo {
        db?.messageDao()?.updateReactions(chatId.value, messageId, json)
    }

    open suspend fun applyUnreadMentions(chatId: PeerId, stillUnread: Int) = roomIo {
        db?.chatDao()?.updateUnreadMentions(chatId.value, stillUnread.coerceAtLeast(0))
    }

    open suspend fun applyUnreadReactions(chatId: PeerId, stillUnread: Int) = roomIo {
        db?.chatDao()?.updateUnreadReactions(chatId.value, stillUnread.coerceAtLeast(0))
    }

    open suspend fun addUnreadMentions(chatId: PeerId, delta: Int) = roomIo {
        db?.chatDao()?.addUnreadMentions(chatId.value, delta)
    }

    open suspend fun addUnreadReactions(chatId: PeerId, delta: Int) = roomIo {
        db?.chatDao()?.addUnreadReactions(chatId.value, delta)
    }

    open suspend fun applyMessageEdit(message: Message) = roomIo {
        val database = db ?: return@roomIo
        database.withTransaction {
            database.messageDao().upsertAll(listOf(message.toEntity()))
            refreshPreview(message.id.chatId, message.id.id)
        }
    }

    private suspend fun refreshPreview(chatId: PeerId, changedId: Int) {
        val database = db ?: return
        val chat = database.chatDao().get(chatId.value)?.toModel() ?: return
        if (chat.lastMessageId != changedId) return
        refreshLatestPreview(chatId)
    }

    private suspend fun refreshLatestPreview(chatId: PeerId) {
        val database = db ?: return
        val chat = database.chatDao().get(chatId.value)?.toModel() ?: return
        val latest = database.messageDao().latestForChat(chatId.value, 1).firstOrNull()?.toModel()
        database.chatDao().upsertAll(
            listOf(
                chat.copy(
                    lastMessageId = latest?.id?.id ?: 0,
                    lastMessagePreview = latest?.chatListPreviewSource(),
                    lastMessageDate = latest?.date,
                    lastMessageOutgoing = latest?.outgoing ?: false,
                    lastMessageSenderName = latest?.senderName,
                    lastMessageMediaKind = latest?.mediaKind,
                    lastMediaThumbCacheKey = latest?.thumbCacheKey,
                ).toEntity(),
            ),
        )
    }

    open suspend fun chats(): List<Chat> {
        val database = db ?: run {
            ensureStartupCleanup()
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            ensureStartupCleanup()
            database.chatDao().observeAll().map { it.toModel() }
        }
    }

    open suspend fun chatsWindow(limit: Int, archiveLimit: Int = 3): List<Chat> {
        val database = db ?: run {
            ensureStartupCleanup()
            return chats()
        }
        return withContext(Dispatchers.IO) {
            ensureStartupCleanup()
            val main = database.chatDao().mainListPage(limit, 0)
            val archived = database.chatDao().archivePreview(archiveLimit)
            (main + archived).distinctBy { it.id }.map { it.toModel() }
        }
    }

    open suspend fun chatsPage(offset: Int, limit: Int): List<Chat> {
        val database = db ?: run {
            ensureStartupCleanup()
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            ensureStartupCleanup()
            database.chatDao().mainListPage(limit, offset).map { it.toModel() }
        }
    }

    open suspend fun chatsExcluding(excludeIds: List<Long>, limit: Int): List<Chat> {
        val database = db ?: run {
            ensureStartupCleanup()
            return emptyList()
        }
        if (excludeIds.isEmpty()) return chatsWindow(limit, archiveLimit = 0)
        return withContext(Dispatchers.IO) {
            ensureStartupCleanup()
            database.chatDao().mainListExcluding(excludeIds, limit).map { it.toModel() }
        }
    }

    open suspend fun chatsArchiveExcluding(excludeIds: List<Long>, limit: Int): List<Chat> {
        val database = db ?: run {
            ensureStartupCleanup()
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            ensureStartupCleanup()
            if (excludeIds.isEmpty()) {
                database.chatDao().archivePreview(limit).map { it.toModel() }
            } else {
                database.chatDao().archiveExcluding(excludeIds, limit).map { it.toModel() }
            }
        }
    }

    open suspend fun mainListCount(): Int {
        val database = db ?: run {
            ensureStartupCleanup()
            return 0
        }
        return withContext(Dispatchers.IO) {
            ensureStartupCleanup()
            database.chatDao().mainListCount()
        }
    }

    open suspend fun chat(chatId: PeerId): Chat? {
        val database = db ?: run {
            ensureStartupCleanup()
            return chats().firstOrNull { it.id == chatId }
        }
        return withContext(Dispatchers.IO) {
            ensureStartupCleanup()
            database.chatDao().get(chatId.value)?.toModel()
        }
    }

    open suspend fun folders(): List<Folder> {
        val database = db ?: run {
            ensureStartupCleanup()
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            ensureStartupCleanup()
            database.folderDao().observeAll().map { it.toModel() }
        }
    }

    /** Drops stuck local rows with no random_id. Pending sends with a random_id wait for updateMessageID. */
    open suspend fun dropUnsentAfterRestart() {
        val database = db ?: return
        roomIo {
            database.withTransaction {
                val chatIds = database.messageDao().unsentChatIds()
                if (chatIds.isEmpty()) return@withTransaction
                database.messageDao().deleteUnsent()
                chatIds.forEach { refreshLatestPreview(PeerId(it)) }
            }
        }
    }

    open suspend fun messages(chatId: PeerId, limit: Int = HISTORY_CACHE_WINDOW): List<Message> {
        val database = db ?: run {
            ensureStartupCleanup()
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            ensureStartupCleanup()
            val pending = database.messageDao().pendingForChat(chatId.value).map { it.toModel() }
            val latest = database.messageDao().latestForChat(chatId.value, limit).map { it.toModel() }
            pending + latest
        }
    }

    open suspend fun messagesByIds(chatId: PeerId, ids: List<Int>): List<Message> {
        if (ids.isEmpty()) return emptyList()
        val database = db ?: run {
            ensureStartupCleanup()
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            ensureStartupCleanup()
            val holes =
                database.messageHoleDao().forChat(chatId.value, MessageHoleEntity.SOURCE_HISTORY)
            if (ids.any { id -> holes.any { id in it.startId..it.endId } }) return@withContext emptyList()
            val rows = database.messageDao().byIds(chatId.value, ids).map { it.toModel() }
            val byId = rows.associateBy { it.id.id }
            ids.mapNotNull { byId[it] }
        }
    }

    open suspend fun olderMessages(chatId: PeerId, beforeId: Int, limit: Int = HISTORY_CACHE_WINDOW): List<Message> {
        val database = db ?: run {
            ensureStartupCleanup()
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            ensureStartupCleanup()
            val rows = database.messageDao().olderThan(chatId.value, beforeId, limit)
            if (rows.isEmpty()) return@withContext emptyList()
            if (database.messageHoleDao().overlaps(
                    chatId.value, rows.minOf { it.id }, beforeId - 1,
                    MessageHoleEntity.SOURCE_HISTORY
                )
            ) return@withContext emptyList()
            rows.map { it.toModel() }
        }
    }

    open suspend fun newerMessages(chatId: PeerId, afterId: Int, limit: Int = HISTORY_CACHE_WINDOW): List<Message> {
        val database = db ?: run {
            ensureStartupCleanup()
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            ensureStartupCleanup()
            val rows = database.messageDao().newerThan(chatId.value, afterId, limit)
            if (rows.isEmpty()) return@withContext emptyList()
            if (database.messageHoleDao().overlaps(
                    chatId.value, afterId + 1, rows.maxOf { it.id },
                    MessageHoleEntity.SOURCE_HISTORY
                )
            ) return@withContext emptyList()
            rows.map { it.toModel() }
        }
    }

    open suspend fun hasHistoryHole(chatId: PeerId, startId: Int, endId: Int): Boolean = roomIo {
        db?.messageHoleDao()
            ?.overlaps(chatId.value, startId, endId, MessageHoleEntity.SOURCE_HISTORY) ?: false
    }

    open suspend fun cacheHistoryPage(
        chatId: PeerId,
        messages: List<Message>,
        reachesNewest: Boolean = false,
        reachesOldest: Boolean = false,
        olderBoundary: Int? = null,
    ) = roomIo {
        val database = db
        if (database == null) {
            upsertMessages(messages)
            return@roomIo
        }
        database.withTransaction {
            val metaKey = "history_coverage:${chatId.value}"
            if (database.metaDao().get(metaKey) == null) {
                markHistoryHole(chatId, 1, Int.MAX_VALUE)
                database.metaDao()
                    .upsert(org.monogram.core.database.entity.MetaEntity(metaKey, "1"))
            }
            upsertMessages(messages)
            val ids = messages.filter { !it.pending && it.id.id > 0 }.map { it.id.id }
            val min = if (reachesOldest) 1 else ids.minOrNull()
            val max =
                if (reachesNewest) Int.MAX_VALUE else olderBoundary?.minus(1) ?: ids.maxOrNull()
            if (min != null && max != null && min <= max) closeHistoryHole(chatId, min, max)
        }
    }

    open suspend fun markHistoryHole(chatId: PeerId, startId: Int, endId: Int) = roomIo {
        if (startId > endId) return@roomIo
        val database = db ?: return@roomIo
        database.withTransaction {
            val dao = database.messageHoleDao()
            val holes = mergeHistoryHoles(
                dao.forChat(chatId.value, MessageHoleEntity.SOURCE_HISTORY) +
                        MessageHoleEntity(chatId.value, startId, endId)
            )
            dao.clearChat(chatId.value, MessageHoleEntity.SOURCE_HISTORY)
            holes.forEach { dao.insert(it) }
        }
    }

    open suspend fun closeHistoryHole(chatId: PeerId, startId: Int, endId: Int) = roomIo {
        if (startId > endId) return@roomIo
        val database = db ?: return@roomIo
        database.withTransaction {
            val dao = database.messageHoleDao()
            val holes = dao.forChat(chatId.value, MessageHoleEntity.SOURCE_HISTORY)
                .flatMap { subtractHistoryRange(it, startId, endId) }
            dao.clearChat(chatId.value, MessageHoleEntity.SOURCE_HISTORY)
            holes.forEach { dao.insert(it) }
        }
    }

    open suspend fun messagesBeforeDate(
        chatId: PeerId,
        beforeDate: Int,
        limit: Int = HISTORY_CACHE_WINDOW,
    ): List<Message> {
        val database = db ?: run {
            ensureStartupCleanup()
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            ensureStartupCleanup()
            database.messageDao()
                .atOrBeforeDate(chatId.value, beforeDate.toLong(), limit)
                .map { it.toModel() }
        }
    }

    open suspend fun replaceChats(chats: List<Chat>) = upsertChats(chats)

    open suspend fun applyProfileToChat(profile: Profile) {
        val stored = roomIo { db?.chatDao()?.get(profile.id.value)?.toModel() } ?: return
        val title = profile.title.takeIf { !isPlaceholderPeerTitle(it, profile.id.value) }
            ?: stored.title
        val updated = stored.copy(
            title = title,
            isChannel = profile.kind == "channel",
            isGroup = profile.kind == "group" || profile.kind == "chat",
            photoCacheKey = profile.avatarCacheKey ?: stored.photoCacheKey,
            peerStatus = profile.status ?: stored.peerStatus,
            peerStatusAt = profile.statusAt ?: stored.peerStatusAt,
            emojiStatusDocumentId = profile.emojiStatusDocumentId ?: stored.emojiStatusDocumentId,
        )
        upsertChats(listOf(updated))
    }

    open suspend fun upsertChats(chats: List<Chat>) {
        val database = db ?: return
        if (chats.isEmpty()) return
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val stored = database.chatDao().getByIds(chats.map { it.id.value }).associateBy { it.id }
                database.chatDao().upsertAll(
                    chats.map { chat ->
                        chat.mergeLocalCache(stored[chat.id.value]?.toModel()).toEntity()
                    },
                )
            }
        }
    }

    open suspend fun replaceFolders(folders: List<Folder>) = roomIo {
        val database = db ?: return@roomIo
        // List order is the folder order, so the index has to land in the row.
        val rows = folders.mapIndexed { index, folder -> folder.toEntity(position = index) }
        database.withTransaction {
            database.folderDao().clear()
            database.folderDao().upsertAll(rows)
        }
    }

    open suspend fun replaceMessages(chatId: PeerId, messages: List<Message>) = roomIo {
        val database = db ?: return@roomIo
        database.messageDao().clearChat(chatId.value)
        if (messages.isNotEmpty()) {
            database.messageDao().upsertAll(messages.map { it.toEntity() })
        }
    }

    open suspend fun upsertMessages(messages: List<Message>) = roomIo {
        if (messages.isEmpty()) return@roomIo
        db?.messageDao()?.upsertAll(messages.map { it.toEntity() })
    }

    open suspend fun deleteMessage(chatId: PeerId, messageId: Int) = roomIo {
        db?.messageDao()?.delete(chatId.value, messageId)
    }

    open suspend fun deleteMessages(chatId: PeerId?, messageIds: Collection<Int>) = roomIo {
        val database = db ?: return@roomIo
        val ids = messageIds.toList()
        if (ids.isEmpty()) return@roomIo
        database.withTransaction {
            // DAO read, not `chats()`: a gated read here would wait on the startup-cleanup mutex
            // while this transaction is open and then open a nested one.
            val affected =
                database.chatDao().withLastMessageIds(chatId?.value, ids).map { it.toModel() }
            if (chatId != null) {
                database.messageDao().deleteInChat(chatId.value, ids)
            } else {
                database.messageDao().deleteUserSpaceIds(ids)
            }
            affected.forEach { refreshPreview(it.id, it.lastMessageId) }
        }
    }

    open suspend fun pruneAheadOfLastMessage(chats: List<Chat>): List<HistoryPrune> {
        val database = db ?: return emptyList()
        return withContext(Dispatchers.IO) {
            val out = mutableListOf<HistoryPrune>()
            for (chat in chats) {
                if (chat.lastMessageId <= 0) continue
                val dropped = database.messageDao().idsAfter(chat.id.value, chat.lastMessageId)
                if (dropped.isEmpty()) continue
                database.messageDao().deleteAfter(chat.id.value, chat.lastMessageId)
                out += HistoryPrune(
                    chatId = chat.id.value,
                    lastMessageId = chat.lastMessageId,
                    cachedMaxId = dropped.max(),
                    droppedIds = dropped,
                )
            }
            out
        }
    }

    /** Drops cached rows in the latest window that the server no longer returns. */
    open suspend fun pruneMissingLatest(chatId: PeerId, fetched: List<Message>) = roomIo {
        val database = db ?: return@roomIo
        if (fetched.isEmpty()) return@roomIo
        val keep = fetched.map { it.id.id }
        val minId = keep.minOrNull() ?: return@roomIo
        val stale = staleIdsInsideFetchedWindow(
            cachedIds = database.messageDao().idsAtOrAfter(chatId.value, minId),
            fetchedIds = keep,
        )
        if (stale.isNotEmpty()) {
            database.messageDao().deleteInChat(chatId.value, stale)
        }
    }

    open suspend fun applyIncomingMessage(message: Message) = roomIo {
        val database = db ?: return@roomIo
        database.withTransaction {
            database.messageDao().upsertAll(listOf(message.toEntity()))
            val stored = database.chatDao().get(message.id.chatId.value)?.toModel()
            val unread =
                if (message.outgoing || message.id.id <= (stored?.readInboxMaxId ?: 0) ||
                    message.id.id <= (stored?.lastMessageId ?: 0)
                ) {
                    stored?.unreadCount ?: 0
                } else {
                    (stored?.unreadCount ?: 0) + 1
                }
            val chat = (stored ?: placeholderChat(message.id.chatId)).copy(
                lastMessagePreview = message.chatListPreviewSource(),
                lastMessageOutgoing = message.outgoing,
                lastMessageSenderName = message.senderName,
                lastMessageMediaKind = message.mediaKind,
                lastMessageDate = message.date,
                unreadCount = unread,
                lastMessageId = message.id.id,
                lastMediaThumbCacheKey = message.thumbCacheKey ?: stored?.lastMediaThumbCacheKey,
            )
            database.chatDao().upsertAll(listOf(chat.toEntity()))
        }
    }

    /** Drops cached chats/history/peers/folders. Auth flags are [SessionMetadataStore.clearSession]. */
    open suspend fun clearAccountCache() = roomIo {
        firstPaintSnapshot = null
        val database = db ?: return@roomIo
        database.messageDao().clearAll()
        database.chatDao().clear()
        database.folderDao().clear()
        database.peerDao().clear()
        database.updateCursorDao().clear()
        database.profileTabsDao().clear()
        database.profileMembersDao().clear()
        database.profileMediaDao().clear()
        database.profileCommonDao().clear()
        database.messageHoleDao().clearAll()
        database.metaDao().deleteLike("history_coverage:%")
        database.metaDao().deleteLike("${DRAFT_META_PREFIX}%")
    }

    open suspend fun setDialogScroll(chatId: PeerId, messageId: Int) = roomIo {
        db?.chatDao()?.updateDialogScroll(chatId.value, messageId)
    }

    open suspend fun markChatRead(chatId: PeerId, maxId: Int) = roomIo {
        val database = db ?: return@roomIo
        database.withTransaction {
            val stored = database.chatDao().get(chatId.value) ?: return@withTransaction
            if (maxId <= stored.readInboxMaxId) return@withTransaction
            val unread = database.messageDao().countIncomingAfter(chatId.value, maxId)
            database.chatDao().updateInboxRead(chatId.value, maxId, unread)
        }
    }

    open suspend fun applyInboxRead(chatId: PeerId, maxId: Int, stillUnread: Int) = roomIo {
        val database = db ?: return@roomIo
        database.withTransaction {
            if (database.chatDao().get(chatId.value) == null) {
                database.chatDao().upsertAll(listOf(placeholderChat(chatId).toEntity()))
            }
            database.chatDao().updateInboxRead(chatId.value, maxId, stillUnread.coerceAtLeast(0))
        }
    }
}

/** Users are positive; groups/channels are negative. Unknown non-user peers start unjoined. */
internal fun placeholderChat(chatId: PeerId) = Chat(
    id = chatId,
    title = "",
    left = chatId.value < 0,
)

data class HistoryPrune(
    val chatId: Long,
    val lastMessageId: Int,
    val cachedMaxId: Int,
    val droppedIds: List<Int>,
)

/** Telegram history `limit` ceiling; Room cache windows stay at or below this. */
const val HISTORY_CACHE_WINDOW = 100

fun staleHistoryIds(cachedIds: List<Int>, lastMessageId: Int): List<Int> =
    if (lastMessageId <= 0) emptyList() else cachedIds.filter { it > lastMessageId }

/** Holes inside a fetched page, never live rows newer than that page. */
fun shouldDropUnsentAfterRestart(pending: Boolean, id: Int, randomId: Long?): Boolean =
    (pending || id < 0) && (randomId == null || randomId == 0L)

fun staleIdsInsideFetchedWindow(cachedIds: List<Int>, fetchedIds: Collection<Int>): List<Int> {
    if (fetchedIds.isEmpty()) return emptyList()
    val keep = fetchedIds.toSet()
    val minId = keep.minOrNull() ?: return emptyList()
    val maxId = keep.maxOrNull() ?: return emptyList()
    return cachedIds.filter { it in minId..maxId && it !in keep }
}

const val DRAFT_META_PREFIX = "draft:"

fun draftMetaKey(chatId: Long, threadId: Int = 0): String =
    if (threadId > 0) "${DRAFT_META_PREFIX}$chatId:$threadId" else "${DRAFT_META_PREFIX}$chatId"
