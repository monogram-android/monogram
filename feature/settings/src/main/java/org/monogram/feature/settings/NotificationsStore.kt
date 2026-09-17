package org.monogram.feature.settings

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch
import org.monogram.core.common.Outcome
import org.monogram.core.common.push.NotificationLocalStore
import org.monogram.core.common.push.PeerNotificationMode
import org.monogram.core.common.push.PushRegistration
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.NotifyException
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PeerId
import org.monogram.core.models.PushDebugState
import org.monogram.network.bridge.MtprotoClient

interface NotificationsStore : Store<NotificationsStore.Intent, NotificationsStore.State, Nothing> {
    sealed interface Intent {
        data object Refresh : Intent
        data class ToggleGlobal(val kind: String, val enabled: Boolean) : Intent
        data class SetPreview(val kind: String, val preview: Boolean) : Intent
        data class ToggleInApp(val key: String, val enabled: Boolean) : Intent
        data class ToggleFolder(val folderId: Int, val muted: Boolean) : Intent
        data class SetSound(val kind: String, val sound: String) : Intent
        data class SetSilent(val kind: String, val silent: Boolean) : Intent
        data class SetCategoryVibrate(val kind: String, val enabled: Boolean) : Intent
        data class SetCategoryLed(val kind: String, val enabled: Boolean) : Intent
        data class SetCategoryPriority(val kind: String, val high: Boolean) : Intent
        data class SetCategoryPopup(val kind: String, val enabled: Boolean) : Intent
        data class ResetException(val chatId: Long) : Intent
        data class AddException(val chatId: Long) : Intent
        data class SetPeerMode(val chatId: Long, val mode: PeerNotificationMode) : Intent
        data class ClearPeerMode(val chatId: Long) : Intent
        data object CycleCallsVibrate : Intent
        data object CycleCallsRingtone : Intent
        data object CycleRepeat : Intent
        data object Reset : Intent
        data object Reregister : Intent
        data class Simulate(val locKey: String) : Intent
        data object RequestPermission : Intent
    }

    /** Local channel preferences of a folder, applied to its `folder_<id>` notification channel. */
    data class FolderPrefs(
        val vibrate: Boolean = true,
        val led: Boolean = true,
        val popup: Boolean = true,
    )

    data class State(
        val users: NotifySettings = NotifySettings(),
        val chats: NotifySettings = NotifySettings(),
        val broadcasts: NotifySettings = NotifySettings(),
        val exceptions: List<NotifyException> = emptyList(),
        val peerModes: Map<Long, PeerNotificationMode> = emptyMap(),
        val chatsById: Map<Long, String> = emptyMap(),
        val addableChats: List<Chat> = emptyList(),
        val folders: List<Folder> = emptyList(),
        val folderPrefs: Map<Int, FolderPrefs> = emptyMap(),
        val mutedFolders: Set<Int> = emptySet(),
        val inAppSound: Boolean = true,
        val inAppVibrate: Boolean = true,
        val inAppPreview: Boolean = true,
        val inChatSound: Boolean = true,
        val inAppPriority: Boolean = true,
        val pinned: Boolean = true,
        val contactJoined: Boolean = true,
        val stories: Boolean = true,
        val reactions: Boolean = true,
        val gifts: Boolean = true,
        val usersVibrate: Boolean = true,
        val usersLed: Boolean = true,
        val usersPriority: Boolean = true,
        val chatsVibrate: Boolean = true,
        val chatsLed: Boolean = true,
        val chatsPriority: Boolean = true,
        val broadcastsVibrate: Boolean = true,
        val broadcastsLed: Boolean = true,
        val broadcastsPriority: Boolean = true,
        val usersPopup: Boolean = true,
        val chatsPopup: Boolean = true,
        val broadcastsPopup: Boolean = true,
        val storiesPopup: Boolean = true,
        val reactionsPopup: Boolean = true,
        val badge: Boolean = true,
        val badgeMuted: Boolean = true,
        val badgeMessages: Boolean = true,
        val repeatMinutes: Int = 0,
        val callsVibrate: String = "default",
        val callsRingtone: String = "default",
        val debug: PushDebugState = PushDebugState(),
        val loading: Boolean = false,
        val message: String? = null,
    )
}

