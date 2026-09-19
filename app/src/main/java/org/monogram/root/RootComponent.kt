package org.monogram.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnStart
import com.arkivanov.essenty.lifecycle.doOnStop
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.pushNew
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.router.stack.navigate
import com.arkivanov.decompose.value.Value
import com.arkivanov.mvikotlin.core.store.StoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.monogram.core.common.Outcome
import org.monogram.core.common.telegram.TelegramLink
import org.monogram.core.common.telegram.parseTelegramLink
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.markup.NativeMarkupParser
import org.monogram.core.models.AccountState
import org.monogram.core.models.PeerId
import org.monogram.feature.auth.AuthComponent
import org.monogram.feature.chats.ChatsComponent
import org.monogram.feature.dialog.DialogComponent
import org.monogram.feature.folders.FoldersComponent
import org.monogram.feature.profile.ProfileComponent
import org.monogram.feature.settings.SettingsComponent
import org.monogram.BuildConfig
import org.monogram.core.common.push.PushRegistration
import org.monogram.core.common.push.NotificationLocalStore
import org.monogram.network.bridge.BridgedMtprotoClient
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import kotlinx.coroutines.CancellationException
import org.monogram.core.common.AppLog
import org.monogram.network.http.MediaRepository
import org.monogram.core.ui.ImageCache

