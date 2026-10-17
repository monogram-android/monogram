package org.monogram.core.database

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.core.common.AppLog
import org.monogram.core.common.PerfLog
import org.monogram.core.database.dao.ChatReadState
import org.monogram.core.database.entity.MessageHoleEntity
import org.monogram.core.database.entity.UpdateBatchReceiptEntity
import org.monogram.core.database.entity.UpdateCursorEntity
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.chatListPreviewSource
import org.monogram.core.models.isPlaceholderPeerTitle
import org.monogram.core.models.mergeIncomingMessage
import org.monogram.core.models.mergeLocalCache

/**
 * Reads cached dialogs/folders/messages for UI hydration before network sync.
 *
 * Auth keys / api_hash are intentionally NOT stored in Room plaintext.
 * Session secrets belong in encrypted storage (follow-up).
 */
open class OfflineWarmup(
    private val db: MonogramDatabase? = null,
    val recentHistory: RecentHistoryMemory = RecentHistoryMemory(),
) {
    private val cleanupMutex = Mutex()

    @Volatile
    private var firstPaintSnapshot: OfflineWarmupSnapshot? = null

    @Volatile
    private var recentWarmupChats: List<Chat> = emptyList()

    @Volatile
    private var recentWarmupJob: Job? = null

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
        recentWarmupChats = chats
        PerfLog.mark("cache:startupSnapshot", PerfLog.nowMs() - started, "count=${chats.size}")
        try {
            pruneExpiredCache(force = true)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            AppLog.warn("warmup", "cache ttl prune failed")
        }
    }

    private suspend fun warmRecentHistory(chats: List<Chat>) {
        val top = chats.filter { !it.archived && !it.left }.take(RECENT_HISTORY_CHATS)
        for (chat in top) {
            val page = messages(chat.id, RECENT_HISTORY_MESSAGES)
            if (page.isNotEmpty()) recentHistory.remember(chat.id.value, page)
        }
    }

    suspend fun prepareRecentHistory() {
        val chats = recentWarmupChats
        recentWarmupJob = currentCoroutineContext()[Job]
        try {
            withTimeoutOrNull(2_000) {
                if (chats.isNotEmpty()) warmRecentHistory(chats)
            }
        } finally {
            recentWarmupJob = null
        }
    }

    open suspend fun chatsAfter(cursor: ChatCacheCursor, limit: Int): List<Chat> = roomIo {
        db?.chatDao()?.afterCursor(
            cursor.archived, cursor.pinned, cursor.pinnedOrder,
            cursor.lastMessageDate, cursor.id, limit
        )?.map { it.toModel() }.orEmpty()
    }

    open suspend fun oldestListed(archived: Boolean): Chat? = roomIo {
        db?.chatDao()?.oldestListed(archived)?.toModel()
    }

    /** Callers use this to skip redundant dispatcher hops for in-memory cache adapters. */
    open val usesIoDispatcher: Boolean get() = db != null

    private suspend inline fun <T> roomIo(crossinline block: suspend () -> T): T =
        if (db == null) block() else DatabaseProvider.onRoom { block() }

    @Volatile
    private var startupCleanupDone = false

    @Volatile
    private var openChatId: Long? = null

    @Volatile
    private var pruneOffset = 0

    @Volatile
    private var lastPruneAt = 0L

    fun setOpenChat(chatId: Long) {
        openChatId = chatId
    }

    fun clearOpenChat(chatId: Long) {
        if (openChatId == chatId) openChatId = null
    }

    suspend fun pruneExpiredCache(
        nowMillis: Long = System.currentTimeMillis(),
        force: Boolean = false,
    ) {
        if (db == null) return
        if (!force && nowMillis - lastPruneAt < 30_000L) return
        lastPruneAt = nowMillis
        try {
            withContext(RoomLane.maintenance()) {
                roomIo { pruneOnRoom(nowMillis) }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            AppLog.warn("warmup", "cache ttl prune failed")
        }
    }

    private suspend fun pruneOnRoom(nowMillis: Long) {
        val database = db ?: return
        val cutoffSeconds = nowMillis / 1000 - CACHE_MESSAGE_TTL_SECONDS
        val chats = database.messageDao().chatIdsPage(CACHE_PRUNE_CHATS, pruneOffset)
        pruneOffset = if (chats.size < CACHE_PRUNE_CHATS) 0 else pruneOffset + chats.size
        for (chatId in chatsToPrune(chats, openChatId)) {
            currentCoroutineContext().ensureActive()
            val newest = database.messageDao()
                .newestConfirmedIds(chatId, HISTORY_CACHE_WINDOW)
                .toSet()
            val old = database.messageDao()
                .oldConfirmedIds(chatId, cutoffSeconds, CACHE_PRUNE_ROWS + newest.size)
            val drop = old.filter { it !in newest }.take(CACHE_PRUNE_ROWS)
            val kept = (newest + database.messageDao()
                .stickyIds(chatId, cutoffSeconds, 200)).distinct()
            kept.minOrNull()?.let { oldestKept ->
                database.messageTombstoneDao().deleteBelow(chatId, oldestKept)
            }
            if (drop.isNotEmpty()) {
                database.messageDao().deleteInChat(chatId, drop)
                database.messageTombstoneDao().deleteIds(chatId, drop)
                val holes = holesAfterCacheTtl(
                    chatId,
                    database.messageHoleDao().forChat(chatId, MessageHoleEntity.SOURCE_HISTORY),
                    drop,
                    kept,
                )
                val holeDao = database.messageHoleDao()
                holeDao.clearChat(chatId, MessageHoleEntity.SOURCE_HISTORY)
                holes.forEach { holeDao.insert(it) }
            }
            kotlinx.coroutines.yield()
        }
        val tombstones = database.messageTombstoneDao()
        val emptyChats = tombstones.chatsWithoutMessages(CACHE_PRUNE_CHATS)
        if (emptyChats.isNotEmpty()) tombstones.deleteChats(emptyChats)
        tombstones.trimUserSpace(HISTORY_CACHE_WINDOW * 2)
        val discussion = database.metaDao().keysLike("discussion_read:%")
        for (key in discussionReadKeysToDrop(discussion)) {
            database.metaDao().delete(key)
        }
        val stalePeers = database.peerDao().unreferencedIds(CACHE_PRUNE_ROWS)
        if (stalePeers.isNotEmpty()) database.peerDao().deleteIds(stalePeers)
        val cutoffMillis = nowMillis - CACHE_PROFILE_TTL_MILLIS
        for (peerId in database.profileTabsDao().stalePeerIds(cutoffMillis, CACHE_PRUNE_ROWS)) {
            currentCoroutineContext().ensureActive()
            database.profileTabsDao().delete(peerId)
            database.profileMembersDao().delete(peerId)
            database.profileMediaDao().deletePeer(peerId)
            database.profileCommonDao().delete(peerId)
        }
    }

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
        recentHistory.invalidate(chatId.value)
        db?.chatDao()?.updateOutboxRead(chatId.value, maxId)
    }

    open suspend fun applyReactions(chatId: PeerId, messageId: Int, json: String) = roomIo {
        recentHistory.invalidate(chatId.value)
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
        recentHistory.invalidate(message.id.chatId.value)
        val database = db ?: return@roomIo
        database.withTransaction {
            if (mergeMessages(listOf(message)).isNotEmpty()) {
                refreshPreview(message.id.chatId, message.id.id)
            }
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
        return DatabaseProvider.onRoom {
            ensureStartupCleanup()
            database.chatDao().observeAll().map { it.toModel() }
        }
    }

    open suspend fun chatsWindow(limit: Int, archiveLimit: Int = 3): List<Chat> {
        val database = db ?: run {
            ensureStartupCleanup()
            return chats()
        }
        return DatabaseProvider.onRoom {
            ensureStartupCleanup()
            val main = database.chatDao().mainListPage(limit, 0)
            val archived = database.chatDao().archivePreview(archiveLimit)
            (main + archived).distinctBy { it.id }.map { it.toModel() }
        }
    }

    open suspend fun mainListCount(): Int {
        val database = db ?: run {
            ensureStartupCleanup()
            return 0
        }
        return DatabaseProvider.onRoom {
            ensureStartupCleanup()
            database.chatDao().mainListCount()
        }
    }

    open suspend fun chat(chatId: PeerId): Chat? {
        val database = db ?: run {
            ensureStartupCleanup()
            return chats().firstOrNull { it.id == chatId }
        }
        return DatabaseProvider.onRoom {
            ensureStartupCleanup()
            database.chatDao().get(chatId.value)?.toModel()
        }
    }

    open suspend fun folders(): List<Folder> {
        val database = db ?: run {
            ensureStartupCleanup()
            return emptyList()
        }
        return DatabaseProvider.onRoom {
            ensureStartupCleanup()
            database.folderDao().observeAll().map { it.toModel() }
        }
    }

    /** Drops failed/error rows and stuck local rows without a random id. Valid pending sends survive restart. */
    open suspend fun dropUnsentAfterRestart() {
        val database = db ?: return
        roomIo {
            database.withTransaction {
                val chatIds = database.messageDao().startupTransientChatIds()
                if (chatIds.isEmpty()) return@withTransaction
                database.messageDao().deleteStartupTransient()
                chatIds.forEach { refreshLatestPreview(PeerId(it)) }
            }
        }
    }

    open suspend fun markMessageFailed(chatId: PeerId, messageId: Int) = roomIo {
        recentHistory.invalidate(chatId.value)
        db?.messageDao()?.markFailed(chatId.value, messageId)
    }

    open suspend fun reconcileOutgoing(pendingId: Int, message: Message, randomId: Long?) = roomIo {
        require(message.id.id > 0)
        recentHistory.invalidate(message.id.chatId.value)
        val database = db
        if (database == null) {
            deleteMessage(message.id.chatId, pendingId)
            upsertMessages(listOf(message))
            return@roomIo
        }
        database.withTransaction {
            val pending = database.messageDao().byIds(message.id.chatId.value, listOf(pendingId))
                .singleOrNull()
            if (pendingId != message.id.id && randomId != null && randomId != 0L && pending?.randomId == randomId) {
                database.messageDao().delete(message.id.chatId.value, pendingId)
            }
            mergeMessages(listOf(message.copy(pending = false, failed = false)))
            refreshPreview(message.id.chatId, pendingId)
        }
    }

    open suspend fun messages(chatId: PeerId, limit: Int = HISTORY_CACHE_WINDOW): List<Message> {
        val database = db ?: run {
            ensureStartupCleanup()
            return emptyList()
        }
        return DatabaseProvider.onRoom {
            ensureStartupCleanup()
            val pending = database.messageDao().pendingForChat(chatId.value).map { it.toModel() }
            val latest =
                database.messageDao().latestForChat(chatId.value, limit).map { it.toModel() }
            pending + latest
        }
    }

    open suspend fun messagesByIds(chatId: PeerId, ids: List<Int>): List<Message> {
        if (ids.isEmpty()) return emptyList()
        val database = db ?: run {
            ensureStartupCleanup()
            return emptyList()
        }
        return DatabaseProvider.onRoom {
            ensureStartupCleanup()
            val holes =
                database.messageHoleDao().forChat(chatId.value, MessageHoleEntity.SOURCE_HISTORY)
            if (ids.any { id -> holes.any { id in it.startId..it.endId } }) return@onRoom emptyList()
            val rows = database.messageDao().byIds(chatId.value, ids).map { it.toModel() }
            val byId = rows.associateBy { it.id.id }
            ids.mapNotNull { byId[it] }
        }
    }

    open suspend fun olderMessages(
        chatId: PeerId,
        beforeId: Int,
        limit: Int = HISTORY_CACHE_WINDOW
    ): List<Message> {
        val database = db ?: run {
            ensureStartupCleanup()
            return emptyList()
        }
        return DatabaseProvider.onRoom {
            ensureStartupCleanup()
            val rows = database.messageDao().olderThan(chatId.value, beforeId, limit)
            if (database.metaDao()
                    .get("history_coverage:${chatId.value}") == null
            ) return@onRoom emptyList()
            if (rows.isEmpty()) return@onRoom emptyList()
            if (database.messageHoleDao().overlaps(
                    chatId.value, rows.minOf { it.id }, beforeId - 1,
                    MessageHoleEntity.SOURCE_HISTORY
                )
            ) return@onRoom emptyList()
            rows.map { it.toModel() }
        }
    }

    open suspend fun newerMessages(
        chatId: PeerId,
        afterId: Int,
        limit: Int = HISTORY_CACHE_WINDOW
    ): List<Message> {
        val database = db ?: run {
            ensureStartupCleanup()
            return emptyList()
        }
        return DatabaseProvider.onRoom {
            ensureStartupCleanup()
            val rows = database.messageDao().newerThan(chatId.value, afterId, limit)
            if (database.metaDao()
                    .get("history_coverage:${chatId.value}") == null
            ) return@onRoom emptyList()
            if (rows.isEmpty()) return@onRoom emptyList()
            if (database.messageHoleDao().overlaps(
                    chatId.value, afterId + 1, rows.maxOf { it.id },
                    MessageHoleEntity.SOURCE_HISTORY
                )
            ) return@onRoom emptyList()
            rows.map { it.toModel() }
        }
    }

    open suspend fun historySlice(
        chatId: PeerId,
        beforeId: Int? = null,
        afterId: Int? = null,
        limit: Int = HISTORY_CACHE_WINDOW,
    ): HistorySlice {
        val database =
            db ?: return HistorySlice(emptyList(), false, emptyList(), false, false, false)
        return DatabaseProvider.onRoom {
            val rows = when {
                beforeId != null -> database.messageDao().olderThan(chatId.value, beforeId, limit)
                afterId != null -> database.messageDao().newerThan(chatId.value, afterId, limit)
                else -> database.messageDao().latestForChat(chatId.value, limit)
            }.map { it.toModel() }
            val holes =
                database.messageHoleDao().forChat(chatId.value, MessageHoleEntity.SOURCE_HISTORY)
            val initialized = database.metaDao().get("history_coverage:${chatId.value}") != null
            val start = when {
                beforeId != null -> (rows.minOfOrNull { it.id.id } ?: 1)
                afterId != null -> afterId + 1
                else -> rows.minOfOrNull { it.id.id } ?: 1
            }
            val end = when {
                beforeId != null -> beforeId - 1
                afterId != null -> rows.maxOfOrNull { it.id.id } ?: afterId
                else -> rows.maxOfOrNull { it.id.id } ?: 0
            }
            historySliceOf(rows, initialized, holes, start, end)
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
        recentHistory.invalidate(chatId.value)
        val database = db
        if (database == null) {
            upsertMessages(messages)
            return@roomIo
        }
        database.withTransaction {
            val metaKey = "history_coverage:${chatId.value}"
            val holeDao = database.messageHoleDao()
            if (database.metaDao().get(metaKey) == null) {
                val holes = mergeHistoryHoles(
                    holeDao.forChat(chatId.value, MessageHoleEntity.SOURCE_HISTORY) +
                            MessageHoleEntity(chatId.value, 1, Int.MAX_VALUE)
                )
                holeDao.clearChat(chatId.value, MessageHoleEntity.SOURCE_HISTORY)
                holes.forEach { holeDao.insert(it) }
                database.metaDao()
                    .upsert(org.monogram.core.database.entity.MetaEntity(metaKey, "1"))
            }
            mergeMessages(messages)
            val ids = messages.filter { !it.pending && it.id.id > 0 }.map { it.id.id }
            val min = if (reachesOldest) 1 else ids.minOrNull()
            val max =
                if (reachesNewest) Int.MAX_VALUE else olderBoundary?.minus(1) ?: ids.maxOrNull()
            if (min != null && max != null && min <= max) {
                val holes = holeDao.forChat(chatId.value, MessageHoleEntity.SOURCE_HISTORY)
                    .flatMap { subtractHistoryRange(it, min, max) }
                holeDao.clearChat(chatId.value, MessageHoleEntity.SOURCE_HISTORY)
                holes.forEach { holeDao.insert(it) }
            }
        }
        pruneExpiredCache()
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
        return DatabaseProvider.onRoom {
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
        DatabaseProvider.onRoom {
            database.withTransaction {
                val stored =
                    database.chatDao().getByIds(chats.map { it.id.value }).associateBy { it.id }
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
        recentHistory.invalidate(chatId.value)
        val database = db ?: return@roomIo
        database.withTransaction {
            mergeMessages(messages)
            database.messageDao().deleteExcept(chatId.value, messages.map { it.id.id })
        }
    }

    open suspend fun upsertMessages(messages: List<Message>) = withContext(RoomLane.maintenance()) {
        upsertMessagesNow(messages)
    }

    private suspend fun upsertMessagesNow(messages: List<Message>) = roomIo {
        messages.map { it.id.chatId.value }.distinct().forEach(recentHistory::invalidate)
        if (messages.isEmpty()) return@roomIo
        val database = db ?: return@roomIo
        for (batch in chunkMessages(messages)) {
            currentCoroutineContext().ensureActive()
            database.withTransaction { mergeMessages(batch) }
            kotlinx.coroutines.yield()
        }
        pruneExpiredCache()
    }

    private suspend fun mergeMessages(messages: List<Message>): List<Message> {
        val database = db ?: return emptyList()
        val accepted = mutableListOf<Message>()
        for ((chatId, rows) in messages.groupBy { it.id.chatId.value }) {
            val ids = rows.map { it.id.id }
            val deleted = database.messageTombstoneDao().deletedIds(chatId, ids).toSet()
            val stored =
                database.messageDao().byIds(chatId, ids).associateBy { it.id }.toMutableMap()
            for (message in rows) {
                if (message.id.id in deleted) continue
                val incoming = message.toEntity()
                val previous = stored[incoming.id]
                if (previous != null && (previous.editDate ?: 0L) > (incoming.editDate
                        ?: 0L)
                ) continue
                stored[incoming.id] = incoming
                accepted += message
            }
        }
        if (accepted.isNotEmpty()) database.messageDao().upsertAll(accepted.map { it.toEntity() })
        return accepted
    }

    open suspend fun deleteMessage(chatId: PeerId, messageId: Int) = roomIo {
        deleteMessages(chatId, listOf(messageId))
    }

    open suspend fun deleteMessages(chatId: PeerId?, messageIds: Collection<Int>) = roomIo {
        if (chatId == null) recentHistory.clear() else recentHistory.invalidate(chatId.value)
        val database = db ?: return@roomIo
        val ids = messageIds.toList()
        if (ids.isEmpty()) return@roomIo
        database.withTransaction {
            // DAO read, not `chats()`: a gated read here would wait on the startup-cleanup mutex
            // while this transaction is open and then open a nested one.
            val affected =
                database.chatDao().withLastMessageIds(chatId?.value, ids).map { it.toModel() }
            database.messageTombstoneDao().insert(ids.filter { it > 0 }.map {
                org.monogram.core.database.entity.MessageTombstoneEntity(chatId?.value ?: 0L, it)
            })
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
        return DatabaseProvider.onRoom {
            val out = mutableListOf<HistoryPrune>()
            for (chat in chats) {
                if (chat.lastMessageId <= 0) continue
                val dropped = database.messageDao().idsAfter(chat.id.value, chat.lastMessageId)
                if (dropped.isEmpty()) continue
                recentHistory.invalidate(chat.id.value)
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
        recentHistory.invalidate(chatId.value)
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

    open suspend fun applyIncomingMessage(
        message: Message,
        accountGeneration: Long = 0,
        acceptedOrder: Long = 0,
    ) = roomIo {
        recentHistory.invalidate(message.id.chatId.value)
        val database = db ?: return@roomIo
        database.withTransaction {
            val existed =
                database.messageDao().byIds(message.id.chatId.value, listOf(message.id.id))
                    .isNotEmpty()
            if (mergeMessages(listOf(message)).isEmpty()) return@withTransaction
            val stored = database.chatDao().get(message.id.chatId.value)?.toModel()
            val chat = (stored ?: placeholderChat(message.id.chatId))
                .mergeIncomingMessage(
                    message,
                    alreadyStored = existed,
                    accountGeneration = accountGeneration,
                    acceptedOrder = acceptedOrder,
                )
            database.chatDao().upsertAll(listOf(chat.toEntity()))
        }
    }

    /**
     * Commits one native update batch: its domain mutations, the application
     * checkpoint and the receipt share a single transaction. A batch whose receipt
     * exists is a duplicate delivery and changes nothing. Returns true when the
     * batch is committed, whether now or earlier, so the caller may acknowledge it.
     */
    open suspend fun applyDurableBatch(
        accountGeneration: Long,
        batchId: Long,
        cursor: UpdateCursorEntity?,
        channelCursors: Map<Long, Int>,
        apply: suspend () -> Unit,
    ): Boolean = roomIo {
        val database = db ?: return@roomIo true
        database.withTransaction {
            val dao = database.updateCursorDao()
            if (dao.isBatchApplied(accountGeneration, batchId)) return@withTransaction true
            apply()
            cursor?.let { dao.upsert(it) }
            for ((chatId, pts) in channelCursors) {
                val key = "$KEY_CHANNEL_CURSOR_PREFIX$chatId"
                val stored = dao.get(key)
                if (stored == null || stored.channelPts < pts) {
                    dao.upsert(UpdateCursorEntity(key, 0, 0, 0, 0, channelPts = pts))
                }
            }
            dao.insertReceipt(
                UpdateBatchReceiptEntity(accountGeneration, batchId, System.currentTimeMillis())
            )
            dao.pruneReceipts(accountGeneration, batchId - RECEIPT_WINDOW)
            true
        }
    }

    /** Drops cached chats/history/peers/folders. Auth flags are [SessionMetadataStore.clearSession]. */
    open suspend fun clearAccountCache() = roomIo {
        recentWarmupJob?.cancel()
        recentHistory.clear()
        firstPaintSnapshot = null
        recentWarmupChats = emptyList()
        val database = db ?: return@roomIo
        database.messageDao().clearAll()
        database.messageTombstoneDao().clear()
        database.chatDao().clear()
        database.folderDao().clear()
        database.peerDao().clear()
        database.updateCursorDao().clear()
        database.updateCursorDao().clearReceipts()
        database.profileTabsDao().clear()
        database.profileMembersDao().clear()
        database.profileMediaDao().clear()
        database.profileCommonDao().clear()
        database.messageHoleDao().clearAll()
        database.metaDao().deleteLike("history_coverage:%")
        database.metaDao().deleteLike("discussion_read:%")
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

const val KEY_CHANNEL_CURSOR_PREFIX = "updates.channel:"

/** Receipts kept per account; native acknowledgement is long done for anything older. */
const val RECEIPT_WINDOW = 1024L

const val MESSAGE_BATCH_ROWS = 100
const val MESSAGE_BATCH_BYTES = 256 * 1024

fun Message.estimatedSerializedBytes(): Int {
    var bytes = 128
    fun add(value: String?) {
        if (value != null) bytes += value.length * 2
    }
    add(text)
    add(mediaCacheKey)
    add(mediaKind)
    add(thumbCacheKey)
    add(fileName)
    add(reactionsJson)
    add(replyQuote)
    add(fwdFrom)
    add(viaBot)
    add(senderName)
    bytes += entities.size * 32
    return bytes
}

fun chunkMessages(
    messages: List<Message>,
    maxRows: Int = MESSAGE_BATCH_ROWS,
    maxBytes: Int = MESSAGE_BATCH_BYTES,
): List<List<Message>> {
    val batches = mutableListOf<List<Message>>()
    var current = mutableListOf<Message>()
    var currentBytes = 0
    for (message in messages) {
        val size = message.estimatedSerializedBytes()
        if (current.isNotEmpty() && (current.size >= maxRows || currentBytes + size > maxBytes)) {
            batches += current
            current = mutableListOf()
            currentBytes = 0
        }
        current += message
        currentBytes += size
    }
    if (current.isNotEmpty()) batches += current
    return batches
}

const val DRAFT_META_PREFIX = "draft:"

fun draftMetaKey(chatId: Long, threadId: Int = 0): String =
    if (threadId > 0) "${DRAFT_META_PREFIX}$chatId:$threadId" else "${DRAFT_META_PREFIX}$chatId"