internal class NotificationsStoreFactory(
    private val storeFactory: StoreFactory,
    private val client: MtprotoClient,
    private val local: NotificationLocalStore?,
    private val push: PushRegistration?,
    private val warmup: OfflineWarmup? = null,
) {
    fun create(): NotificationsStore =
        object :
            NotificationsStore,
            Store<NotificationsStore.Intent, NotificationsStore.State, Nothing> by storeFactory.create(
                name = "NotificationsStore",
                initialState = NotificationsStore.State(
                    inAppSound = local?.inAppSound ?: true,
                    inAppVibrate = local?.inAppVibrate ?: true,
                    inAppPreview = local?.inAppPreview ?: true,
                    inChatSound = local?.inChatSound ?: true,
                    inAppPriority = local?.inAppPriority ?: true,
                    pinned = local?.pinnedEnabled ?: true,
                    contactJoined = local?.contactJoinedEnabled ?: true,
                    stories = local?.storiesEnabled ?: true,
                    reactions = local?.reactionsEnabled ?: true,
                    gifts = local?.giftsEnabled ?: true,
                    usersVibrate = local?.categoryVibrate("users") ?: true,
                    usersLed = local?.categoryLed("users") ?: true,
                    usersPriority = local?.categoryPriorityHigh("users") ?: true,
                    chatsVibrate = local?.categoryVibrate("chats") ?: true,
                    chatsLed = local?.categoryLed("chats") ?: true,
                    chatsPriority = local?.categoryPriorityHigh("chats") ?: true,
                    broadcastsVibrate = local?.categoryVibrate("broadcasts") ?: true,
                    broadcastsLed = local?.categoryLed("broadcasts") ?: true,
                    broadcastsPriority = local?.categoryPriorityHigh("broadcasts") ?: true,
                    usersPopup = local?.categoryPopup("users") ?: true,
                    chatsPopup = local?.categoryPopup("chats") ?: true,
                    broadcastsPopup = local?.categoryPopup("broadcasts") ?: true,
                    storiesPopup = local?.categoryPopup("stories") ?: true,
                    reactionsPopup = local?.categoryPopup("reactions") ?: true,
                    callsVibrate = local?.callsVibrate ?: "default",
                    callsRingtone = local?.callsRingtone ?: "default",
                    badge = local?.badgeEnabled ?: true,
                    badgeMuted = local?.badgeMuted ?: true,
                    badgeMessages = local?.badgeMessages ?: true,
                    repeatMinutes = local?.repeatMinutes ?: 0,
                    mutedFolders = local?.mutedFolders().orEmpty(),
                    debug = push?.debugState() ?: PushDebugState(),
                ),
                bootstrapper = SimpleBootstrapper(Unit),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private sealed interface Msg {
        data class Loaded(
            val users: NotifySettings,
            val chats: NotifySettings,
            val broadcasts: NotifySettings,
            val exceptions: List<NotifyException>,
            val folders: List<Folder>,
            val chatsById: Map<Long, String>,
            val addableChats: List<Chat>,
            val peerModes: Map<Long, PeerNotificationMode>,
            val folderPrefs: Map<Int, NotificationsStore.FolderPrefs>,
        ) : Msg
        data class Local(val state: NotificationsStore.State) : Msg
        data class Loading(val value: Boolean) : Msg
        data class Message(val value: String?) : Msg
        data class Debug(val value: PushDebugState) : Msg
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<NotificationsStore.Intent, Unit, NotificationsStore.State, Msg, Nothing>() {
        override fun executeAction(action: Unit) = refresh()

        override fun executeIntent(intent: NotificationsStore.Intent) {
            when (intent) {
                NotificationsStore.Intent.Refresh -> refresh()
                is NotificationsStore.Intent.ToggleGlobal -> toggleGlobal(intent.kind, intent.enabled)
                is NotificationsStore.Intent.SetPreview -> setPreview(intent.kind, intent.preview)
                is NotificationsStore.Intent.ToggleInApp -> toggleInApp(intent.key, intent.enabled)
                is NotificationsStore.Intent.ToggleFolder -> {
                    local?.setFolderMuted(intent.folderId, intent.muted)
                    dispatchLocal()
                }
                is NotificationsStore.Intent.SetSound -> updateKind(intent.kind) { it.copy(sound = intent.sound) }
                is NotificationsStore.Intent.SetSilent -> updateKind(intent.kind) { it.copy(silent = intent.silent) }
                is NotificationsStore.Intent.SetCategoryVibrate -> {
                    local?.setCategoryVibrate(intent.kind, intent.enabled)
                    push?.applyChannels()
                    dispatchLocal()
                }
                is NotificationsStore.Intent.SetCategoryLed -> {
                    local?.setCategoryLed(intent.kind, intent.enabled)
                    push?.applyChannels()
                    dispatchLocal()
                }
                is NotificationsStore.Intent.SetCategoryPriority -> {
                    local?.setCategoryPriorityHigh(intent.kind, intent.high)
                    push?.applyChannels()
                    dispatchLocal()
                }
                is NotificationsStore.Intent.SetCategoryPopup -> {
                    local?.setCategoryPopup(intent.kind, intent.enabled)
                    dispatchLocal()
                }
                is NotificationsStore.Intent.ResetException -> scope.launch {
                    client.updateNotifySettings("peer", NotifySettings(), PeerId(intent.chatId))
                    refresh()
                }
                is NotificationsStore.Intent.AddException -> scope.launch {
                    client.updateNotifySettings(
                        "peer",
                        NotifySettings(muteUntil = Int.MAX_VALUE, showPreviews = true),
                        PeerId(intent.chatId),
                    )
                    refresh()
                }
                is NotificationsStore.Intent.SetPeerMode -> {
                    local?.setPeerMode(intent.chatId, intent.mode)
                    // A custom mode picks a per-chat channel, so channel state must be current.
                    push?.applyChannels()
                    dispatchLocal()
                }
                is NotificationsStore.Intent.ClearPeerMode -> {
                    local?.setPeerMode(intent.chatId, null)
                    push?.applyChannels()
                    dispatchLocal()
                }
                NotificationsStore.Intent.CycleCallsVibrate -> {
                    val next = when (state().callsVibrate) {
                        "default" -> "off"
                        "off" -> "short"
                        else -> "default"
                    }
                    local?.callsVibrate = next
                    dispatchLocal()
                }
                NotificationsStore.Intent.CycleCallsRingtone -> {
                    val next = if (state().callsRingtone == "default") "none" else "default"
                    local?.callsRingtone = next
                    dispatchLocal()
                }
                NotificationsStore.Intent.CycleRepeat -> {
                    val next = when (state().repeatMinutes) {
                        0 -> 5
                        5 -> 10
                        10 -> 30
                        else -> 0
                    }
                    local?.repeatMinutes = next
                    push?.applyChannels()
                    dispatchLocal()
                }
                NotificationsStore.Intent.Reset -> scope.launch {
                    client.resetNotifySettings()
                    refresh()
                }
                NotificationsStore.Intent.Reregister -> scope.launch {
                    push?.reregister()
                    dispatch(Msg.Debug(push?.debugState() ?: PushDebugState()))
                }
                is NotificationsStore.Intent.Simulate -> {
                    push?.simulate(intent.locKey)
                    dispatch(Msg.Debug(push?.debugState() ?: PushDebugState()))
                }
                NotificationsStore.Intent.RequestPermission -> {
                    push?.requestPermission()
                    dispatch(Msg.Debug(push?.debugState() ?: PushDebugState()))
                }
            }
        }

        private fun refresh() {
            dispatch(Msg.Loading(true))
            scope.launch {
                val cachedExceptions = local?.notifyExceptions.orEmpty()
                val cachedFolders = warmup?.folders().orEmpty()
                val cachedChats = warmup?.chats().orEmpty()
                if (cachedExceptions.isNotEmpty() || cachedFolders.isNotEmpty() || cachedChats.isNotEmpty()) {
                    val titles = cachedChats.associate { it.id.value to it.title }
                    val exceptIds = cachedExceptions.map { it.chatId.value }.toSet()
                    dispatch(
                        Msg.Loaded(
                            users = local?.notifyDefaults?.users ?: NotifySettings(),
                            chats = local?.notifyDefaults?.chats ?: NotifySettings(),
                            broadcasts = local?.notifyDefaults?.broadcasts ?: NotifySettings(),
                            exceptions = cachedExceptions,
                            folders = cachedFolders,
                            chatsById = titles,
                            addableChats = cachedChats.filter { it.id.value !in exceptIds }.take(40),
                            peerModes = local?.peerModes().orEmpty(),
                            folderPrefs = folderPrefs(local, cachedFolders),
                        ),
                    )
                }
                val users = settings("users")
                val chats = settings("chats")
                val broadcasts = settings("broadcasts")
                val exceptions = when (val result = client.getNotifyExceptions()) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> cachedExceptions
                }
                local?.notifyExceptions = exceptions
                val folders = when (val result = client.getFolders()) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> cachedFolders
                }
                val dialogs = when (val result = client.getChats()) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> cachedChats
                }
                val titles = dialogs.associate { it.id.value to it.title }
                val exceptIds = exceptions.map { it.chatId.value }.toSet()
                val addable = dialogs.filter { it.id.value !in exceptIds }.take(40)
                dispatch(
                    Msg.Loaded(
                        users = users,
                        chats = chats,
                        broadcasts = broadcasts,
                        exceptions = exceptions,
                        folders = folders,
                        chatsById = titles,
                        addableChats = addable,
                        peerModes = local?.peerModes().orEmpty(),
                        folderPrefs = folderPrefs(local, folders),
                    ),
                )
                dispatch(Msg.Debug(push?.debugState() ?: PushDebugState()))
                dispatch(Msg.Loading(false))
            }
        }

        private suspend fun settings(kind: String): NotifySettings =
            when (val result = client.getNotifySettings(kind)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> NotifySettings()
            }

        private fun toggleGlobal(kind: String, enabled: Boolean) {
            val muteUntil = if (enabled) 0 else Int.MAX_VALUE
            scope.launch {
                val current = when (kind) {
                    "chats" -> state().chats
                    "broadcasts" -> state().broadcasts
                    else -> state().users
                }
                client.updateNotifySettings(kind, current.copy(muteUntil = muteUntil), PeerId(0))
                if (kind == "stories") local?.storiesEnabled = enabled
                if (kind == "reactions") local?.reactionsEnabled = enabled
                refresh()
            }
        }

        private fun setPreview(kind: String, preview: Boolean) {
            updateKind(kind) { it.copy(showPreviews = preview) }
        }

        private fun updateKind(kind: String, transform: (NotifySettings) -> NotifySettings) {
            scope.launch {
                val current = when (kind) {
                    "chats" -> state().chats
                    "broadcasts" -> state().broadcasts
                    else -> state().users
                }
                client.updateNotifySettings(kind, transform(current), PeerId(0))
                refresh()
            }
        }

        private fun toggleInApp(key: String, enabled: Boolean) {
            when (key) {
                "sound" -> local?.inAppSound = enabled
                "vibrate" -> local?.inAppVibrate = enabled
                "preview" -> local?.inAppPreview = enabled
                "inchat" -> local?.inChatSound = enabled
                "priority" -> local?.inAppPriority = enabled
                "pinned" -> local?.pinnedEnabled = enabled
                "joined" -> {
                    local?.contactJoinedEnabled = enabled
                    scope.launch { client.setContactJoinedSilent(!enabled) }
                }
                "stories" -> local?.storiesEnabled = enabled
                "reactions" -> local?.reactionsEnabled = enabled
                "gifts" -> local?.giftsEnabled = enabled
                "badge" -> local?.badgeEnabled = enabled
                "badgeMuted" -> local?.badgeMuted = enabled
                "badgeMessages" -> local?.badgeMessages = enabled
            }
            dispatchLocal()
        }

        private fun dispatchLocal() {
            val current = state()
            dispatch(
                Msg.Local(
                    current.copy(
                        mutedFolders = local?.mutedFolders().orEmpty(),
                        folderPrefs = folderPrefs(local, current.folders),
                        peerModes = local?.peerModes().orEmpty(),
                        inAppSound = local?.inAppSound ?: current.inAppSound,
                        inAppVibrate = local?.inAppVibrate ?: current.inAppVibrate,
                        inAppPreview = local?.inAppPreview ?: current.inAppPreview,
                        inChatSound = local?.inChatSound ?: current.inChatSound,
                        inAppPriority = local?.inAppPriority ?: current.inAppPriority,
                        pinned = local?.pinnedEnabled ?: current.pinned,
                        contactJoined = local?.contactJoinedEnabled ?: current.contactJoined,
                        stories = local?.storiesEnabled ?: current.stories,
                        reactions = local?.reactionsEnabled ?: current.reactions,
                        gifts = local?.giftsEnabled ?: current.gifts,
                        usersVibrate = local?.categoryVibrate("users") ?: current.usersVibrate,
                        usersLed = local?.categoryLed("users") ?: current.usersLed,
                        usersPriority = local?.categoryPriorityHigh("users") ?: current.usersPriority,
                        chatsVibrate = local?.categoryVibrate("chats") ?: current.chatsVibrate,
                        chatsLed = local?.categoryLed("chats") ?: current.chatsLed,
                        chatsPriority = local?.categoryPriorityHigh("chats") ?: current.chatsPriority,
                        broadcastsVibrate = local?.categoryVibrate("broadcasts") ?: current.broadcastsVibrate,
                        broadcastsLed = local?.categoryLed("broadcasts") ?: current.broadcastsLed,
                        broadcastsPriority = local?.categoryPriorityHigh("broadcasts") ?: current.broadcastsPriority,
                        usersPopup = local?.categoryPopup("users") ?: current.usersPopup,
                        chatsPopup = local?.categoryPopup("chats") ?: current.chatsPopup,
                        broadcastsPopup = local?.categoryPopup("broadcasts") ?: current.broadcastsPopup,
                        storiesPopup = local?.categoryPopup("stories") ?: current.storiesPopup,
                        reactionsPopup = local?.categoryPopup("reactions") ?: current.reactionsPopup,
                        callsVibrate = local?.callsVibrate ?: current.callsVibrate,
                        callsRingtone = local?.callsRingtone ?: current.callsRingtone,
                        badge = local?.badgeEnabled ?: current.badge,
                        badgeMuted = local?.badgeMuted ?: current.badgeMuted,
                        badgeMessages = local?.badgeMessages ?: current.badgeMessages,
                        repeatMinutes = local?.repeatMinutes ?: current.repeatMinutes,
                        debug = push?.debugState() ?: current.debug,
                    ),
                ),
            )
        }
    }

    private fun folderPrefs(
        local: NotificationLocalStore?,
        folders: List<Folder>,
    ): Map<Int, NotificationsStore.FolderPrefs> = folders.associate { folder ->
        val key = "folder_${folder.id}"
        folder.id to NotificationsStore.FolderPrefs(
            vibrate = local?.categoryVibrate(key) ?: true,
            led = local?.categoryLed(key) ?: true,
            popup = local?.categoryPopup(key) ?: true,
        )
    }

    private object ReducerImpl : Reducer<NotificationsStore.State, Msg> {
        override fun NotificationsStore.State.reduce(msg: Msg): NotificationsStore.State = when (msg) {
            is Msg.Loaded -> copy(
                users = msg.users,
                chats = msg.chats,
                broadcasts = msg.broadcasts,
                exceptions = msg.exceptions,
                peerModes = msg.peerModes,
                folders = msg.folders,
                folderPrefs = msg.folderPrefs,
                chatsById = msg.chatsById,
                addableChats = msg.addableChats,
            )
            is Msg.Local -> msg.state
            is Msg.Loading -> copy(loading = msg.value)
            is Msg.Message -> copy(message = msg.value)
            is Msg.Debug -> copy(debug = msg.value)
        }
    }
}
