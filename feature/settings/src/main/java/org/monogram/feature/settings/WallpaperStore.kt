package org.monogram.feature.settings

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.monogram.core.common.Outcome
import org.monogram.core.models.Wallpaper

interface WallpaperStore : Store<WallpaperStore.Intent, WallpaperStore.State, Nothing> {
    sealed interface Intent {
        data class Open(val directory: File) : Intent
        data object Retry : Intent
        data class Preview(val wallpaper: Wallpaper) : Intent
        data object Close : Intent
    }

    data class State(
        val wallpapers: List<Wallpaper> = emptyList(),
        val loading: Boolean = false,
        val error: Boolean = false,
        val previews: Map<Long, File> = emptyMap(),
        val failedPreviews: Set<Long> = emptySet(),
    )
}

internal class WallpaperStoreFactory(
    private val factory: StoreFactory,
    private val source: (File) -> WallpaperSource,
) {
    fun create(): WallpaperStore = object : WallpaperStore,
        Store<WallpaperStore.Intent, WallpaperStore.State, Nothing> by factory.create(
            name = "WallpaperStore", initialState = WallpaperStore.State(),
            executorFactory = ::Executor,
            reducer = Reducer<WallpaperStore.State, Msg> { msg ->
                when (msg) {
                    Msg.Loading -> copy(loading = true, error = false)
                    Msg.Failed -> copy(loading = false, error = true)
                    Msg.Stopped -> copy(loading = false)
                    is Msg.Loaded -> copy(wallpapers = msg.wallpapers, loading = msg.refreshing, error = false)
                    is Msg.Preview -> copy(previews = previews + (msg.id to msg.file), failedPreviews = failedPreviews - msg.id)
                    is Msg.PreviewFailed -> copy(failedPreviews = failedPreviews + msg.id)
                    is Msg.PreviewLoading -> copy(failedPreviews = failedPreviews - msg.id)
                }
            },
        ) {}

    private sealed interface Msg {
        data object Loading : Msg
        data object Failed : Msg
        data object Stopped : Msg
        data class Loaded(val wallpapers: List<Wallpaper>, val refreshing: Boolean) : Msg
        data class Preview(val id: Long, val file: File) : Msg
        data class PreviewLoading(val id: Long) : Msg
        data class PreviewFailed(val id: Long) : Msg
    }

    private inner class Executor : CoroutineExecutor<WallpaperStore.Intent, Nothing, WallpaperStore.State, Msg, Nothing>() {
        private var repository: WallpaperSource? = null
        private var load: Job? = null
        private val previews = mutableMapOf<Long, Job>()
        private val permits = Semaphore(2)

        override fun executeIntent(intent: WallpaperStore.Intent) {
            when (intent) {
                is WallpaperStore.Intent.Open -> {
                    repository = source(intent.directory)
                    refresh()
                }
                WallpaperStore.Intent.Retry -> refresh()
                is WallpaperStore.Intent.Preview -> preview(intent.wallpaper)
                WallpaperStore.Intent.Close -> {
                    load?.cancel()
                    previews.values.toList().forEach(Job::cancel)
                    previews.clear()
                    dispatch(Msg.Stopped)
                }
            }
        }

        private fun refresh() {
            val repo = repository ?: return
            if (load?.isActive == true) return
            dispatch(Msg.Loading)
            load = scope.launch {
                try {
                    val cached = repo.cachedCatalog()
                    if (cached != null) dispatch(Msg.Loaded(cached.wallpapers, refreshing = true))
                    when (val result = repo.refresh(cached?.hash ?: 0)) {
                        is Outcome.Ok -> dispatch(Msg.Loaded(
                            if (result.value.notModified) cached?.wallpapers ?: state().wallpapers else result.value.wallpapers,
                            refreshing = false,
                        ))
                        is Outcome.Err -> dispatch(Msg.Failed)
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { dispatch(Msg.Failed) }
            }
        }

        private fun preview(wallpaper: Wallpaper) {
            val repo = repository ?: return
            val id = wallpaper.id
            if (previews[id]?.isActive == true || state().previews[id]?.isFile == true) return
            dispatch(Msg.PreviewLoading(id))
            previews[id] = scope.launch {
                try {
                    when (val result = permits.withPermit { repo.preview(wallpaper) }) {
                        is Outcome.Ok -> dispatch(Msg.Preview(id, result.value))
                        is Outcome.Err -> dispatch(Msg.PreviewFailed(id))
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { dispatch(Msg.PreviewFailed(id)) }
                finally {
                    if (previews[id] === kotlinx.coroutines.currentCoroutineContext()[Job]) previews.remove(id)
                }
            }
        }
    }
}
