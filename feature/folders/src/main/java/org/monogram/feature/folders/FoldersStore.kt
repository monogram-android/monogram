package org.monogram.feature.folders

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.ARCHIVE_FOLDER_ID
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.contains
import org.monogram.core.models.isMigratedServicePlaceholder
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate

interface FoldersStore : Store<FoldersStore.Intent, FoldersStore.State, Nothing> {
    sealed interface Intent {
        data object Refresh : Intent
        data object HydrateChats : Intent

        /** Reorder folders, including All chats (`id == 0`). Indices address [State.reorderableFolders]. */
        data class Move(val from: Int, val to: Int) : Intent
        data class Save(val folder: Folder) : Intent
        data class Delete(val id: Int) : Intent
    }

    data class State(
        val folders: List<Folder> = emptyList(),
        val chats: List<Chat> = emptyList(),
        val order: List<Int> = emptyList(),
        val loading: Boolean = false,
        val error: TelegramError? = null,
        val fromCache: Boolean = false,
        /** False until the cache or the network has answered, so "empty" means empty. */
        val loaded: Boolean = false,
    ) {
        val reorderableFolders: List<Folder> get() = ordered.filter { it.id != ARCHIVE_FOLDER_ID }
        val userFolders: List<Folder> get() = ordered.filter { it.id > 1 }

        /** "All chats" is synthetic, so its count comes from the same cache as everything else. */
        val allChatsCount: Int get() = chats.count { !it.archived }

        fun count(folder: Folder): Int = chats.count { folder.contains(it) }

        private val ordered: List<Folder>
            get() {
                val position = order.withIndex().associate { it.value to it.index }
                return folders.sortedBy { position[it.id] ?: Int.MAX_VALUE }
            }
    }
}

internal fun isSystem(folder: Folder): Boolean = folder.id <= 0

private val AllChatsFolder = Folder(id = 0, title = "", excludeArchived = true)

private fun foldersWithAllChats(folders: List<Folder>): List<Folder> {
    val incoming = folders.filter { it.id != ARCHIVE_FOLDER_ID }
    return if (incoming.any { it.id == 0 }) incoming else listOf(AllChatsFolder) + incoming
}

