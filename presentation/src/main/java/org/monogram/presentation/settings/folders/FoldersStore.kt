package org.monogram.presentation.settings.folders

import com.arkivanov.mvikotlin.core.store.Store

interface FoldersStore : Store<FoldersStore.Intent, FoldersComponent.State, FoldersStore.Label> {

    sealed class Intent {
        object BackClicked : Intent()
        object CreateFolderClicked : Intent()
        data class FolderClicked(val folderId: Int) : Intent()
        data class DeleteFolder(val folderId: Int) : Intent()
        data class EditFolder(
            val folderId: Int,
            val newTitle: String,
            val iconName: String?,
            val includedChatIds: List<Long>
        ) : Intent()

        data class AddFolder(val title: String, val iconName: String?, val includedChatIds: List<Long>) : Intent()
        data class MoveFolder(val fromIndex: Int, val toIndex: Int) : Intent()
        data class SearchChats(val query: String) : Intent()
        data class UpdateState(val state: FoldersComponent.State) : Intent()
    }

    sealed class Label {
        object Back : Label()
    }
}
