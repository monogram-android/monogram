package org.monogram.feature.settings

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.pushNew
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.models.PeerId
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.http.MediaRepository
import org.monogram.core.common.push.PushRegistration
import org.monogram.core.common.push.NotificationLocalStore

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    private val client: MtprotoClient,
    sessionStore: SessionMetadataStore?,
    warmup: OfflineWarmup?,
    val mediaRepository: MediaRepository?,
    appVersion: String,
    buildStamp: String,
    private val onBack: () -> Unit,
    private val onOpenProfile: (PeerId) -> Unit,
    private val onLoggedOut: () -> Unit,
    val pushRegistration: PushRegistration? = null,
    val debugNotifications: Boolean = false,
    notificationLocal: NotificationLocalStore? = null,
    openFolders: Boolean = false,
) : ComponentContext by componentContext {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = instanceKeeper.getStore {
        SettingsStoreFactory(
            storeFactory = storeFactory,
            client = client,
            sessionStore = sessionStore,
            warmup = warmup,
            mediaRepository = mediaRepository,
            appVersion = appVersion,
            buildStamp = buildStamp,
            pushRegistration = pushRegistration,
        ).create()
    }

    val state: StateFlow<SettingsStore.State> = store.stateFlow
    private val notificationsStore = instanceKeeper.getStore("notifications") {
        NotificationsStoreFactory(
            storeFactory,
            client,
            notificationLocal,
            pushRegistration,
            warmup,
        ).create()
    }
    val notifications: StateFlow<NotificationsStore.State> = notificationsStore.stateFlow
    private val wallpaperStore = instanceKeeper.getStore("wallpapers") {
        WallpaperStoreFactory(storeFactory) { WallpaperRepository(it, client) }.create()
    }
    val wallpapers: StateFlow<WallpaperStore.State> = wallpaperStore.stateFlow

    private val pageNavigation = StackNavigation<SettingsPage>()

    val pages: Value<ChildStack<SettingsPage, SettingsPage>> = childStack(
        source = pageNavigation,
        serializer = SettingsPage.serializer(),
        initialConfiguration = if (openFolders) SettingsPage.Folders else SettingsPage.Home,
        childFactory = { page, _ -> page },
    )

    /**
     * Back inside the currently visible page. The folders page installs a handler while an
     * editor is open so the first back press closes the editor instead of leaving Settings.
     */
    private var pageBackHandler: (() -> Boolean)? = null

    fun setPageBackHandler(handler: (() -> Boolean)?) {
        pageBackHandler = handler
    }

    init {
        scope.launch {
            store.labels.collect { label ->
                when (label) {
                    SettingsStore.Label.LoggedOut -> onLoggedOut()
                }
            }
        }
        lifecycle.doOnDestroy { scope.cancel() }
    }

    fun onRefresh() = store.accept(SettingsStore.Intent.Refresh)
    fun onOpenWallpapers(cacheDirectory: java.io.File) = wallpaperStore.accept(WallpaperStore.Intent.Open(
        java.io.File(cacheDirectory, "wallpapers/${state.value.profile?.id?.value ?: 0L}"),
    ))
    fun onRetryWallpapers() = wallpaperStore.accept(WallpaperStore.Intent.Retry)
    fun onPreviewWallpaper(wallpaper: org.monogram.core.models.Wallpaper) =
        wallpaperStore.accept(WallpaperStore.Intent.Preview(wallpaper))
    fun onCloseWallpapers() = wallpaperStore.accept(WallpaperStore.Intent.Close)
    fun onClearCache() = store.accept(SettingsStore.Intent.ClearCache)
    fun onClearChatCache(chatId: Long) = store.accept(SettingsStore.Intent.ClearChatCache(chatId))
    fun onClearKindCache(kind: String) = store.accept(SettingsStore.Intent.ClearKindCache(kind))
    fun onLogout() = store.accept(SettingsStore.Intent.Logout)
    fun onExportDebugStats() = store.accept(SettingsStore.Intent.ExportDebugStats)
    fun onClearDebugStats() = store.accept(SettingsStore.Intent.ClearDebugStats)
    fun openPage(page: SettingsPage) = pageNavigation.pushNew(page)
    fun popPage() {
        if (pageBackHandler?.invoke() == true) return
        pageNavigation.pop()
    }
    fun onBack() = onBack.invoke()
    fun onOpenProfile() = onOpenProfile(PeerId(0L))
    fun onNotification(intent: NotificationsStore.Intent) = notificationsStore.accept(intent)
}
