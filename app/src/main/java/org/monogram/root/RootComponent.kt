package org.monogram.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.navigate
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.doOnStart
import com.arkivanov.essenty.lifecycle.doOnStop
import com.arkivanov.mvikotlin.core.store.StoreFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.monogram.BuildConfig
import org.monogram.MonogramApp.PendingAccountAuthorization
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.push.NotificationLocalStore
import org.monogram.core.common.push.PushRegistration
import org.monogram.core.common.telegram.TelegramLink
import org.monogram.core.common.telegram.parseTelegramLink
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.markup.NativeMarkupParser
import org.monogram.core.models.AccountState
import org.monogram.core.models.AppUpdate
import org.monogram.core.models.AuthSession
import org.monogram.core.models.PeerId
import org.monogram.core.models.requiresForwardPhotoRight
import org.monogram.core.ui.DownloadSettings
import org.monogram.core.ui.ImageCache
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.feature.auth.AuthComponent
import org.monogram.feature.chats.AccountMenuItem
import org.monogram.feature.chats.ChatsComponent
import org.monogram.feature.dialog.DialogComponent
import org.monogram.feature.dialog.clearDialogMemory
import org.monogram.feature.dialog.scheduled.ScheduledQueueComponent
import org.monogram.feature.dialog.ui.stopAudioMessagePlayback
import org.monogram.feature.folders.FoldersComponent
import org.monogram.feature.profile.ProfileComponent
import org.monogram.feature.settings.AppUpdateController
import org.monogram.feature.settings.SettingsAccountDetails
import org.monogram.feature.settings.SettingsComponent
import org.monogram.feature.settings.SettingsPage
import org.monogram.feature.settings.ui.ProxySession
import org.monogram.network.bridge.BridgedMtprotoClient
import org.monogram.network.bridge.DurableUpdateBatch
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.http.MediaRepository

