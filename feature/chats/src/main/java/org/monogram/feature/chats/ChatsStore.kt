package org.monogram.feature.chats

import com.arkivanov.mvikotlin.core.store.Store
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.SearchPeer
import org.monogram.network.bridge.ConnectionStatus

interface ChatsStore : Store<ChatsStore.Intent, ChatsStore.State, Nothing> {
    sealed interface Intent {
        data class ConnectionStatusChanged(
            val value: ConnectionStatus,
        ) : Intent

        data object Refresh : Intent

        data object LoadMore : Intent

        data class FolderUnderfilled(
            val folderId: Int,
            val remainingPages: Int,
        ) : Intent

        /** The list switched to a folder (`null` = All chats); paging follows that folder. */
        data class FolderSelected(
            val folderId: Int?,
            val folder: Folder? = null,
        ) : Intent

        /** Manual unread mark toggle (`messages.markDialogUnread`). */
        data class MarkUnread(
            val chatId: PeerId,
            val unread: Boolean,
        ) : Intent

        data class QueryChanged(
            val value: String,
        ) : Intent

        data object RetrySearch : Intent

        /** Marks whole chats as read (folder menus); unread chats only. */
        data class MarkRead(
            val chatIds: List<PeerId>,
        ) : Intent
    }

    data class FolderActivation(
        val folderId: Int,
        val cachedMembers: List<Chat>,
        val remainingPages: Int,
        val explicitIds: Set<PeerId>,
        val hasRules: Boolean,
    ) {
        internal val explicitComplete: Boolean get() = !hasRules && cachedMembers.size == explicitIds.size
    }

    data class State(
        val chats: List<Chat> = emptyList(),
        val query: String = "",
        val loading: Boolean = false,
        val syncing: Boolean = false,
        val loadingMore: Boolean = false,
        val hasMore: Boolean = true,
        val folderActivation: FolderActivation? = null,
        val error: TelegramError? = null,
        val fromCache: Boolean = false,
        val self: Profile? = null,
        val searchPeople: List<SearchPeer> = emptyList(),
        val searchChats: List<SearchPeer> = emptyList(),
        val searchMessages: List<Message> = emptyList(),
        val searchLoading: Boolean = false,
        val searchLoadingMore: Boolean = false,
        val searchHasMore: Boolean = false,
        val searchError: TelegramError? = null,
        val connectionStatus: ConnectionStatus = ConnectionStatus.Idle,
    ) {
        internal fun chat(id: PeerId): Chat? = chats.firstOrNull { it.id == id }
            ?: folderActivation?.cachedMembers?.firstOrNull { it.id == id }
    }
}