internal class FoldersStoreFactory(
    private val storeFactory: StoreFactory,
    private val client: MtprotoClient,
    private val warmup: OfflineWarmup?,
) {
    fun create(): FoldersStore =
        object :
            FoldersStore,
            Store<FoldersStore.Intent, FoldersStore.State, Nothing> by storeFactory.create(
                name = "FoldersStore",
                initialState = FoldersStore.State(),
                bootstrapper = SimpleBootstrapper(Unit),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private sealed interface Msg {
        data class Loading(val value: Boolean) : Msg
        data class Folders(val value: List<Folder>, val fromCache: Boolean) : Msg
        data class Chats(val value: List<Chat>) : Msg
        data class Error(val value: TelegramError?) : Msg
        data object Loaded : Msg
        data class Move(val from: Int, val to: Int) : Msg
        data class Save(val folder: Folder) : Msg
        data class Delete(val id: Int) : Msg
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<FoldersStore.Intent, Unit, FoldersStore.State, Msg, Nothing>() {
        /** Newest arrangement not yet sent; replaced by every [persistOrder] caller. */
        @Volatile
        private var pendingOrder: List<Int>? = null

        private val orderPush = Mutex()

        override fun executeAction(action: Unit) {
            refresh()
            client.updates()
                .onEach { update ->
                    if (update is MtprotoUpdate.FoldersChanged) {
                        dispatch(Msg.Folders(update.folders, fromCache = false))
                    }
                }
                .launchIn(scope)
        }

        override fun executeIntent(intent: FoldersStore.Intent) {
            when (intent) {
                FoldersStore.Intent.Refresh -> refresh()
                FoldersStore.Intent.HydrateChats -> scope.launch { loadChats() }
                is FoldersStore.Intent.Move -> {
                    dispatch(Msg.Move(intent.from, intent.to))
                    persistOrder()
                }
                is FoldersStore.Intent.Save -> saveFolder(intent.folder)
                is FoldersStore.Intent.Delete -> deleteFolder(intent.id)
            }
        }

        private suspend fun loadChats() {
            val chats = warmup?.chats().orEmpty().filterNot(Chat::isMigratedServicePlaceholder)
            dispatch(Msg.Chats(chats))
        }

        private fun refresh() {
            dispatch(Msg.Error(null))
            scope.launch {
                val cached = warmup?.folders().orEmpty()
                if (cached.isNotEmpty()) {
                    dispatch(Msg.Folders(cached, fromCache = true))
                    dispatch(Msg.Loading(false))
                } else {
                    dispatch(Msg.Loading(true))
                }
                when (val result = client.getFolders()) {
                    is Outcome.Ok -> {
                        dispatch(Msg.Folders(result.value, fromCache = false))
                        warmup?.replaceFolders(result.value)
                        AppLog.api(
                            "folders",
                            "fetched count=${result.value.size} " +
                                "ids=${result.value.joinToString(",") { it.id.toString() }}",
                        )
                    }
                    is Outcome.Err -> {
                        if (state().folders.isEmpty()) {
                            dispatch(Msg.Error(result.telegramError))
                        } else {
                            dispatch(Msg.Loaded)
                        }
                    }
                }
                dispatch(Msg.Loading(false))
            }
        }

        private fun saveFolder(folder: Folder) {
            scope.launch {
                when (val result = client.updateFolder(folder)) {
                    is Outcome.Ok -> {
                        dispatch(Msg.Save(folder))
                        warmup?.replaceFolders(state().folders)
                    }
                    is Outcome.Err -> dispatch(Msg.Error(result.telegramError))
                }
            }
        }

        private fun deleteFolder(id: Int) {
            scope.launch {
                when (val result = client.deleteFolder(id)) {
                    is Outcome.Ok -> {
                        dispatch(Msg.Delete(id))
                        warmup?.replaceFolders(state().folders)
                    }
                    is Outcome.Err -> dispatch(Msg.Error(result.telegramError))
                }
            }
        }

        /** Caches the arrangement first, then pushes the newest one; a drag emits a move per row. */
        private fun persistOrder() {
            val folders = state().folders
            val order = state().order
            scope.launch {
                warmup?.replaceFolders(folders)
                pendingOrder = order
                orderPush.withLock {
                    while (true) {
                        val next = pendingOrder ?: break
                        pendingOrder = null
                        when (val result = client.updateFolderOrder(next)) {
                            is Outcome.Ok -> Unit
                            is Outcome.Err -> dispatch(Msg.Error(result.telegramError))
                        }
                    }
                }
            }
        }
    }

    private object ReducerImpl : Reducer<FoldersStore.State, Msg> {
        override fun FoldersStore.State.reduce(msg: Msg): FoldersStore.State = when (msg) {
            is Msg.Loading -> copy(loading = msg.value)
            is Msg.Folders -> {
                val folders = foldersWithAllChats(msg.value)
                copy(
                    folders = folders,
                    fromCache = msg.fromCache,
                    loaded = true,
                    order = folders.map { it.id },
                )
            }
            is Msg.Chats -> copy(chats = msg.value)
            is Msg.Error -> copy(error = msg.value, loaded = true)
            Msg.Loaded -> copy(loaded = true)
            is Msg.Save -> {
                val next = folders.toMutableList()
                val index = next.indexOfFirst { it.id == msg.folder.id }
                if (index >= 0) next[index] = msg.folder else next += msg.folder
                copy(
                    folders = next,
                    order = if (isSystem(msg.folder) || msg.folder.id in order) {
                        order
                    } else {
                        order + msg.folder.id
                    },
                )
            }
            is Msg.Delete -> copy(
                folders = folders.filterNot { it.id == msg.id },
                order = order.filterNot { it == msg.id },
            )
            is Msg.Move -> {
                val next = order.toMutableList()
                val from = msg.from.coerceIn(0, (next.size - 1).coerceAtLeast(0))
                val to = msg.to.coerceIn(0, (next.size - 1).coerceAtLeast(0))
                if (next.isEmpty() || from == to) {
                    this
                } else {
                    next.add(to, next.removeAt(from))
                    val byId = folders.associateBy { it.id }
                    copy(
                        order = next,
                        folders = next.mapNotNull { byId[it] },
                    )
                }
            }
        }
    }
}
