package org.monogram.feature.folders

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.Folder
import org.monogram.network.bridge.MtprotoClient

@OptIn(ExperimentalCoroutinesApi::class)
class FoldersComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    client: MtprotoClient,
    warmup: OfflineWarmup?,
) : ComponentContext by componentContext {

    private val store = instanceKeeper.getStore {
        FoldersStoreFactory(storeFactory, client, warmup).create()
    }

    val state: StateFlow<FoldersStore.State> = store.stateFlow

    /** The folder being edited. The host screen owns the app bar, so the draft lives here. */
    private val editingState = MutableStateFlow<Folder?>(null)
    val editing: StateFlow<Folder?> = editingState.asStateFlow()

    fun onRefresh() = store.accept(FoldersStore.Intent.Refresh)
    fun onHydrateChats() = store.accept(FoldersStore.Intent.HydrateChats)
    fun onMoveFolder(from: Int, to: Int) =
        store.accept(FoldersStore.Intent.Move(from = from, to = to))
    fun onEditFolder(folder: Folder) {
        editingState.value = folder
    }

    fun onNewFolder() {
        val nextId = (state.value.userFolders.maxOfOrNull { it.id } ?: 1) + 1
        editingState.value = Folder(id = nextId, title = "")
    }

    fun onChangeFolder(folder: Folder) {
        editingState.value = folder
    }

    fun onSaveEdit() {
        val draft = editingState.value ?: return
        editingState.value = null
        store.accept(FoldersStore.Intent.Save(draft))
    }

    fun onDeleteEdit() {
        val draft = editingState.value ?: return
        editingState.value = null
        store.accept(FoldersStore.Intent.Delete(draft.id))
    }

    /** Closes the editor when it is open. Returns true when the back press was consumed. */
    fun onCancelEdit(): Boolean {
        if (editingState.value == null) return false
        editingState.value = null
        return true
    }
}
