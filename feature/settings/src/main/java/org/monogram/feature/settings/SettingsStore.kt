package org.monogram.feature.settings

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.monogram.core.common.Outcome
import org.monogram.core.common.push.PushRegistration
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.network.bridge.MtprotoClient
import org.monogram.core.ui.ImageCache
import org.monogram.network.http.MediaRepository

interface SettingsStore : Store<SettingsStore.Intent, SettingsStore.State, SettingsStore.Label> {
    sealed interface Intent {
        data object Refresh : Intent
        data object ClearCache : Intent
        data class ClearChatCache(val chatId: Long) : Intent
        data class ClearKindCache(val kind: String) : Intent
        data object Logout : Intent
    }

    data class CacheChatRow(
        val chatId: Long,
        val title: String,
        val bytes: Long,
    )

    data class State(
        val profile: Profile? = null,
        val appVersion: String,
        val buildStamp: String,
        val nativeVersion: String,
        val cacheBytes: Long = 0L,
        val cacheByKind: Map<String, Long> = emptyMap(),
        val cacheChats: List<CacheChatRow> = emptyList(),
        val cacheMessage: String? = null,
        val loading: Boolean = false,
        val loggingOut: Boolean = false,
        val error: TelegramError? = null,
    )

    sealed interface Label {
        data object LoggedOut : Label
    }
}

internal class SettingsStoreFactory(
    private val storeFactory: StoreFactory,
    private val client: MtprotoClient,
    private val sessionStore: SessionMetadataStore?,
    private val warmup: OfflineWarmup?,
    private val mediaRepository: MediaRepository?,
    private val appVersion: String,
    private val buildStamp: String,
    private val pushRegistration: PushRegistration?,
) {
    fun create(): SettingsStore =
        object :
            SettingsStore,
            Store<SettingsStore.Intent, SettingsStore.State, SettingsStore.Label> by storeFactory.create(
                name = "SettingsStore",
                initialState = SettingsStore.State(
                    appVersion = appVersion,
                    buildStamp = buildStamp,
                    nativeVersion = client.libraryVersion(),
                    cacheBytes = mediaRepository?.cacheSizeBytes() ?: 0L,
                ),
                bootstrapper = SimpleBootstrapper(Unit),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private sealed interface Msg {
        data class Loading(val value: Boolean) : Msg
        data class LoggingOut(val value: Boolean) : Msg
        data class ProfileLoaded(val profile: Profile?) : Msg
        data class CacheBytes(val value: Long) : Msg
        data class CacheByKind(val value: Map<String, Long>) : Msg
        data class CacheChats(val value: List<SettingsStore.CacheChatRow>) : Msg
        data class CacheMessage(val value: String?) : Msg
        data class Error(val value: TelegramError?) : Msg
        data class NativeVersion(val value: String) : Msg
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<SettingsStore.Intent, Unit, SettingsStore.State, Msg, SettingsStore.Label>() {
        override fun executeAction(action: Unit) {
            refresh()
        }

        override fun executeIntent(intent: SettingsStore.Intent) {
            when (intent) {
                SettingsStore.Intent.Refresh -> refresh()
                SettingsStore.Intent.ClearCache -> clearCache()
                is SettingsStore.Intent.ClearChatCache -> {
                    mediaRepository?.clearChatCache(intent.chatId)
                    refreshCache()
                }
                is SettingsStore.Intent.ClearKindCache -> {
                    mediaRepository?.clearKindCache(intent.kind)
                    refreshCache()
                }
                SettingsStore.Intent.Logout -> logout()
            }
        }

        private fun refresh() {
            dispatch(Msg.Error(null))
            dispatch(Msg.NativeVersion(client.libraryVersion()))
            refreshCache()
            scope.launch {
                val cachedId = sessionStore?.readAuthorizedUserId()?.value
                if (cachedId != null) {
                    sessionStore.readProfile(cachedId)?.let { dispatch(Msg.ProfileLoaded(it)) }
                }
                if (state().profile == null) dispatch(Msg.Loading(true))
                when (val result = client.getProfile(PeerId(0L))) {
                    is Outcome.Ok -> {
                        dispatch(Msg.ProfileLoaded(result.value))
                        sessionStore?.upsertProfile(result.value)
                    }
                    is Outcome.Err -> {
                        if (state().profile == null) {
                            dispatch(Msg.Error(result.telegramError))
                        }
                    }
                }
                dispatch(Msg.Loading(false))
            }
        }

        private fun refreshCache() {
            dispatch(Msg.CacheBytes(mediaRepository?.cacheSizeBytes() ?: 0L))
            dispatch(Msg.CacheByKind(mediaRepository?.cacheByKind().orEmpty()))
            scope.launch {
                val titles = warmup?.chats()?.associate { it.id.value to it.title }.orEmpty()
                val chats = mediaRepository?.cacheByChat().orEmpty()
                    .map { (id, bytes) ->
                        SettingsStore.CacheChatRow(
                            chatId = id,
                            title = titles[id] ?: id.toString(),
                            bytes = bytes,
                        )
                    }
                    .sortedByDescending { it.bytes }
                dispatch(Msg.CacheChats(chats))
            }
        }

        private fun clearCache() {
            val freed = mediaRepository?.clearCache() ?: 0L
            ImageCache.clear()
            refreshCache()
            dispatch(Msg.CacheMessage("cleared:$freed"))
        }

        private fun logout() {
            if (state().loggingOut) return
            dispatch(Msg.LoggingOut(true))
            dispatch(Msg.Loading(true))
            scope.launch {
                try {
                    when (val result = client.logout()) {
                        is Outcome.Err -> dispatch(Msg.Error(result.telegramError))
                        is Outcome.Ok -> pushRegistration?.onLogout()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    dispatch(Msg.Error(TelegramError.parse(e.message ?: "logout failed")))
                }
                warmup?.clearAccountCache()
                sessionStore?.clearSession()
                mediaRepository?.clearCache()
                ImageCache.clear()
                publish(SettingsStore.Label.LoggedOut)
                dispatch(Msg.Loading(false))
                dispatch(Msg.LoggingOut(false))
            }
        }
    }

    private object ReducerImpl : Reducer<SettingsStore.State, Msg> {
        override fun SettingsStore.State.reduce(msg: Msg): SettingsStore.State = when (msg) {
            is Msg.Loading -> copy(loading = msg.value)
            is Msg.LoggingOut -> copy(loggingOut = msg.value)
            is Msg.ProfileLoaded -> copy(profile = msg.profile)
            is Msg.CacheBytes -> copy(cacheBytes = msg.value)
            is Msg.CacheByKind -> copy(cacheByKind = msg.value)
            is Msg.CacheChats -> copy(cacheChats = msg.value)
            is Msg.CacheMessage -> copy(cacheMessage = msg.value)
            is Msg.Error -> copy(error = msg.value)
            is Msg.NativeVersion -> copy(nativeVersion = msg.value)
        }
    }
}
