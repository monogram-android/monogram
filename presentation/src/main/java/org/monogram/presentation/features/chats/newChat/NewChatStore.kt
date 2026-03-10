package org.monogram.presentation.features.chats.newChat

import com.arkivanov.mvikotlin.core.store.Store

interface NewChatStore : Store<NewChatStore.Intent, NewChatComponent.State, NewChatStore.Label> {

    sealed class Intent {
        object Back : Intent()
        data class UserClicked(val userId: Long) : Intent()
        data class SearchQueryChange(val query: String) : Intent()
        object CreateGroup : Intent()
        object CreateChannel : Intent()
        data class TitleChange(val title: String) : Intent()
        data class DescriptionChange(val description: String) : Intent()
        data class PhotoSelected(val path: String?) : Intent()
        data class ToggleUserSelection(val userId: Long) : Intent()
        data class AutoDeleteTimeChange(val seconds: Int) : Intent()
        object ConfirmCreate : Intent()
        object StepBack : Intent()
        data class UpdateState(val state: NewChatComponent.State) : Intent()
    }

    sealed class Label {
        object Back : Label()
        data class UserClicked(val userId: Long) : Label()
    }
}