class RootComponent(
    componentContext: ComponentContext,
    private val storeFactory: StoreFactory,
    private val client: MtprotoClient,
    private val warmup: OfflineWarmup?,
    private val sessionStore: SessionMetadataStore?,
    private val mediaRepository: MediaRepository?,
    startOnHome: Boolean,
    private val accountState: AccountState = AccountState(),
    private val pushRegistration: PushRegistration? = null,
    private val notificationLocal: NotificationLocalStore? = null,
) : ComponentContext by componentContext {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navigation = StackNavigation<Config>()
    private var expiringSession = false
    private var accountFlagsVersion = 0L
    private var listDetailVisible = false

    fun setListDetailVisible(visible: Boolean) {
        listDetailVisible = visible
    }

    fun openFromNotification(chatId: Long, messageId: Int = 0) {
        if (stack.value.active.configuration is Config.Auth) return
        AppLog.api("notify", "open chat=$chatId")
        pushRegistration?.onVisibleChat(chatId)
        navigation.navigate { configurations ->
            chatSelectionStack(
                configurations,
                Config.Dialog(chatId, jumpToMessageId = messageId),
                listDetailVisible,
            )
        }
    }

    fun openChatForPlayback(chatId: Long, messageId: Int = 0) {
        if (stack.value.active.configuration is Config.Auth) return
        navigation.navigate { configurations ->
            chatSelectionStack(
                configurations,
                Config.Dialog(chatId, jumpToMessageId = messageId),
                listDetailVisible,
            )
        }
    }

    private fun openChatFromList(peer: PeerId, forum: Boolean) {
        val destination = Config.Dialog(peer.value, isForum = forum)
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

    private fun openSettings(openFolders: Boolean) {
        navigation.navigate { configurations ->
            uniqueStack(configurations, Config.Settings(openFolders = openFolders))
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

    val stack: Value<ChildStack<Config, Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = if (startOnHome) Config.Home else Config.Auth,
        handleBackButton = true,
        childFactory = ::child,
    )

    fun onBack() {
        navigation.pop()
    }

    fun openTelegramUri(uri: String): Boolean {
        val link = parseTelegramLink(uri) ?: return false
        if (stack.value.active.configuration is Config.Auth) return true
        scope.launch { openTelegramLink(link) }
        return true
    }

    private suspend fun openTelegramLink(link: TelegramLink) {
        when (link) {
            is TelegramLink.Username -> {
                when (val resolved = client.resolveUsername(link.username)) {
                    is Outcome.Ok -> openFromNotification(
                        resolved.value.peerId.value,
                        link.messageId ?: 0,
                    )
                    is Outcome.Err -> AppLog.warn("t.me", "resolve failed")
                }
            }
            is TelegramLink.PrivateChannel ->
                openFromNotification(link.chatId, link.messageId)
            is TelegramLink.Invite, is TelegramLink.Share ->
                AppLog.api("t.me", "link type ignored")
        }
    }

    init {
        if (startOnHome && stack.value.active.configuration is Config.Auth) {
            navigation.replaceAll(Config.Home)
        }
        stack.subscribe { childStack ->
            val chatId = (childStack.active.configuration as? Config.Dialog)?.chatId
            pushRegistration?.onVisibleChat(chatId)
        }
        lifecycle.doOnStart {
            if (stack.value.active.configuration !is Config.Auth) {
                scope.launch { runCatching { pushRegistration?.reregister() } }
            }
        }
        lifecycle.doOnDestroy { scope.cancel() }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.sessionLost().collect { expireSession() }
        }
        scope.launch {
            client.updates().collect { update ->
                if (expiringSession) return@collect
                try {
                    when (update) {
                        is MtprotoUpdate.NewMessage -> warmup?.applyIncomingMessage(update.message)
                        is MtprotoUpdate.MessageEdited -> warmup?.applyMessageEdit(update.message)
                        is MtprotoUpdate.MessagesDeleted -> warmup?.deleteMessages(update.chatId, update.messageIds)
                        is MtprotoUpdate.MessageReactions -> warmup?.applyReactions(update.chatId, update.messageId, update.reactionsJson)
                        is MtprotoUpdate.ReadOutbox -> warmup?.applyOutboxRead(update.chatId, update.maxId)
                        is MtprotoUpdate.DiscussionInbox -> warmup?.applyDiscussionRead(update.channelId, update.topMessageId, update.readMaxId)
                        is MtprotoUpdate.FoldersChanged -> warmup?.replaceFolders(update.folders)
                        is MtprotoUpdate.ChatsChanged -> {
                            warmup?.upsertChats(update.chats)
                            sessionStore?.upsertPeersFromChats(update.chats)
                        }
                        is MtprotoUpdate.PeerStatus -> sessionStore?.updatePeerStatus(update.userId.value, update.status, update.statusAt)
                        is MtprotoUpdate.PeerEmojiStatus -> sessionStore?.updatePeerEmojiStatus(update.userId.value, update.documentId)
                        is MtprotoUpdate.ReadInbox -> {
                            warmup?.applyInboxRead(
                                update.chatId, update.maxId, update.stillUnread,
                            )
                            if (update.stillUnread <= 0) {
                                pushRegistration?.onChatRead(update.chatId.value)
                            }
                        }
                        is MtprotoUpdate.ReadHistoryConfirmed -> {
                            warmup?.markChatRead(
                                update.chatId, update.maxId,
                            )
                            pushRegistration?.onChatRead(update.chatId.value)
                        }
                        is MtprotoUpdate.UnreadMentions -> warmup?.applyUnreadMentions(
                            update.chatId,
                            update.stillUnread,
                        )
                        is MtprotoUpdate.UnreadReactions -> warmup?.applyUnreadReactions(
                            update.chatId,
                            update.stillUnread,
                        )
                        is MtprotoUpdate.UnreadMentionsDelta -> warmup?.addUnreadMentions(
                            update.chatId,
                            update.delta,
                        )
                        is MtprotoUpdate.UnreadReactionsDelta -> warmup?.addUnreadReactions(
                            update.chatId,
                            update.delta,
                        )
                        else -> Unit
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    AppLog.warn("read-state", "cache update failed")
                }
                if (!expiringSession && update is org.monogram.network.bridge.MtprotoUpdate.AccountPremium) {
                    accountFlagsVersion++
                    accountState.isPremium = update.isPremium
                    sessionStore?.savePremium(update.isPremium)
                }
            }
        }
        scope.launch {
            val bridged = client as? BridgedMtprotoClient ?: return@launch
            val result = withContext(Dispatchers.IO) { bridged.isAuthorized() }
            if (result is Outcome.Ok && result.value) {
                if (!expiringSession && stack.value.active.configuration is Config.Auth) {
                    navigation.replaceAll(Config.Home)
                }
                refreshAccountFlags()
            } else if (requiresSessionReset(result)) {
                expireSession()
            }
        }
        lifecycle.doOnStart {
            scope.launch(Dispatchers.IO) { client.updateStatus(offline = false) }
        }
        lifecycle.doOnStop {
            scope.launch(Dispatchers.IO) { client.updateStatus(offline = true) }
        }
    }

    private suspend fun refreshAccountFlags() {
        if (expiringSession) return
        val version = accountFlagsVersion
        val cachedPremium = sessionStore?.readPremium() ?: false
        if (expiringSession || version != accountFlagsVersion) return
        accountState.isPremium = cachedPremium
        when (val profile = client.getProfile(PeerId(0))) {
            is Outcome.Ok -> {
                if (expiringSession || version != accountFlagsVersion) return
                accountState.isPremium = profile.value.isPremium
                sessionStore?.savePremium(profile.value.isPremium)
                sessionStore?.upsertProfile(profile.value)
            }
            is Outcome.Err -> Unit
        }
    }

    private suspend fun expireSession() {
        if (expiringSession) return
        expiringSession = true
        accountFlagsVersion++
        accountState.isPremium = false
        // Destroy account screens and their collectors before clearing their cache.
        navigation.replaceAll(Config.Auth)
        withContext(Dispatchers.IO) {
            warmup?.clearAccountCache()
            sessionStore?.clearSession()
            mediaRepository?.clearCache()
            ImageCache.clear()
        }
    }

    private fun child(config: Config, context: ComponentContext): Child = when (config) {
        Config.Auth -> Child.Auth(
            AuthComponent(
                componentContext = context,
                storeFactory = storeFactory,
                client = client,
                onAuthorized = { session ->
                    expiringSession = false
                    scope.launch {
                        sessionStore?.saveAuthorized(session)
                        refreshAccountFlags()
                        runCatching { pushRegistration?.reregister() }
                    }
                    navigation.replaceAll(Config.Home)
                },
            ),
        )
        Config.Home -> Child.Home(
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
                onOpenSettings = { openSettings(openFolders = false) },
                onOpenFolders = { openSettings(openFolders = true) },
            ),
        )
        is Config.Dialog -> Child.Dialog(
            DialogComponent(
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
                onBack = { navigation.pop() },
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
            ),
        )
        is Config.Profile -> Child.Profile(
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
        is Config.Settings -> Child.Settings(
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
                onLoggedOut = { navigation.replaceAll(Config.Auth) },
                pushRegistration = pushRegistration,
                debugNotifications = BuildConfig.DEBUG,
                notificationLocal = notificationLocal,
                openFolders = config.openFolders,
            ),
            folders = FoldersComponent(
                componentContext = context,
                storeFactory = storeFactory,
                client = client,
                warmup = warmup,
            ),
        )
    }

    sealed class Child {
        class Auth(val component: AuthComponent) : Child()
        class Home(val component: HomeComponent) : Child()
        class Dialog(val component: DialogComponent) : Child()
        class Profile(val component: ProfileComponent) : Child()
        class Settings(
            val component: SettingsComponent,
            val folders: FoldersComponent,
        ) : Child()
    }

    @Serializable
    sealed interface Config {
        @Serializable
        data object Auth : Config

        @Serializable
        data object Home : Config

        @Serializable
        data class Dialog(
            val chatId: Long,
            val jumpToMessageId: Int = 0,
            val threadTopMsgId: Int = 0,
            val isForum: Boolean? = null,
        ) : Config

        @Serializable
        data class Profile(val peerId: Long) : Config

        @Serializable
        data class Settings(val openFolders: Boolean = false) : Config
    }
}

internal fun requiresSessionReset(result: Outcome<Boolean>): Boolean = when (result) {
    is Outcome.Ok -> !result.value
    is Outcome.Err -> result.telegramError.requiresReauth
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
internal fun <C> uniqueStack(configurations: List<C>, destination: C): List<C> {
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
    onOpenChat: (PeerId, Boolean) -> Unit,
    onOpenProfile: (PeerId) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFolders: () -> Unit,
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
        onOpenFolders = onOpenFolders,
    )
    val folders = FoldersComponent(componentContext, storeFactory, client, warmup)
}
