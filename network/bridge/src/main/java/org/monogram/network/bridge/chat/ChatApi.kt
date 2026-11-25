package org.monogram.network.bridge.chat

import kotlinx.coroutines.delay
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.ForumTopicsPage
import org.monogram.core.models.PeerId
import org.monogram.mtproto.MtprotoNative
import org.monogram.network.bridge.DialogOffset
import org.monogram.network.bridge.DialogPage
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.bridge.session.SessionCore
import uniffi.monogram_mtproto.ChatDto
import uniffi.monogram_mtproto.DialogsPageDto

internal class ChatApi(
    private val core: SessionCore,
) : ChatOps {
    override suspend fun getChats(): Outcome<List<Chat>> = getChatsPage().chatsOnly()

    override suspend fun getChatsPage(): Outcome<DialogPage> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcResult("getChats failed") {
            var page = sharedChats(idle = false)
            while (page == null) page = sharedChats(idle = false)
            page
        }
    }

    internal suspend fun getChatsIfIdle(): List<Chat>? {
        if (core.dialogRetryDelayMs() > 0L) return null
        when (core.ensureConnected()) {
            is Outcome.Err -> return null
            is Outcome.Ok -> Unit
        }
        return sharedChats(idle = true)?.chats
    }

    private suspend fun sharedChats(idle: Boolean): DialogPage? =
        core.coalesce("getChats:${core.dialogSnapshotGeneration.get()}") {
            if (!idle) awaitDialogDeadline()
            core.readDialogs(idle, ::fetchChats)
        }

    private fun fetchChats(handle: Long): DialogPage =
        fetchDialogPage({ core.native.getChatsPage(handle) }, { core.native.getChats(handle) })

    private fun fetchDialogPage(
        fetch: () -> DialogsPageDto,
        legacy: () -> List<ChatDto>,
    ): DialogPage {
        val page = try {
            fetch()
        } catch (e: UnsupportedOperationException) {
            if (core.native is MtprotoNative.UniFfi) throw e
            // Stub-backed fakes may implement only the original list methods.
            return DialogPage(legacy().toChatModels(), nextOffset = null)
        }
        return DialogPage(
            page.chats.toChatModels(),
            page.nextCursor?.let { DialogOffset(it.offsetDate, it.offsetId, it.offsetPeerId) },
        )
    }

    private suspend fun awaitDialogDeadline() {
        while (true) {
            val remaining = core.dialogRetryDelayMs()
            if (remaining == 0L) return
            delay(remaining)
        }
    }

    override suspend fun getFolders(): Outcome<List<Folder>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcResult("getFolders failed") {
            var folders = core.readFolders()
            while (folders == null) folders = core.readFolders()
            folders.map { it.toModel() }
        }
    }

    override suspend fun updateFolder(folder: Folder): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("updateFolder failed") { activeHandle ->
            core.native.updateFolder(activeHandle, folder.toDto())
        }
    }

    override suspend fun deleteFolder(id: Int): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("deleteFolder failed") { activeHandle ->
            core.native.deleteFolder(activeHandle, id)
        }
    }

    override suspend fun updateFolderOrder(order: List<Int>): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("updateFolderOrder failed") { activeHandle ->
            core.native.updateFolderOrder(activeHandle, order)
        }
    }

    override suspend fun loadMoreChats(
        offsetDate: Int,
        offsetId: Int,
        offsetPeerId: Long,
    ): Outcome<List<Chat>> = loadMoreFolderChats(0, offsetDate, offsetId, offsetPeerId)

    override suspend fun loadMoreFolderChats(
        folderId: Int,
        offsetDate: Int,
        offsetId: Int,
        offsetPeerId: Long,
    ): Outcome<List<Chat>> =
        loadMoreFolderChatsPage(folderId, offsetDate, offsetId, offsetPeerId).chatsOnly()

    override suspend fun loadMoreFolderChatsPage(
        folderId: Int,
        offsetDate: Int,
        offsetId: Int,
        offsetPeerId: Long,
    ): Outcome<DialogPage> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.coalesce("dialogs:$folderId:$offsetDate:$offsetId:$offsetPeerId") {
            val handle = core.activeHandleOrZero()
            var page: DialogPage?
            do {
                awaitDialogDeadline()
                if (!core.isCurrentHandle(handle)) return@coalesce Outcome.Err("Session changed")
                val result = core.rpcResult("loadMoreFolderChats failed") {
                    core.readDialogs { activeHandle ->
                        fetchDialogPage(
                            {
                                core.native.loadMoreChatsPage(
                                    activeHandle, offsetDate, offsetId, offsetPeerId, folderId
                                )
                            },
                            {
                                core.native.loadMoreChats(
                                    activeHandle, offsetDate, offsetId, offsetPeerId, folderId
                                )
                            },
                        )
                    }
                }
                page = when (result) {
                    is Outcome.Err -> return@coalesce result
                    is Outcome.Ok -> result.value
                }
            } while (page == null)
            Outcome.Ok(page)
        }
    }

    private fun Outcome<DialogPage>.chatsOnly(): Outcome<List<Chat>> = when (this) {
        is Outcome.Ok -> Outcome.Ok(value.chats)
        is Outcome.Err -> this
    }

    override suspend fun readHistory(
        chatId: PeerId,
        maxId: Int,
    ): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("readHistory failed") { activeHandle ->
            core.native.readHistory(activeHandle, chatId.value, maxId)
        }.also { result ->
            if (result is Outcome.Ok) {
                core.localUpdates.emit(
                    MtprotoUpdate.ReadHistoryConfirmed(
                        chatId,
                        maxId,
                    ),
                )
            }
        }
    }

    override suspend fun markDialogUnread(
        chatId: PeerId,
        unread: Boolean,
    ): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("markDialogUnread failed") { activeHandle ->
            core.native.markDialogUnread(activeHandle, chatId.value, unread)
        }.also { result ->
            if (result is Outcome.Ok) {
                core.localUpdates.emit(MtprotoUpdate.DialogUnreadMark(chatId, unread))
            }
        }
    }

    override suspend fun readDiscussion(
        chatId: PeerId,
        msgId: Int,
        readMaxId: Int,
    ): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("readDiscussion failed") { activeHandle ->
            core.native.readDiscussion(activeHandle, chatId.value, msgId, readMaxId)
        }.also { result ->
            if (result is Outcome.Ok) {
                core.localUpdates.emit(
                    MtprotoUpdate.DiscussionInbox(
                        chatId,
                        msgId,
                        readMaxId,
                    ),
                )
            }
        }
    }

    override suspend fun setTyping(
        chatId: PeerId,
        typing: Boolean,
    ): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcBackground("setTyping failed") { activeHandle ->
            core.native.setTyping(activeHandle, chatId.value, typing)
        }
    }

    override suspend fun updateStatus(offline: Boolean): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        if (!core.onNative { activeHandle -> core.native.isAuthorized(activeHandle) }) {
            return Outcome.Ok(
                Unit,
            )
        }
        return core.rpc("account.updateStatus failed") { activeHandle ->
            core.native.updateStatus(activeHandle, offline)
        }
    }

    override suspend fun getGroupAdminTags(chatId: PeerId): Outcome<Map<PeerId, String>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcBackground("getGroupAdminTags failed") { activeHandle ->
            val json =
                org.json.JSONObject(core.native.getGroupAdminTags(activeHandle, chatId.value))
            buildMap {
                json.keys().forEach { key ->
                    key.toLongOrNull()?.let { put(PeerId(it), json.getString(key)) }
                }
            }
        }
    }

    override suspend fun getForumTopics(
        chatId: PeerId,
        offsetDate: Int,
        offsetId: Int,
        offsetTopic: Int,
        limit: Int,
    ): Outcome<ForumTopicsPage> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        AppLog.api("getForumTopics", "start chat=${chatId.value}")
        return core.rpc("getForumTopics failed") { activeHandle ->
            core.native.getForumTopics(
                activeHandle,
                chatId.value,
                offsetDate,
                offsetId,
                offsetTopic,
                limit,
            ).toModel()
        }
    }

    override suspend fun getForumTopicsById(
        chatId: PeerId,
        topicIds: List<Int>,
    ): Outcome<ForumTopicsPage> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("getForumTopicsById failed") { activeHandle ->
            core.native.getForumTopicsById(activeHandle, chatId.value, topicIds).toModel()
        }
    }

    override suspend fun editForumTopicHidden(
        chatId: PeerId,
        topicId: Int,
        hidden: Boolean,
    ): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("editForumTopicHidden failed") { activeHandle ->
            core.native.editForumTopicHidden(activeHandle, chatId.value, topicId, hidden)
        }
    }

    override suspend fun joinChat(chatId: PeerId): Outcome<JoinMembership> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcWrite("channels.joinChannel failed") { activeHandle ->
            core.native.joinChat(activeHandle, chatId.value).let { joined ->
                JoinMembership(
                    joined = joined.joined,
                    canSendPlain = joined.canSendPlain,
                    canSendPhotos = joined.canSendPhotos,
                    canSendPolls = joined.canSendPolls,
                )
            }
        }
    }

    override suspend fun noteLocalChat(chat: Chat) {
        core.localUpdates.emit(MtprotoUpdate.ChatsChanged(listOf(chat)))
    }

    override suspend fun unblockUser(chatId: PeerId): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcWrite("contacts.unblock failed") { activeHandle ->
            core.native.unblockUser(activeHandle, chatId.value)
        }
    }
}
