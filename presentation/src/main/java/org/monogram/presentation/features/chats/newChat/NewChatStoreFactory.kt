package org.monogram.presentation.features.chats.newChat

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import org.monogram.presentation.features.chats.chatList.DefaultNewChatComponent
import org.monogram.presentation.features.chats.newChat.NewChatStore.Intent
import org.monogram.presentation.features.chats.newChat.NewChatStore.Label

class NewChatStoreFactory(
    private val storeFactory: StoreFactory,
    private val component: DefaultNewChatComponent
) {

    fun create(): NewChatStore =
        object : NewChatStore, Store<Intent, NewChatComponent.State, Label> by storeFactory.create(
            name = "NewChatStore",
            initialState = NewChatComponent.State(),
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl
        ) {}

    private inner class ExecutorImpl : CoroutineExecutor<Intent, Nothing, NewChatComponent.State, Message, Label>() {
        override fun executeIntent(intent: Intent) {
            when (intent) {
                Intent.Back -> component.handleBack()
                is Intent.UserClicked -> component.handleUserClicked(intent.userId)
                is Intent.SearchQueryChange -> component.handleSearchQueryChange(intent.query)
                Intent.CreateGroup -> component.handleCreateGroup()
                Intent.CreateChannel -> component.handleCreateChannel()
                is Intent.TitleChange -> component.handleTitleChange(intent.title)
                is Intent.DescriptionChange -> component.handleDescriptionChange(intent.description)
                is Intent.PhotoSelected -> component.handlePhotoSelected(intent.path)
                is Intent.ToggleUserSelection -> component.handleToggleUserSelection(intent.userId)
                is Intent.AutoDeleteTimeChange -> component.handleAutoDeleteTimeChange(intent.seconds)
                Intent.ConfirmCreate -> component.handleConfirmCreate()
                Intent.StepBack -> component.handleStepBack()
                is Intent.UpdateState -> dispatch(Message.UpdateState(intent.state))
            }
        }
    }

    private object ReducerImpl : Reducer<NewChatComponent.State, Message> {
        override fun NewChatComponent.State.reduce(msg: Message): NewChatComponent.State =
            when (msg) {
                is Message.UpdateState -> msg.state
            }
    }

    sealed class Message {
        data class UpdateState(val state: NewChatComponent.State) : Message()
    }
}