class RootComponent(
    componentContext: ComponentContext,
    private val storeFactory: StoreFactory,
    private val client: MtprotoClient,
    private val warmup: OfflineWarmup?,
    private val sessionStore: SessionMetadataStore?,
    private val mediaRepository: MediaRepository?,
    startOnHome: Boolean,
    accountSwitchDestination: Config? = null,
    private val accountState: AccountState = AccountState(),
    private val pushRegistration: PushRegistration? = null,
    private val notificationLocal: NotificationLocalStore? = null,
    private val onIncomingShareConsumed: () -> Unit = {},
    val appUpdate: AppUpdateController? = null,
    private val bubbleChatId: Long? = null,
    private val liveLocations: LiveLocationRuntime? = null,
    private val onRootBack: () -> Unit = {},
    private val onAccountAuthorized: suspend (AuthSession) -> Unit = {},
    private val onAccountLoggedOut: suspend () -> Unit = {},
    private val onAccountWorkStop: suspend () -> Unit = {},
    private val onAccountWorkResume: () -> Unit = {},
    private val onBeginAddAccount: suspend () -> PendingAccountAuthorization = { error("Add account unavailable") },
    private val onAdditionalAccountAuthorized: suspend (AuthSession, PendingAccountAuthorization) -> Unit = { _, _ -> },
    private var accountUserIds: List<Long> = emptyList(),
    private var accountMenuItems: List<AccountMenuItem> = emptyList(),
    private var accountNeedsSignInIds: Set<Long> = emptySet(),
    private var activeAccountUserId: Long? = null,
    private var activeAccountDcId: Int? = null,
    private val loadAccountMenuItems: (suspend () -> List<AccountMenuItem>)? = null,
    private val loadAccountDetails: suspend (Long, suspend (SettingsAccountDetails) -> Unit) -> Unit = { _, _ -> },
    private val onSwitchAccount: suspend (Long) -> Unit = {},
    private val onRemoveAccount: suspend (Long) -> Unit = {},
) : ComponentContext by componentContext {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingAdditionalAuthorization: PendingAccountAuthorization? = null

    fun beginAddAccount() {
        scope.launch {
            pendingAdditionalAuthorization = onBeginAddAccount()
            navigation.navigate { it + Config.AddAccount }
        }
    }

    private val navigation = StackNavigation<Config>()
    private var expiringSession = false
    private var rootForeground =
        lifecycle.state == Lifecycle.State.STARTED || lifecycle.state == Lifecycle.State.RESUMED
    private var accountFlagsVersion = 0L
    private var listDetailVisible = false
    private val incomingShareState = MutableStateFlow<IncomingShare?>(null)
    val incomingShare: kotlinx.coroutines.flow.StateFlow<IncomingShare?> = incomingShareState
    private var pendingIncomingShare: IncomingShare?
        get() = incomingShareState.value
        set(value) {
            incomingShareState.value = value
        }

    fun accountSwitchDestination(): Config {
        val active = stack.value.active
        val page =
            (active.instance as? Child.Settings)?.component?.pages?.value?.active?.configuration
        return accountSwitchDestination(active.configuration, page)
    }

    fun setListDetailVisible(visible: Boolean) {
        listDetailVisible = visible
    }

    internal val proxySession: ProxySession = ClientProxySession(client)

    fun openFromNotification(
        chatId: Long,
        messageId: Int = 0,
    ) {
        if (stack.value.active.configuration.isAuthArea()) {
            return
        }
        AppLog.api("notify", "open chat=$chatId")
        pushRegistration?.onVisibleChat(chatId)
        if (bubbleChatId != null) {
            navigation.replaceAll(Config.Dialog(chatId, jumpToMessageId = messageId))
            return
        }
        navigation.navigate { configurations ->
            chatSelectionStack(
                configurations,
                Config.Dialog(chatId, jumpToMessageId = messageId),
                listDetailVisible,
            )
        }
    }

    fun openChatForPlayback(
        chatId: Long,
        messageId: Int = 0,
    ) {
        if (stack.value.active.configuration.isAuthArea()) {
            return
        }
        navigation.navigate { configurations ->
            chatSelectionStack(
                configurations,
                Config.Dialog(chatId, jumpToMessageId = messageId),
                listDetailVisible,
            )
        }
    }

    fun openIncomingShare(share: IncomingShare) {
        if (share.isEmpty()) return
        pendingIncomingShare = share
        if (!stack.value.active.configuration.isAuthArea()) {
            openRecipientPicker(RecipientRequest(share = share))
            pendingIncomingShare = null
            onIncomingShareConsumed()
        }
    }

    private fun openRecipientPicker(request: RecipientRequest) {
        navigation.navigate { configurations ->
            configurations.filterNot { it is Config.Recipients } + Config.Recipients(request)
        }
    }

    fun hasIncomingShare(): Boolean =
        pendingIncomingShare != null || stack.value.items.any { item ->
            (item.configuration as? Config.Dialog)?.incomingShare != null || (item.configuration as? Config.Recipients)?.request?.share != null
        }

    fun cancelIncomingShare() {
        pendingIncomingShare = null
        onIncomingShareConsumed()
    }

    private fun openChatFromList(
        peer: PeerId,
        forum: Boolean,
        messageId: Int = 0,
    ) {
        val destination = Config.Dialog(
            chatId = peer.value,
            isForum = forum,
            jumpToMessageId = messageId,
        )
        navigation.navigate { configurations ->
            chatSelectionStack(configurations, destination, listDetailVisible)
        }
    }

    /**
     * Opens a peer's profile. A profile may already be in the stack (member lists,
     * shared chats), so return to that child instead of pushing an equal configuration:
     * Decompose rejects a stack holding two equal configurations.
     */
    private fun openProfile(peer: PeerId) {
        navigation.navigate { configurations ->
            uniqueStack(configurations, Config.Profile(peer.value))
        }
    }

    fun openProxy() {
        navigation.navigate { configurations -> uniqueStack(configurations, Config.Proxy) }
    }

    fun openNotificationSettings() {
        openSettings(openNotifications = true)
    }

    private fun openSettings(
        openFolders: Boolean = false,
        openProxy: Boolean = false,
        openNotifications: Boolean = false,
    ) {
        navigation.navigate { configurations ->
            uniqueStack(
                configurations,
                Config.Settings(
                    openFolders = openFolders,
                    openProxy = openProxy,
                    openNotifications = openNotifications,
                ),
            )
        }
    }

    /**
     * Opens a peer's chat from a profile. The chat is usually still open behind the
     * profile, so return to that child keeping the thread view it was opened with
     * instead of pushing an equal configuration.
     */
    private fun openChatFromProfile(peer: PeerId) {
        navigation.navigate { configurations ->
            val opened = configurations.filterIsInstance<Config.Dialog>()
                .lastOrNull { it.chatId == peer.value }
            chatSelectionStack(
                configurations,
                opened ?: Config.Dialog(peer.value),
                listDetailVisible,
            )
        }
    }

    private var restoringSignedOutStack = !startOnHome

    val stack: Value<ChildStack<Config, Child>> = childStack(
        source = navigation,
        key = "root:${activeAccountUserId ?: "unauthenticated"}",
        serializer = Config.serializer(),
        initialConfiguration = if (!startOnHome) {
            Config.Auth
        } else {
            bubbleChatId?.let { Config.Dialog(it) } ?: Config.Home
        },
        handleBackButton = true,
        childFactory = ::child,
    )

    fun onBack() {
        if (stack.value.backStack.isEmpty()) onRootBack() else navigation.pop()
    }

    fun openTelegramUri(uri: String): Boolean {
        val link = parseTelegramLink(uri) ?: return false
        if (stack.value.active.configuration.isAuthArea()) {
            return true
        }
        scope.launch { openTelegramLink(link) }
        return true
    }

    private suspend fun openTelegramLink(link: TelegramLink) {
        when (link) {
            is TelegramLink.Username -> {
                when (val resolved = client.resolveUsername(link.username)) {
                    is Outcome.Ok -> {
                        openFromNotification(
                            resolved.value.peerId.value,
                            link.messageId ?: 0,
                        )
                    }

                    is Outcome.Err -> {
                        AppLog.warn("t.me", "resolve failed")
                    }
                }
            }

            is TelegramLink.PrivateChannel -> {
                openFromNotification(link.chatId, link.messageId)
            }

            is TelegramLink.Invite, is TelegramLink.Share -> {
                AppLog.api("t.me", "link type ignored")
            }
        }
    }

    private val durableUpdateSink = warmup?.let(::AccountDurableUpdateSink)

    private suspend fun commitDurableBatch(batch: DurableUpdateBatch): Boolean {
        if (expiringSession) return false
        return durableUpdateSink?.commit(batch) ?: false
    }

    init {
        if (startOnHome && accountSwitchDestination != null && bubbleChatId == null) {
            navigation.navigate { accountSwitchStack(accountSwitchDestination) }
        }
        if (restoringSignedOutStack && stack.value.items.any { !it.configuration.isAuthArea() }) {
            navigation.replaceAll(Config.Auth)
        }
        if (stack.value.items.any { it.configuration == Config.AddAccount }) {
            navigation.navigate { configurations ->
                configurations.filterNot { it == Config.AddAccount }.ifEmpty {
                    listOf(if (startOnHome) Config.Home else Config.Auth)
                }
            }
        }
        restoringSignedOutStack = false
        if (startOnHome && stack.value.active.configuration is Config.Auth) {
            navigation.replaceAll(Config.Home)
        }
        stack.subscribe { childStack ->
            val dialog = childStack.active.configuration as? Config.Dialog
            pushRegistration?.onVisibleChat(
                dialog?.chatId, dialog?.threadTopMsgId?.takeIf { it > 0 })
        }
        lifecycle.doOnStart {
            onAccountWorkResume()
            if (!stack.value.active.configuration.isAuthArea()) {
                scope.launch {
                    sessionStore?.readAuthorizedUserId()?.value?.let {
                        pushRegistration?.setAccountUserId(
                            it,
                        )
                    }
                    runCatching { pushRegistration?.reregister() }
                }
            }
        }
        lifecycle.doOnDestroy {
            liveLocations?.setForeground(this@RootComponent, false)
            scope.cancel()
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.sessionLost().collect { expireSession() }
        }
        // Message, read-state and delta updates reach the cache only through the
        // durable batch commit; the live collector below must not apply them twice.
        (client as? BridgedMtprotoClient)?.installDurableUpdateSink(::commitDurableBatch)
        scope.launch {
            client.updates().collect { update ->
                if (expiringSession) return@collect
                try {
                    when (update) {
                        is MtprotoUpdate.SyncState -> {
                            if (!update.isSyncing) onAccountWorkResume()
                        }

                        is MtprotoUpdate.FoldersChanged -> {
                            warmup?.replaceFolders(update.folders)
                        }

                        is MtprotoUpdate.ChatsChanged -> {
                            warmup?.upsertChats(update.chats)
                            sessionStore?.upsertPeersFromChats(update.chats)
                        }

                        is MtprotoUpdate.PeerStatus -> {
                            sessionStore?.updatePeerStatus(
                                update.userId.value,
                                update.status,
                                update.statusAt,
                            )
                        }

                        is MtprotoUpdate.PeerEmojiStatus -> {
                            sessionStore?.updatePeerEmojiStatus(
                                update.userId.value,
                                update.documentId,
                            )
                        }

                        is MtprotoUpdate.ReadInbox -> {
                            if (update.stillUnread <= 0) {
                                pushRegistration?.onChatRead(update.chatId.value)
                            }
                        }

                        is MtprotoUpdate.ReadHistoryConfirmed -> {
                            warmup?.markChatRead(
                                update.chatId,
                                update.maxId,
                            )
                            pushRegistration?.onChatRead(update.chatId.value)
                        }

                        is MtprotoUpdate.UnreadMentions -> {
                            warmup?.applyUnreadMentions(
                                update.chatId,
                                update.stillUnread,
                            )
                        }

                        is MtprotoUpdate.UnreadReactions -> {
                            warmup?.applyUnreadReactions(
                                update.chatId,
                                update.stillUnread,
                            )
                        }

                        else -> {
                            Unit
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    AppLog.warn("read-state", "cache update failed")
                }
                if (!expiringSession && update is MtprotoUpdate.AccountPremium) {
                    accountFlagsVersion++
                    accountState.isPremium = update.isPremium
                    DownloadSettings.setPremium(update.isPremium)
                    sessionStore?.savePremium(update.isPremium)
                }
            }
        }
        scope.launch {
            val bridged = client as? BridgedMtprotoClient ?: return@launch
            if (sessionStore?.readMeta(SessionMetadataStore.KEY_AUTHORIZED) == "0") return@launch
            val result = withContext(Dispatchers.IO) { bridged.isAuthorized() }
            if (sessionStore?.readMeta(SessionMetadataStore.KEY_AUTHORIZED) == "0") return@launch
            if (result is Outcome.Ok && result.value) {
                if (!expiringSession) {
                    startLiveLocations()
                }
                if (!expiringSession && stack.value.active.configuration.isAuthArea() && stack.value.active.configuration != Config.AddAccount) {
                    navigation.replaceAll(Config.Home)
                    pendingIncomingShare?.let(::openIncomingShare)
                }
                refreshAccountFlags()
            } else if (requiresSessionReset(result)) {
                expireSession()
            }
        }
        lifecycle.doOnStart {
            rootForeground = true
            liveLocations?.setForeground(this@RootComponent, true)
            scope.launch(Dispatchers.IO) { client.updateStatus(offline = false) }
        }
        lifecycle.doOnStop {
            rootForeground = false
            liveLocations?.setForeground(this@RootComponent, false)
            scope.launch(Dispatchers.IO) { client.updateStatus(offline = true) }
        }
    }

    private suspend fun startLiveLocations() {
        liveLocations?.setForeground(this, rootForeground)
        liveLocations?.startAuthorized()
    }

    private suspend fun stopLiveLocations() {
        liveLocations?.stopAuthorized()
    }

    private suspend fun refreshAccountFlags() {
        if (expiringSession) return
        val version = accountFlagsVersion
        val cachedPremium = sessionStore?.readPremium() ?: false
        if (expiringSession || version != accountFlagsVersion) return
        accountState.isPremium = cachedPremium
        DownloadSettings.setPremium(cachedPremium)
        when (val profile = client.getProfile(PeerId(0))) {
            is Outcome.Ok -> {
                if (expiringSession || version != accountFlagsVersion) return
                if (activeAccountUserId != null && profile.value.id.value != activeAccountUserId) {
                    AppLog.warn("account", "profile identity does not match active account")
                    return
                }
                accountState.isPremium = profile.value.isPremium
                DownloadSettings.setPremium(profile.value.isPremium)
                sessionStore?.savePremium(profile.value.isPremium)
                sessionStore?.upsertProfile(profile.value)
                if (expiringSession || version != accountFlagsVersion) return
                recoveredProfileSession(
                    profile.value.id,
                    activeAccountUserId,
                    activeAccountDcId,
                )?.let {
                    sessionStore?.recoverAuthorizedIfAbsent(it)
                }
            }

            is Outcome.Err -> {
                Unit
            }
        }
    }

    private suspend fun expireSession() {
        if (expiringSession) return
        expiringSession = true
        clearDialogMemory(client)
        stopAudioMessagePlayback()
        onAccountWorkStop()
        stopLiveLocations()
        accountState.isPremium = false
        // Destroy account screens and their collectors before clearing their cache.
        MediaPlaybackHolder.peek()?.stop()
        navigation.replaceAll(Config.Auth)
        withContext(Dispatchers.IO) {
            warmup?.clearAccountCache()
            sessionStore?.clearSession()
            mediaRepository?.clearCache()
            ImageCache.clear(mediaRepository?.cacheRoot)
        }
    }

    private fun child(
        config: Config,
        context: ComponentContext,
    ): Child = if (restoringSignedOutStack && !config.isAuthArea()) {
        // Decompose restores children before init can clear the signed-out stack.
        Child.Proxy
    } else if (config == Config.AddAccount && pendingAdditionalAuthorization == null) {
        // Restored add-account screens have no pending client; init removes the placeholder.
        Child.Proxy
    } else {
        when (config) {
            is Config.Recipients -> {
                Child.Recipients(
                    RecipientPickerComponent(context, config.request, client, warmup) {
                        navigation.pop()
                    },
                )
            }

            Config.Auth -> {
                Child.Auth(
                    AuthComponent(context, storeFactory, client) { session ->
                        expiringSession = false
                        scope.launch {
                            onAccountAuthorized(session)
                            sessionStore?.saveAuthorized(session)
                            if (expiringSession) return@launch
                            activeAccountUserId = session.userId.value
                            activeAccountDcId = session.dcId
                            try {
                                loadAccountMenuItems?.invoke()?.let { items ->
                                    accountMenuItems = items
                                    accountUserIds = items.map { it.userId }
                                    accountNeedsSignInIds = items.filter { it.needsSignIn }
                                        .mapTo(mutableSetOf()) { it.userId }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                AppLog.warn("account", "account information unavailable")
                            }
                            if (expiringSession) return@launch
                            navigation.replaceAll(Config.Home)
                            pendingIncomingShare?.let(::openIncomingShare)
                            startLiveLocations()
                            pushRegistration?.setAccountUserId(session.userId.value)
                            refreshAccountFlags()
                            runCatching { pushRegistration?.reregister() }
                        }
                    },
                )
            }

            Config.AddAccount -> {
                Child.Auth(
                    AuthComponent(
                        context,
                        storeFactory,
                        checkNotNull(pendingAdditionalAuthorization).client,
                    ) { session ->
                        val pending = pendingAdditionalAuthorization ?: return@AuthComponent
                        scope.launch {
                            onAdditionalAccountAuthorized(session, pending)
                            pendingAdditionalAuthorization = null
                            navigation.replaceAll(Config.Home)
                        }
                    },
                )
            }

            Config.Proxy -> {
                Child.Proxy
            }

            Config.Home -> {
                Child.Home(
                    HomeComponent(
                        componentContext = context,
                        storeFactory = storeFactory,
                        client = client,
                        warmup = warmup,
                        sessionStore = sessionStore,
                        notificationLocal = notificationLocal,
                        mediaRepository = mediaRepository,
                        onOpenChat = ::openChatFromList,
                        onOpenProfile = ::openProfile,
                        onOpenSettings = { openSettings() },
                        onOpenProxy = { openSettings(openProxy = true) },
                        onOpenFolders = { openSettings(openFolders = true) },
                        accountMenuItems = accountMenuItems,
                        onSwitchAccount = { userId -> scope.launch { onSwitchAccount(userId) } },
                        onAddAccount = ::beginAddAccount,
                    ),
                )
            }

            is Config.Dialog -> {
                val liveDialogEpoch = warmup?.outgoingAccountEpoch()
                val component = DialogComponent(
                    componentContext = context,
                    storeFactory = storeFactory,
                    client = client,
                    warmup = warmup,
                    sessionStore = sessionStore,
                    mediaRepository = mediaRepository,
                    chatId = PeerId(config.chatId),
                    jumpToMessageId = config.jumpToMessageId,
                    threadTopMsgId = config.threadTopMsgId,
                    isForum = config.isForum,
                    markup = NativeMarkupParser(),
                    isPremium = { accountState.isPremium },
                    onBack = ::onBack,
                    onOpenScheduledQueue = {
                        navigation.navigate {
                            uniqueStack(
                                it,
                                Config.ScheduledQueue(config.chatId),
                            )
                        }
                    },
                    onResumeLiveLocation = liveLocations?.takeIf { liveDialogEpoch != null }
                        ?.let { runtime ->
                            { id ->
                                runtime.resume(
                                    id, liveDialogEpoch
                                )
                            }
                        },
                    onStopLiveLocation = liveLocations?.takeIf { liveDialogEpoch != null }
                        ?.let { runtime ->
                            { id ->
                                runtime.requestStop(
                                    id, liveDialogEpoch
                                )
                            }
                        },
                    liveLocationErrors = liveLocations?.errors ?: kotlinx.coroutines.flow.flowOf(
                        emptyMap(),
                    ),
                    liveLocationFailure = liveLocations?.failure ?: kotlinx.coroutines.flow.flowOf(
                        null,
                    ),
                    showBackButton = config.chatId != bubbleChatId || config.threadTopMsgId != 0,
                    onOpenProfile = ::openProfile,
                    onOpenChat = { peer, jump, threadTop ->
                        navigation.navigate { configurations ->
                            uniqueStack(
                                configurations,
                                Config.Dialog(
                                    peer.value,
                                    jump,
                                    threadTop,
                                    isForum = if (peer.value == config.chatId) config.isForum else false,
                                ),
                            )
                        }
                    },
                    onRequestForward = { messages ->
                        openRecipientPicker(
                            RecipientRequest(
                                fromChatId = config.chatId,
                                messageIds = messages.map { it.id.id },
                                requiresPhotos = messages.any { it.requiresForwardPhotoRight() },
                            ),
                        )
                    },
                )
                config.incomingShare?.let { share ->
                    if (share.text.isNotBlank()) component.onDraftChanged(share.text)
                    val items = share.attachments.map(IncomingShareAttachment::toUploadItem)
                    if (items.isNotEmpty()) component.onAttachMedia(items)
                }
                Child.Dialog(component)
            }

            is Config.ScheduledQueue -> {
                Child.ScheduledQueue(
                    ScheduledQueueComponent(
                        context,
                        storeFactory,
                        client,
                        warmup,
                        PeerId(config.chatId),
                        ::onBack,
                        mediaRepository,
                    ),
                )
            }

            is Config.Profile -> {
                Child.Profile(
                    ProfileComponent(
                        componentContext = context,
                        storeFactory = storeFactory,
                        client = client,
                        sessionStore = sessionStore,
                        mediaRepository = mediaRepository,
                        peerId = PeerId(config.peerId),
                        onBack = { navigation.pop() },
                        onOpenChat = ::openChatFromProfile,
                        onOpenProfile = ::openProfile,
                    ),
                )
            }

            is Config.Settings -> {
                Child.Settings(
                    SettingsComponent(
                        componentContext = context,
                        storeFactory = storeFactory,
                        client = client,
                        sessionStore = sessionStore,
                        warmup = warmup,
                        mediaRepository = mediaRepository,
                        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        buildStamp = "${BuildConfig.BUILD_TYPE} · ${BuildConfig.GIT_COMMIT}",
                        onBack = { navigation.pop() },
                        onOpenProfile = ::openProfile,
                        accountUserIds = accountUserIds,
                        activeAccountUserId = activeAccountUserId,
                        accountNeedsSignInIds = accountNeedsSignInIds,
                        loadAccountDetails = loadAccountDetails,
                        onRemoveAccount = onRemoveAccount,
                        onAddAccount = ::beginAddAccount,
                        onSwitchAccount = onSwitchAccount,
                        onBeforeLogout = {
                            expiringSession = true
                            clearDialogMemory(client)
                            stopAudioMessagePlayback()
                            onAccountWorkStop()
                            stopLiveLocations()
                        },
                        onLoggedOut = {
                            expiringSession = true
                            scope.launch {
                                onAccountLoggedOut()
                                stopLiveLocations()
                                MediaPlaybackHolder.peek()?.stop()
                                navigation.replaceAll(Config.Auth)
                            }
                        },
                        pushRegistration = pushRegistration,
                        debugNotifications = BuildConfig.DEBUG,
                        notificationLocal = notificationLocal,
                        openFolders = config.openFolders,
                        openProxy = config.openProxy,
                        openNotifications = config.openNotifications,
                        appUpdate = appUpdate,
                        updatesEnabled = AppUpdate.inAppUpdatesEnabled(BuildConfig.BUILD_TYPE),
                    ),
                    folders = FoldersComponent(
                        componentContext = context,
                        storeFactory = storeFactory,
                        client = client,
                        warmup = warmup,
                    ),
                )
            }
        }
    }

    sealed class Child {
        class Recipients(
            val component: RecipientPickerComponent,
        ) : Child()

        class Auth(
            val component: AuthComponent,
        ) : Child()

        object Proxy : Child()

        class Home(
            val component: HomeComponent,
        ) : Child()

        class ScheduledQueue(
            val component: ScheduledQueueComponent,
        ) : Child()

        class Dialog(
            val component: DialogComponent,
        ) : Child()

        class Profile(
            val component: ProfileComponent,
        ) : Child()

        class Settings(
            val component: SettingsComponent,
            val folders: FoldersComponent,
        ) : Child()
    }

    @Serializable
    sealed interface Config {
        @Serializable
        data class Recipients(
            val request: RecipientRequest,
        ) : Config

        @Serializable
        data object Auth : Config

        @Serializable
        data object AddAccount : Config

        @Serializable
        data object Proxy : Config

        @Serializable
        data object Home : Config

        @Serializable
        data class Dialog(
            val chatId: Long,
            val jumpToMessageId: Int = 0,
            val threadTopMsgId: Int = 0,
            val isForum: Boolean? = null,
            val incomingShareId: Long = 0L,
            val incomingShare: IncomingShare? = null,
        ) : Config

        @Serializable
        data class ScheduledQueue(
            val chatId: Long,
        ) : Config

        @Serializable
        data class Profile(
            val peerId: Long,
        ) : Config

        @Serializable
        data class Settings(
            val openFolders: Boolean = false,
            val openProxy: Boolean = false,
            val openNotifications: Boolean = false,
        ) : Config
    }
}

internal fun recoveredProfileSession(
    profileUserId: PeerId,
    accountUserId: Long?,
    accountDcId: Int?,
): AuthSession? =
    if (profileUserId.value > 0 && profileUserId.value == accountUserId && accountDcId != null && accountDcId > 0) {
        AuthSession(profileUserId, accountDcId)
    } else {
        null
    }

internal fun RootComponent.Config.isAuthArea(): Boolean =
    this is RootComponent.Config.Auth || this is RootComponent.Config.AddAccount || this is RootComponent.Config.Proxy

internal fun RootComponent.Child.isSignedOut(): Boolean =
    this is RootComponent.Child.Auth || this is RootComponent.Child.Proxy

internal fun requiresSessionReset(result: Outcome<Boolean>): Boolean = when (result) {
    is Outcome.Ok -> !result.value
    is Outcome.Err -> result.telegramError.requiresReauth
}

internal fun accountSwitchDestination(
    configuration: RootComponent.Config,
    settingsPage: SettingsPage?,
): RootComponent.Config = when (configuration) {
    is RootComponent.Config.Settings -> {
        when (settingsPage) {
            SettingsPage.Proxy -> RootComponent.Config.Settings(openProxy = true)
            SettingsPage.Folders -> RootComponent.Config.Settings(openFolders = true)
            SettingsPage.Notifications -> RootComponent.Config.Settings(openNotifications = true)
            else -> RootComponent.Config.Settings()
        }
    }

    RootComponent.Config.Proxy -> {
        RootComponent.Config.Proxy
    }

    else -> {
        RootComponent.Config.Home
    }
}

internal fun accountSwitchStack(destination: RootComponent.Config): List<RootComponent.Config> =
    if (destination == RootComponent.Config.Home) {
        listOf(destination)
    } else {
        listOf(RootComponent.Config.Home, destination)
    }

internal fun chatSelectionStack(
    configurations: List<RootComponent.Config>,
    destination: RootComponent.Config.Dialog,
    listDetailVisible: Boolean,
): List<RootComponent.Config> {
    val homeIndex = configurations.indexOf(RootComponent.Config.Home)
    return if (listDetailVisible && homeIndex >= 0) {
        uniqueStack(configurations.take(homeIndex + 1), destination)
    } else {
        uniqueStack(configurations, destination)
    }
}

/**
 * Returns to [destination] when an equal configuration is already in the stack, dropping
 * whatever was open above it. Decompose rejects a stack holding two equal configurations,
 * which happens whenever a screen reopens a chat or profile that is still below it.
 */
internal fun <C> uniqueStack(
    configurations: List<C>,
    destination: C,
): List<C> {
    val existing = configurations.indexOf(destination)
    return if (existing >= 0) configurations.take(existing + 1) else configurations + destination
}

class HomeComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    client: MtprotoClient,
    warmup: OfflineWarmup?,
    sessionStore: SessionMetadataStore?,
    mediaRepository: MediaRepository?,
    notificationLocal: NotificationLocalStore? = null,
    onOpenChat: (PeerId, Boolean, Int) -> Unit,
    onOpenProfile: (PeerId) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProxy: () -> Unit = {},
    onOpenFolders: () -> Unit,
    accountMenuItems: List<AccountMenuItem> = emptyList(),
    onSwitchAccount: (Long) -> Unit = {},
    onAddAccount: () -> Unit = {},
) : ComponentContext by componentContext {
    val chats = ChatsComponent(
        componentContext = componentContext,
        storeFactory = storeFactory,
        client = client,
        warmup = warmup,
        sessionStore = sessionStore,
        notifications = notificationLocal,
        mediaRepository = mediaRepository,
        onOpenChat = onOpenChat,
        onOpenProfile = onOpenProfile,
        onOpenSelfProfile = { onOpenProfile(PeerId(0L)) },
        onOpenSettings = onOpenSettings,
        onOpenProxy = onOpenProxy,
        onOpenFolders = onOpenFolders,
        accountMenuItems = accountMenuItems,
        onSwitchAccount = onSwitchAccount,
        onAddAccount = onAddAccount,
    )
    val folders = FoldersComponent(componentContext, storeFactory, client, warmup)
}
