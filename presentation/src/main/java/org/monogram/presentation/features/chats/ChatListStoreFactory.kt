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
            initialState = component._state.value,
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl
        ) {}

    private inner class ExecutorImpl : CoroutineExecutor<Intent, Nothing, ChatListComponent.State, Message, Label>() {
        override fun executeIntent(intent: Intent) {
            when (intent) {
                is Intent.ChatClicked -> component.handleChatClicked(intent.id)?.let(::publish)
                is Intent.ProfileClicked -> publish(Label.OpenProfile(intent.id))
                is Intent.MessageClicked -> component.handleMessageClicked(intent.chatId, intent.messageId)?.let(::publish)
                Intent.SettingsClicked -> publish(Label.OpenSettings)
                is Intent.FolderClicked -> component.handleFolderClicked(intent.id)
                is Intent.LoadMore -> component.handleLoadMore(intent.folderId)
                Intent.LoadMoreMessages -> component.handleLoadMoreMessages()
                is Intent.ChatLongClicked -> component.handleChatLongClicked(intent.id)
                Intent.ClearSelection -> component.handleClearSelection()
                Intent.RetryConnection -> component.handleRetryConnection()
                Intent.SearchToggle -> component.handleSearchToggle()
                is Intent.SearchQueryChange -> component.handleSearchQueryChange(intent.query)
                is Intent.SetEmojiStatus -> component.handleSetEmojiStatus(intent.customEmojiId, intent.statusPath)
                Intent.ClearSearchHistory -> component.handleClearSearchHistory()
                is Intent.RemoveSearchHistoryItem -> component.handleRemoveSearchHistoryItem(intent.chatId)
                is Intent.MuteSelected -> component.handleMuteSelected(intent.mute)
                is Intent.ArchiveSelected -> component.handleArchiveSelected(intent.archive)
                Intent.PinSelected -> component.handlePinSelected()
                Intent.ToggleReadSelected -> component.handleToggleReadSelected()
                Intent.DeleteSelected -> component.handleDeleteSelected()
                Intent.ArchivePinToggle -> component.handleArchivePinToggle()
                Intent.ConfirmForwarding -> component.handleConfirmForwarding()?.let(::publish)
                Intent.NewChatClicked -> publish(Label.OpenNewChat)
                Intent.ProxySettingsClicked -> publish(Label.OpenProxySettings)
                Intent.EditFoldersClicked -> publish(Label.EditFolders())
                is Intent.DeleteFolder -> component.handleDeleteFolder(intent.folderId)
                is Intent.EditFolder -> component.handleEditFolder(intent.folderId)?.let(::publish)
                is Intent.OpenInstantView -> component.handleOpenInstantView(intent.url)
                Intent.DismissInstantView -> component.handleDismissInstantView()
                is Intent.OpenWebApp -> component.handleOpenWebApp(intent.url, intent.botUserId, intent.botName)
                Intent.DismissWebApp -> component.handleDismissWebApp()
                is Intent.OpenWebView -> component.handleOpenWebView(intent.url)
                Intent.DismissWebView -> component.handleDismissWebView()
                Intent.UpdateClicked -> component.handleUpdateClicked()?.let(::publish)
                is Intent.UpdateScrollPosition -> component.handleUpdateScrollPosition(
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
