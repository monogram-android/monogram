package org.monogram.presentation.settings.privacy.userSelection

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import org.monogram.presentation.settings.privacy.userSelection.UserSelectionStore.Intent
import org.monogram.presentation.settings.privacy.userSelection.UserSelectionStore.Label

class UserSelectionStoreFactory(
    private val storeFactory: StoreFactory,
    private val component: DefaultUserSelectionComponent
) {

    fun create(): UserSelectionStore =
        object : UserSelectionStore, Store<Intent, UserSelectionComponent.State, Label> by storeFactory.create(
            name = "UserSelectionStore",
            initialState = component.state.value,
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl
        ) {}

    private inner class ExecutorImpl :
        CoroutineExecutor<Intent, Nothing, UserSelectionComponent.State, Message, Label>() {
        override fun executeIntent(intent: Intent) {
            when (intent) {
                Intent.BackClicked -> component.onBackClicked()
                is Intent.SearchQueryChanged -> component.onSearchQueryChanged(intent.query)
                is Intent.UserClicked -> component.onUserClicked(intent.userId)
                is Intent.UpdateState -> dispatch(Message.UpdateState(intent.state))
            }
        }
    }

    private object ReducerImpl : Reducer<UserSelectionComponent.State, Message> {
        override fun UserSelectionComponent.State.reduce(msg: Message): UserSelectionComponent.State =
            when (msg) {
                is Message.UpdateState -> msg.state
            }
    }

    sealed class Message {
        data class UpdateState(val state: UserSelectionComponent.State) : Message()
    }
}
