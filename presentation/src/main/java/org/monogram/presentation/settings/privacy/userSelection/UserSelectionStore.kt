package org.monogram.presentation.settings.privacy.userSelection

import com.arkivanov.mvikotlin.core.store.Store

interface UserSelectionStore :
    Store<UserSelectionStore.Intent, UserSelectionComponent.State, UserSelectionStore.Label> {

    sealed class Intent {
        object BackClicked : Intent()
        data class SearchQueryChanged(val query: String) : Intent()
        data class UserClicked(val userId: Long) : Intent()
        data class UpdateState(val state: UserSelectionComponent.State) : Intent()
    }

    sealed class Label {
        object Back : Label()
    }
}
