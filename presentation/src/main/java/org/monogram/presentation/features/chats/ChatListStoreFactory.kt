package org.monogram.presentation.features.chats

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import org.monogram.presentation.features.chats.ChatListStore.Intent
import org.monogram.presentation.features.chats.ChatListStore.Label
import org.monogram.presentation.features.chats.chatList.DefaultChatListComponent

class ChatListStoreFactory(
    private val storeFactory: StoreFactory,
    private val component: DefaultChatListComponent
) {

    fun create(): ChatListStore =
        object : ChatListStore, Store<Intent, ChatListComponent.State, Label> by storeFactory.create(
            name = "ChatListStore",
            initialState = ChatListComponent.State(isForwarding = component.isForwarding),
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl
        ) {}

    private inner class ExecutorImpl : CoroutineExecutor<Intent, Nothing, ChatListComponent.State, Message, Label>() {
        override fun executeIntent(intent: Intent) {
            when (intent) {
                is Intent.ChatClicked -> component.onChatClicked(intent.id)
                is Intent.ProfileClicked -> component.onProfileClicked(intent.id)
                is Intent.MessageClicked -> component.onMessageClicked(intent.chatId, intent.messageId)
                Intent.SettingsClicked -> component.onSettingsClicked()
                is Intent.FolderClicked -> component.onFolderClicked(intent.id)
                is Intent.LoadMore -> component.loadMore(intent.folderId)
                Intent.LoadMoreMessages -> component.loadMoreMessages()
                is Intent.ChatLongClicked -> component.onChatLongClicked(id = intent.id)
                Intent.ClearSelection -> component.clearSelection()
                Intent.RetryConnection -> component.retryConnection()
                Intent.SearchToggle -> component.onSearchToggle()
                is Intent.SearchQueryChange -> component.onSearchQueryChange(intent.query)
                Intent.ClearSearchHistory -> component.onClearSearchHistory()
                is Intent.RemoveSearchHistoryItem -> component.onRemoveSearchHistoryItem(intent.chatId)
                is Intent.MuteSelected -> component.onMuteSelected(intent.mute)
                is Intent.ArchiveSelected -> component.onArchiveSelected(archive = intent.archive)
                Intent.DeleteSelected -> component.onDeleteSelected()
                Intent.ArchivePinToggle -> component.onArchivePinToggle()
                Intent.ConfirmForwarding -> component.onConfirmForwarding()
                Intent.NewChatClicked -> component.onNewChatClicked()
                Intent.ProxySettingsClicked -> component.onProxySettingsClicked()
                is Intent.OpenInstantView -> component.onOpenInstantView(intent.url)
                Intent.DismissInstantView -> component.onDismissInstantView()
                is Intent.OpenWebApp -> component.onOpenWebApp(intent.url, intent.botUserId, intent.botName)
                Intent.DismissWebApp -> component.onDismissWebApp()
                is Intent.OpenWebView -> component.onOpenWebView(intent.url)
                Intent.DismissWebView -> component.onDismissWebView()
                Intent.UpdateClicked -> component.onUpdateClicked()
                is Intent.UpdateScrollPosition -> component.updateScrollPosition(
                    intent.folderId,
                    intent.index,
                    intent.offset
                )

                is Intent.UpdateState -> dispatch(Message.UpdateState(intent.state))
            }
        }
    }

    private object ReducerImpl : Reducer<ChatListComponent.State, Message> {
        override fun ChatListComponent.State.reduce(msg: Message): ChatListComponent.State =
            when (msg) {
                is Message.UpdateState -> msg.state
            }
    }

    sealed class Message {
        data class UpdateState(val state: ChatListComponent.State) : Message()
    }
}
