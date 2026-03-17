package org.monogram.presentation.settings.folders

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.FolderModel
import org.monogram.domain.repository.ChatsListRepository
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.root.AppComponentContext

interface FoldersComponent {
    val state: Value<State>
    val videoPlayerPool: VideoPlayerPool
    fun onBackClicked()
    fun onCreateFolderClicked()
    fun onFolderClicked(folderId: Int)
    fun onDeleteFolder(folderId: Int)
    fun onEditFolder(folderId: Int, newTitle: String, iconName: String?, includedChatIds: List<Long>)
    fun onAddFolder(title: String, iconName: String?, includedChatIds: List<Long>)
    fun onMoveFolder(fromIndex: Int, toIndex: Int)
    fun onSearchChats(query: String)

    data class State(
        val folders: List<FolderModel> = emptyList(),
        val isLoading: Boolean = false,
        val showAddFolderDialog: Boolean = false,
        val showEditFolderDialog: Boolean = false,
        val selectedFolder: FolderModel? = null,
        val availableChats: List<ChatModel> = emptyList(),
        val selectedChatIds: List<Long> = emptyList()
    )
}

class DefaultFoldersComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit
) : FoldersComponent, AppComponentContext by context {

    private val repository: ChatsListRepository = container.repositories.chatsListRepository
    override val videoPlayerPool: VideoPlayerPool = container.utils.videoPlayerPool

    private val _state = MutableValue(FoldersComponent.State())
    override val state: Value<FoldersComponent.State> = _state

    private val scope = componentScope

    init {
        repository.foldersFlow.onEach { folders ->
            _state.update { it.copy(folders = folders) }
        }.launchIn(scope)

        repository.chatListFlow.onEach { chats ->
            _state.update { it.copy(availableChats = chats) }
        }.launchIn(scope)
    }

    override fun onBackClicked() {
        onBack()
    }

    override fun onCreateFolderClicked() {
        _state.update { it.copy(showAddFolderDialog = true, selectedChatIds = emptyList()) }
    }

    override fun onFolderClicked(folderId: Int) {
        val folder = _state.value.folders.find { it.id == folderId }
        if (folder != null) {
            _state.update {
                it.copy(
                    showEditFolderDialog = true,
                    selectedFolder = folder,
                    selectedChatIds = folder.includedChatIds
                )
            }
        }
    }

    override fun onDeleteFolder(folderId: Int) {
        scope.launch {
            repository.deleteFolder(folderId)
            _state.update { it.copy(showEditFolderDialog = false, selectedFolder = null) }
        }
    }

    override fun onEditFolder(folderId: Int, newTitle: String, iconName: String?, includedChatIds: List<Long>) {
        scope.launch {
            repository.updateFolder(folderId, newTitle, iconName, includedChatIds)
            _state.update { it.copy(showEditFolderDialog = false, selectedFolder = null) }
        }
    }

    override fun onAddFolder(title: String, iconName: String?, includedChatIds: List<Long>) {
        scope.launch {
            repository.createFolder(title, iconName, includedChatIds)
            _state.update { it.copy(showAddFolderDialog = false) }
        }
    }

    override fun onMoveFolder(fromIndex: Int, toIndex: Int) {
        val folders = _state.value.folders.toMutableList()
        if (fromIndex !in folders.indices || toIndex !in folders.indices) return

        val folder = folders.removeAt(fromIndex)
        folders.add(toIndex, folder)

        _state.update { it.copy(folders = folders) }

        scope.launch {
            val newUserFolderIds = folders
                .filter { it.id >= 0 }
                .map { it.id }

            repository.reorderFolders(newUserFolderIds)
        }
    }

    override fun onSearchChats(query: String) {
        scope.launch {
            val results = repository.searchChats(query)
            _state.update { it.copy(availableChats = results) }
        }
    }

    fun dismissDialog() {
        _state.update {
            it.copy(showAddFolderDialog = false, showEditFolderDialog = false, selectedFolder = null)
        }
    }
}
