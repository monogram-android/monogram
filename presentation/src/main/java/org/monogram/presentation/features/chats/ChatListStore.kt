package org.monogram.presentation.features.chats

import com.arkivanov.mvikotlin.core.store.Store

interface ChatListStore : Store<ChatListStore.Intent, ChatListComponent.State, ChatListStore.Label> {

    sealed class Intent {
        data class ChatClicked(val id: Long) : Intent()
        data class ProfileClicked(val id: Long) : Intent()
        data class MessageClicked(val chatId: Long, val messageId: Long) : Intent()
        object SettingsClicked : Intent()
        data class FolderClicked(val id: Int) : Intent()
        data class LoadMore(val folderId: Int? = null) : Intent()
        object LoadMoreMessages : Intent()
        data class ChatLongClicked(val id: Long) : Intent()
        object ClearSelection : Intent()
        object RetryConnection : Intent()
        object SearchToggle : Intent()
        data class SearchQueryChange(val query: String) : Intent()
        data class SetEmojiStatus(val customEmojiId: Long, val statusPath: String?) : Intent()
        object ClearSearchHistory : Intent()
        data class RemoveSearchHistoryItem(val chatId: Long) : Intent()
        data class MuteSelected(val mute: Boolean) : Intent()
        data class ArchiveSelected(val archive: Boolean) : Intent()
        object PinSelected : Intent()
        object ToggleReadSelected : Intent()
        object DeleteSelected : Intent()
        object ArchivePinToggle : Intent()
        object ConfirmForwarding : Intent()
        object NewChatClicked : Intent()
        object ProxySettingsClicked : Intent()
        object EditFoldersClicked : Intent()
        data class DeleteFolder(val folderId: Int) : Intent()
        data class EditFolder(val folderId: Int) : Intent()
        data class OpenInstantView(val url: String) : Intent()
        object DismissInstantView : Intent()
        data class OpenWebApp(val url: String, val botUserId: Long, val botName: String) : Intent()
        object DismissWebApp : Intent()
        data class OpenWebView(val url: String) : Intent()
        object DismissWebView : Intent()
        object UpdateClicked : Intent()
        data class UpdateScrollPosition(val folderId: Int, val index: Int, val offset: Int) : Intent()
        data class UpdateState(val state: ChatListComponent.State) : Intent()
    }

    sealed class Label {
        data class OpenChat(val chatId: Long, val messageId: Long?) : Label()
        data class OpenProfile(val id: Long) : Label()
        object OpenSettings : Label()
        object OpenProxySettings : Label()
        object OpenNewChat : Label()
        data class ConfirmForward(val selectedChatIds: Set<Long>) : Label()
        data class EditFolders(val folderId: Int? = null) : Label()
    }
}
