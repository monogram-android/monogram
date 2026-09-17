package org.monogram.network.bridge.chat

import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.ForumTopicsPage
import org.monogram.core.models.PeerId
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.bridge.session.SessionCore

internal class ChatApi(private val core: SessionCore) : ChatOps {
    override suspend fun getChats(): Outcome<List<Chat>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("getChats failed") { activeHandle ->
            core.native.getChats(activeHandle).toChatModels()
        }
    }

    override suspend fun getFolders(): Outcome<List<Folder>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcBackground("getFolders failed") { activeHandle ->
            core.native.getFolders(activeHandle).map { it.toModel() }
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
    ): Outcome<List<Chat>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("loadMoreChats failed") { activeHandle ->
            core.native.loadMoreChats(activeHandle, offsetDate, offsetId, offsetPeerId, 0)
                .toChatModels()
        }
    }

    override suspend fun loadMoreFolderChats(
        folderId: Int,
        offsetDate: Int,
        offsetId: Int,
        offsetPeerId: Long,
    ): Outcome<List<Chat>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("loadMoreFolderChats failed") { activeHandle ->
            core.native.loadMoreChats(activeHandle, offsetDate, offsetId, offsetPeerId, folderId)
                .toChatModels()
        }
    }

    override suspend fun readHistory(chatId: PeerId, maxId: Int): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("readHistory failed") { activeHandle ->
            core.native.readHistory(activeHandle, chatId.value, maxId)
        }.also { result ->
            if (result is Outcome.Ok) core.localUpdates.emit(
                MtprotoUpdate.ReadHistoryConfirmed(
                    chatId,
                    maxId
                )
            )
        }
    }

    override suspend fun markDialogUnread(chatId: PeerId, unread: Boolean): Outcome<Unit> {
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
            if (result is Outcome.Ok) core.localUpdates.emit(
                MtprotoUpdate.DiscussionInbox(
                    chatId,
                    msgId,
                    readMaxId
                )
            )
        }
    }

    override suspend fun setTyping(chatId: PeerId, typing: Boolean): Outcome<Unit> {
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
        if (!core.onNative { activeHandle -> core.native.isAuthorized(activeHandle) }) return Outcome.Ok(
            Unit
        )
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
                limit
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
}