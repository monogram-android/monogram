package org.monogram.feature.chats

import com.arkivanov.mvikotlin.core.store.Store
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.Chat
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.SearchPeer

interface ChatsStore : Store<ChatsStore.Intent, ChatsStore.State, Nothing> {
    sealed interface Intent {
        data object Refresh : Intent
        data object LoadMore : Intent

        /** The list switched to a folder (`null` = All chats); paging follows that folder. */
        data class FolderSelected(val folderId: Int?) : Intent

        /** Manual unread mark toggle (`messages.markDialogUnread`). */
        data class MarkUnread(val chatId: PeerId, val unread: Boolean) : Intent
        data class QueryChanged(val value: String) : Intent
        data object RetrySearch : Intent

        /** Marks whole chats as read (folder menus); unread chats only. */
        data class MarkRead(val chatIds: List<PeerId>) : Intent
    }

    data class State(
        val chats: List<Chat> = emptyList(),
        val query: String = "",
        val loading: Boolean = false,
        val syncing: Boolean = false,
        val loadingMore: Boolean = false,
        val hasMore: Boolean = true,
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
    )
}
