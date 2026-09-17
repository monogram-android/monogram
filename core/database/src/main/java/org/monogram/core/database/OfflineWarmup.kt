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
import org.monogram.core.database.dao.ChatReadState
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.Message
import org.monogram.core.models.Profile
import org.monogram.core.models.isPlaceholderPeerTitle
import org.monogram.core.models.chatListPreviewSource
import org.monogram.core.models.mergeLocalCache
import org.monogram.core.models.PeerId

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
    private var startupCleanupDone = false

    /**
     * Drops rows left by process death before the first warmup read returns.
     */
    suspend fun ensureStartupCleanup() {
        if (startupCleanupDone) return
        cleanupMutex.withLock {
            if (startupCleanupDone) return@withLock
            try {
                dropUnsentAfterRestart()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                AppLog.warn("warmup", "startup cleanup failed")
            }
            startupCleanupDone = true
        }
    }

    open fun observeReadStates(): Flow<List<ChatReadState>> =
        db?.chatDao()?.observeReadStates() ?: emptyFlow()

    open suspend fun draft(chatId: PeerId, threadId: Int = 0): String =
        db?.metaDao()?.get(draftMetaKey(chatId.value, threadId))?.value.orEmpty()

    open suspend fun setDraft(chatId: PeerId, threadId: Int = 0, text: String) {
        val database = db ?: return
        val key = draftMetaKey(chatId.value, threadId)
        if (text.isEmpty()) {
            database.metaDao().delete(key)
        } else {
            database.metaDao().upsert(
                org.monogram.core.database.entity.MetaEntity(key, text),
            )
        }
    }

    open suspend fun discussionReadMax(chatId: PeerId, topId: Int): Int =
        db?.metaDao()?.get("discussion_read:${chatId.value}:$topId")?.value?.toIntOrNull() ?: 0

    open suspend fun applyDiscussionRead(chatId: PeerId, topId: Int, maxId: Int) {
        val database = db ?: return
        database.withTransaction {
            val confirmed = maxOf(discussionReadMax(chatId, topId), maxId)
            database.metaDao().upsert(
                org.monogram.core.database.entity.MetaEntity(
                    "discussion_read:${chatId.value}:$topId",
                    confirmed.toString(),
                ),
            )
        }
    }

    open suspend fun applyOutboxRead(chatId: PeerId, maxId: Int) {
        db?.chatDao()?.updateOutboxRead(chatId.value, maxId)
    }

    open suspend fun applyReactions(chatId: PeerId, messageId: Int, json: String) {
        db?.messageDao()?.updateReactions(chatId.value, messageId, json)
    }

    open suspend fun applyUnreadMentions(chatId: PeerId, stillUnread: Int) {
        db?.chatDao()?.updateUnreadMentions(chatId.value, stillUnread.coerceAtLeast(0))
    }

    open suspend fun applyUnreadReactions(chatId: PeerId, stillUnread: Int) {
        db?.chatDao()?.updateUnreadReactions(chatId.value, stillUnread.coerceAtLeast(0))
    }

    open suspend fun addUnreadMentions(chatId: PeerId, delta: Int) {
        db?.chatDao()?.addUnreadMentions(chatId.value, delta)
    }

    open suspend fun addUnreadReactions(chatId: PeerId, delta: Int) {
        db?.chatDao()?.addUnreadReactions(chatId.value, delta)
    }

    open suspend fun applyMessageEdit(message: Message) {
        val database = db ?: return
        database.withTransaction {
            upsertMessages(listOf(message))
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
        val database = db ?: return emptyList()
        return withContext(Dispatchers.IO) {
            ensureStartupCleanup()
            database.chatDao().observeAll().map { it.toModel() }
        }
    }

    open suspend fun chatsWindow(limit: Int, archiveLimit: Int = 3): List<Chat> {
        val database = db ?: return chats()
        return withContext(Dispatchers.IO) {
            ensureStartupCleanup()
            val main = database.chatDao().mainListPage(limit, 0)
            val archived = database.chatDao().archivePreview(archiveLimit)
            (main + archived).distinctBy { it.id }.map { it.toModel() }
        }
    }

    open suspend fun chatsPage(offset: Int, limit: Int): List<Chat> {
        val database = db ?: return emptyList()
        return withContext(Dispatchers.IO) {
            database.chatDao().mainListPage(limit, offset).map { it.toModel() }
        }
    }

    open suspend fun chatsExcluding(excludeIds: List<Long>, limit: Int): List<Chat> {
        val database = db ?: return emptyList()
        if (excludeIds.isEmpty()) return chatsWindow(limit, archiveLimit = 0)
        return withContext(Dispatchers.IO) {
            database.chatDao().mainListExcluding(excludeIds, limit).map { it.toModel() }
        }
    }

    open suspend fun mainListCount(): Int {
        val database = db ?: return 0
        return withContext(Dispatchers.IO) {
            database.chatDao().mainListCount()
        }
    }

    open suspend fun chat(chatId: PeerId): Chat? {
        val database = db
        if (database != null) {
            ensureStartupCleanup()
            return database.chatDao().get(chatId.value)?.toModel()
        }
        return chats().firstOrNull { it.id == chatId }
    }

    open suspend fun folders(): List<Folder> {
        val database = db ?: return emptyList()
        return withContext(Dispatchers.IO) {
            ensureStartupCleanup()
            database.folderDao().observeAll().map { it.toModel() }
        }
    }

    /** Drops stuck unsent/failed local rows left after process death. */
    open suspend fun dropUnsentAfterRestart() {
        val database = db ?: return
        database.withTransaction {
            val chatIds = database.messageDao().unsentChatIds()
            if (chatIds.isEmpty()) return@withTransaction
            database.messageDao().deleteUnsent()
            chatIds.forEach { refreshLatestPreview(PeerId(it)) }
        }
    }

    open suspend fun messages(chatId: PeerId, limit: Int = 200): List<Message> {
        ensureStartupCleanup()
        val dao = db?.messageDao() ?: return emptyList()
        val pending = dao.pendingForChat(chatId.value).map { it.toModel() }
        val latest = dao.latestForChat(chatId.value, limit).map { it.toModel() }
        return pending + latest
    }

    open suspend fun messagesByIds(chatId: PeerId, ids: List<Int>): List<Message> {
        if (ids.isEmpty()) return emptyList()
        val rows = db?.messageDao()?.byIds(chatId.value, ids).orEmpty().map { it.toModel() }
        val byId = rows.associateBy { it.id.id }
        return ids.mapNotNull { byId[it] }
    }

    open suspend fun olderMessages(chatId: PeerId, beforeId: Int, limit: Int = 40): List<Message> {
        ensureStartupCleanup()
        return db?.messageDao()
            ?.olderThan(chatId.value, beforeId, limit)
            ?.map { it.toModel() }
            .orEmpty()
    }

    open suspend fun replaceChats(chats: List<Chat>) {
        val database = db ?: return
        database.withTransaction {
            val stored = database.chatDao().observeAll().associateBy { it.id }
            database.chatDao().clear()
            if (chats.isEmpty()) return@withTransaction
            database.chatDao().upsertAll(
                chats.map { chat ->
                    chat.mergeLocalCache(stored[chat.id.value]?.toModel()).toEntity()
                },
            )
        }
    }

    open suspend fun applyProfileToChat(profile: Profile) {
        val stored = db?.chatDao()?.get(profile.id.value)?.toModel() ?: return
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

    open suspend fun replaceFolders(folders: List<Folder>) {
        val database = db ?: return
        // List order is the folder order, so the index has to land in the row.
        val rows = folders.mapIndexed { index, folder -> folder.toEntity(position = index) }
        database.withTransaction {
            database.folderDao().clear()
            database.folderDao().upsertAll(rows)
        }
    }

    open suspend fun replaceMessages(chatId: PeerId, messages: List<Message>) {
        db?.messageDao()?.clearChat(chatId.value)
        upsertMessages(messages)
    }

    open suspend fun upsertMessages(messages: List<Message>) {
        if (messages.isEmpty()) return
        db?.messageDao()?.upsertAll(messages.map { it.toEntity() })
    }

    open suspend fun deleteMessage(chatId: PeerId, messageId: Int) {
        db?.messageDao()?.delete(chatId.value, messageId)
    }

    open suspend fun deleteMessages(chatId: PeerId?, messageIds: Collection<Int>) {
        val database = db ?: return
        val ids = messageIds.toList()
        if (ids.isEmpty()) return
        database.withTransaction {
            // DAO read, not `chats()`: a gated read here would wait on the startup-cleanup mutex
            // while this transaction is open and then open a nested one.
            val affected = database.chatDao().observeAll().map { it.toModel() }.filter {
                (chatId == it.id || (chatId == null && it.id.value > -1_000_000_000_000L)) &&
                    it.lastMessageId in ids
            }
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
    open suspend fun pruneMissingLatest(chatId: PeerId, fetched: List<Message>) {
        val database = db ?: return
        if (fetched.isEmpty()) return
        val keep = fetched.mapTo(mutableSetOf()) { it.id.id }
        val minId = keep.minOrNull() ?: return
        val stale = database.messageDao()
            .idsAtOrAfter(chatId.value, minId)
            .filterNot(keep::contains)
        if (stale.isNotEmpty()) {
            database.messageDao().deleteInChat(chatId.value, stale)
        }
    }

    open suspend fun applyIncomingMessage(message: Message) {
        val database = db ?: return
        database.withTransaction {
            upsertMessages(listOf(message))
            val stored = database.chatDao().get(message.id.chatId.value)?.toModel()
            val unread =
                if (message.outgoing || message.id.id <= (stored?.readInboxMaxId ?: 0) ||
                    message.id.id <= (stored?.lastMessageId ?: 0)
                ) {
                    stored?.unreadCount ?: 0
                } else {
                    (stored?.unreadCount ?: 0) + 1
                }
            val chat = (stored ?: Chat(id = message.id.chatId, title = "")).copy(
                lastMessagePreview = message.chatListPreviewSource(),
                lastMessageOutgoing = message.outgoing,
                lastMessageSenderName = message.senderName,
                lastMessageMediaKind = message.mediaKind,
                lastMessageDate = message.date,
                unreadCount = unread,
                lastMessageId = message.id.id,
                lastMediaThumbCacheKey = message.thumbCacheKey ?: stored?.lastMediaThumbCacheKey,
            )
            upsertChats(listOf(chat))
        }
    }

    /** Drops cached chats/history/peers/folders. Auth flags are [SessionMetadataStore.clearSession]. */
    open suspend fun clearAccountCache() {
        val database = db ?: return
        database.messageDao().clearAll()
        database.chatDao().clear()
        database.folderDao().clear()
        database.peerDao().clear()
        database.updateCursorDao().clear()
        database.profileTabsDao().clear()
        database.profileMembersDao().clear()
        database.profileMediaDao().clear()
        database.profileCommonDao().clear()
        database.metaDao().deleteLike("${DRAFT_META_PREFIX}%")
    }

    open suspend fun setDialogScroll(chatId: PeerId, messageId: Int) {
        db?.chatDao()?.updateDialogScroll(chatId.value, messageId)
    }

    open suspend fun markChatRead(chatId: PeerId, maxId: Int) {
        val database = db ?: return
        database.withTransaction {
            val stored = database.chatDao().get(chatId.value) ?: return@withTransaction
            if (maxId <= stored.readInboxMaxId) return@withTransaction
            val unread = database.messageDao().countIncomingAfter(chatId.value, maxId)
            database.chatDao().updateInboxRead(chatId.value, maxId, unread)
        }
    }

    open suspend fun applyInboxRead(chatId: PeerId, maxId: Int, stillUnread: Int) {
        val database = db ?: return
        database.withTransaction {
            if (database.chatDao().get(chatId.value) == null) {
                database.chatDao().upsertAll(listOf(Chat(chatId, "").toEntity()))
            }
            database.chatDao().updateInboxRead(chatId.value, maxId, stillUnread.coerceAtLeast(0))
        }
    }
}

data class HistoryPrune(
    val chatId: Long,
    val lastMessageId: Int,
    val cachedMaxId: Int,
    val droppedIds: List<Int>,
)

fun staleHistoryIds(cachedIds: List<Int>, lastMessageId: Int): List<Int> =
    if (lastMessageId <= 0) emptyList() else cachedIds.filter { it > lastMessageId }

const val DRAFT_META_PREFIX = "draft:"

fun draftMetaKey(chatId: Long, threadId: Int = 0): String =
    if (threadId > 0) "${DRAFT_META_PREFIX}$chatId:$threadId" else "${DRAFT_META_PREFIX}$chatId"
