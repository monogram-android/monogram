package org.monogram.network.bridge.chat

import org.monogram.core.common.Outcome
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.ForumTopicsPage
import org.monogram.core.models.PeerId

interface ChatOps {
    suspend fun getChats(): Outcome<List<Chat>>
    suspend fun loadMoreChats(
        offsetDate: Int,
        offsetId: Int,
        offsetPeerId: Long
    ): Outcome<List<Chat>>

    suspend fun getFolders(): Outcome<List<Folder>>
    suspend fun updateFolder(folder: Folder): Outcome<Unit> = Outcome.Err("unsupported")
    suspend fun deleteFolder(id: Int): Outcome<Unit> = Outcome.Err("unsupported")
    suspend fun updateFolderOrder(order: List<Int>): Outcome<Unit> = Outcome.Err("unsupported")

    /**
     * One page of a folder's dialogs (`messages.getDialogs` with `folder_id`).
     * The main list keeps using [loadMoreChats]; a folder pages its own stream so a rule-based
     * folder can grow past whatever the main page happened to contain.
     */
    suspend fun loadMoreFolderChats(
        folderId: Int,
        offsetDate: Int,
        offsetId: Int,
        offsetPeerId: Long,
    ): Outcome<List<Chat>> = Outcome.Err("unsupported")

    suspend fun readHistory(chatId: PeerId, maxId: Int): Outcome<Unit>

    /** https://core.telegram.org/method/messages.markDialogUnread */
    suspend fun markDialogUnread(chatId: PeerId, unread: Boolean): Outcome<Unit> =
        Outcome.Err("unsupported")

    suspend fun readDiscussion(chatId: PeerId, msgId: Int, readMaxId: Int): Outcome<Unit> =
        Outcome.Err("unsupported")

    suspend fun setTyping(chatId: PeerId, typing: Boolean): Outcome<Unit>
    suspend fun updateStatus(offline: Boolean): Outcome<Unit> = Outcome.Ok(Unit)
    suspend fun getForumTopics(
        chatId: PeerId,
        offsetDate: Int = 0,
        offsetId: Int = 0,
        offsetTopic: Int = 0,
        limit: Int = 40,
    ): Outcome<ForumTopicsPage> = Outcome.Err("unsupported")

    suspend fun getForumTopicsById(
        chatId: PeerId,
        topicIds: List<Int>,
    ): Outcome<ForumTopicsPage> = Outcome.Err("unsupported")

    suspend fun getGroupAdminTags(chatId: PeerId): Outcome<Map<PeerId, String>> =
        Outcome.Ok(emptyMap())
}
