package org.monogram.presentation.settings.folders

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import org.monogram.presentation.settings.folders.FoldersStore.Intent
import org.monogram.presentation.settings.folders.FoldersStore.Label

class FoldersStoreFactory(
    private val storeFactory: StoreFactory,
    private val component: DefaultFoldersComponent
) {

    fun create(): FoldersStore =
        object : FoldersStore, Store<Intent, FoldersComponent.State, Label> by storeFactory.create(
            name = "FoldersStore",
            initialState = component.state.value,
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl
        ) {}

    private inner class ExecutorImpl : CoroutineExecutor<Intent, Nothing, FoldersComponent.State, Message, Label>() {
        override fun executeIntent(intent: Intent) {
            when (intent) {
                Intent.BackClicked -> component.onBackClicked()
                Intent.CreateFolderClicked -> component.onCreateFolderClicked()
                is Intent.FolderClicked -> component.onFolderClicked(intent.folderId)
                is Intent.DeleteFolder -> component.onDeleteFolder(intent.folderId)
                is Intent.EditFolder -> component.onEditFolder(
                    intent.folderId,
                    intent.newTitle,
                    intent.iconName,
                    intent.includedChatIds
                )

                is Intent.AddFolder -> component.onAddFolder(intent.title, intent.iconName, intent.includedChatIds)
                is Intent.MoveFolder -> component.onMoveFolder(intent.fromIndex, intent.toIndex)
                is Intent.SearchChats -> component.onSearchChats(intent.query)
                is Intent.UpdateState -> dispatch(Message.UpdateState(intent.state))
            }
        }
    }

    private object ReducerImpl : Reducer<FoldersComponent.State, Message> {
        override fun FoldersComponent.State.reduce(msg: Message): FoldersComponent.State =
            when (msg) {
                is Message.UpdateState -> msg.state
            }
    }

    sealed class Message {
        data class UpdateState(val state: FoldersComponent.State) : Message()
    }
}
