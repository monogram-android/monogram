// DefaultRootComponent.kt
package org.monogram.presentation.root

import android.os.Parcelable
import android.util.Log
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.bringToFront
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.navigate
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.popWhile
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import org.monogram.domain.managers.PhoneManager
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthStep
import org.monogram.domain.repository.CacheProvider
import org.monogram.domain.repository.ExternalNavigator
import org.monogram.domain.repository.ExternalProxyRepository
import org.monogram.domain.repository.LinkAction
import org.monogram.domain.repository.LinkHandlerRepository
import org.monogram.domain.repository.MessageDisplayer
import org.monogram.domain.repository.MessageRepository
import org.monogram.domain.repository.StickerRepository
import org.monogram.domain.repository.StorageRepository
import org.monogram.domain.repository.UpdateRepository
import org.monogram.domain.repository.UserRepository
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.core.util.coRunCatching
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.features.auth.DefaultAuthComponent
import org.monogram.presentation.features.chats.chatList.DefaultChatListComponent
import org.monogram.presentation.features.chats.currentChat.DefaultChatComponent
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.features.chats.newChat.DefaultNewChatComponent
import org.monogram.presentation.features.profile.DefaultProfileComponent
import org.monogram.presentation.features.profile.admin.DefaultAdminManageComponent
import org.monogram.presentation.features.profile.admin.DefaultChatEditComponent
import org.monogram.presentation.features.profile.admin.DefaultChatPermissionsComponent
import org.monogram.presentation.features.profile.admin.DefaultMemberListComponent
import org.monogram.presentation.features.profile.admin.MemberListComponent
import org.monogram.presentation.features.profile.logs.DefaultProfileLogsComponent
import org.monogram.presentation.features.stickers.core.toUi
import org.monogram.presentation.features.webview.DefaultWebViewComponent
import org.monogram.presentation.settings.about.DefaultAboutComponent
import org.monogram.presentation.settings.adblock.DefaultAdBlockComponent
import org.monogram.presentation.settings.chatSettings.DefaultChatSettingsComponent
import org.monogram.presentation.settings.dataStorage.DefaultDataStorageComponent
import org.monogram.presentation.settings.debug.DefaultDebugComponent
import org.monogram.presentation.settings.folders.DefaultFoldersComponent
import org.monogram.presentation.settings.networkUsage.DefaultNetworkUsageComponent
import org.monogram.presentation.settings.notifications.DefaultNotificationsComponent
import org.monogram.presentation.settings.powersaving.DefaultPowerSavingComponent
import org.monogram.presentation.settings.premium.DefaultPremiumComponent
import org.monogram.presentation.settings.privacy.DefaultPasscodeComponent
import org.monogram.presentation.settings.privacy.DefaultPrivacyComponent
import org.monogram.presentation.settings.profile.DefaultEditProfileComponent
import org.monogram.presentation.settings.proxy.DefaultProxyComponent
import org.monogram.presentation.settings.sessions.DefaultSessionsComponent
import org.monogram.presentation.settings.settings.DefaultSettingsComponent
import org.monogram.presentation.settings.stickers.DefaultStickersComponent
import org.monogram.presentation.settings.storage.DefaultStorageUsageComponent

class DefaultRootComponent(
    private val componentContext: AppComponentContext
) : RootComponent, AppComponentContext by componentContext {

    private val authRepository: AuthRepository = container.repositories.authRepository
    private val messageRepository: MessageRepository = container.repositories.messageRepository
    private val storageRepository: StorageRepository = container.repositories.storageRepository
    private val linkHandlerRepository: LinkHandlerRepository = container.repositories.linkHandlerRepository
    private val externalProxyRepository: ExternalProxyRepository = container.repositories.externalProxyRepository
    private val stickerRepository: StickerRepository = container.repositories.stickerRepository
    private val messageDisplayer: MessageDisplayer = container.utils.messageDisplayer()
    private val externalNavigator: ExternalNavigator = container.utils.externalNavigator()
    private val downloadUtils: IDownloadUtils = container.utils.downloadUtils()
    private val phoneManager: PhoneManager = container.utils.phoneManager()
    private val updateRepository: UpdateRepository = container.repositories.updateRepository
    private val userRepository: UserRepository = container.repositories.userRepository
    private val cacheProvider: CacheProvider = container.cacheProvider

    override val appPreferences: AppPreferences = container.preferences.appPreferences
    override val videoPlayerPool: VideoPlayerPool = container.utils.videoPlayerPool

    private val navigation = StackNavigation<Config>()
    private val scope = componentScope

    private val _stickerSetToPreview = MutableStateFlow(RootComponent.StickerPreviewState())
    override val stickerSetToPreview = _stickerSetToPreview.asStateFlow()

    private val _proxyToConfirm = MutableStateFlow(RootComponent.ProxyConfirmState())
    override val proxyToConfirm = _proxyToConfirm.asStateFlow()

    private val _chatToConfirmJoin = MutableStateFlow(RootComponent.ChatConfirmJoinState())
    override val chatToConfirmJoin = _chatToConfirmJoin.asStateFlow()

    private val _isLocked = MutableStateFlow(false)
    override val isLocked = _isLocked.asStateFlow()

    private val _isBiometricEnabled = MutableStateFlow(false)
    override val isBiometricEnabled = _isBiometricEnabled.asStateFlow()

    private val _activeChatId = MutableValue(0L)
    private val activeChatId: Value<Long> = _activeChatId

    override val childStack: Value<ChildStack<Config, RootComponent.Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = Config.Startup,
        handleBackButton = true,
        childFactory = ::createChild,
        key = "RootStack"
    )

    init {
        childStack.subscribe { stack ->
            _activeChatId.update { (stack.active.configuration as? Config.ChatDetail)?.chatId ?: 0L }
        }

        observeAuthState()
        observeMaintenanceSettings()
        observeBiometricSettings()
        observeStickerLoading()
        checkLockState()
        updateSimCountryIso()
    }

    private fun observeAuthState() {
        authRepository.authState
            .onEach { state ->
                val activeConfig = childStack.value.active.configuration
                when (state) {
                    is AuthStep.Ready -> {
                        if (activeConfig is Config.Auth || activeConfig is Config.Startup) {
                            navigation.replaceAll(Config.Chats())
                        }
                    }

                    is AuthStep.InputPhone,
                    is AuthStep.InputCode,
                    is AuthStep.InputPassword -> {
                        _isLocked.update { false }
                        appPreferences.setPasscode(null)
                        appPreferences.setBiometricEnabled(false)
                        if (activeConfig !is Config.Auth) {
                            navigation.replaceAll(Config.Auth)
                        }
                    }

                    is AuthStep.Loading,
                    is AuthStep.WaitParameters -> {
                        if (activeConfig !is Config.Startup && activeConfig !is Config.Auth) {
                            navigation.replaceAll(Config.Startup)
                        }
                    }
                }
            }
            .launchIn(scope)
    }

    private fun observeMaintenanceSettings() {
        combine(appPreferences.cacheLimitSize, appPreferences.autoClearCacheTime) { limit, time ->
            limit to time
        }.onEach { (limit, time) ->
            val ttl = if (time > 0) time * 24 * 60 * 60 else -1
            storageRepository.setDatabaseMaintenanceSettings(limit, ttl)
        }.launchIn(scope)
    }

    private fun observeBiometricSettings() {
        appPreferences.isBiometricEnabled
            .onEach { bool -> _isBiometricEnabled.update { bool } }
            .launchIn(scope)
    }

    private fun observeStickerLoading() {
        authRepository.authState
            .filterIsInstance<AuthStep.Ready>()
            .onEach {
                stickerRepository.loadInstalledStickerSets()
                stickerRepository.loadCustomEmojiStickerSets()
            }
            .launchIn(scope)
    }

    private fun checkLockState() {
        scope.launch {
            if (appPreferences.passcode.first() != null) {
                _isLocked.update { true }
            }
        }
    }

    private fun updateSimCountryIso() {
        val countryCode = phoneManager.getSimCountryIso()
        if (!countryCode.isNullOrBlank()) {
            scope.launch {
                userRepository.setCachedSimCountryIso(countryCode)
            }
        }
    }

    override fun onBack() {
        navigation.pop()
    }

    override fun onSettingsClick() {
        navigation.bringToFront(Config.Settings)
    }

    override fun onChatsClick() {
        navigation.popWhile { it !is Config.Chats }
    }

    override fun dismissStickerPreview() {
        _stickerSetToPreview.update { it.copy(stickerSet = null) }
    }

    override fun handleLink(link: String) {
        scope.launch {
            coRunCatching {
                linkHandlerRepository.handleLink(link)
            }.onSuccess { action ->
                processLinkAction(action, link)
            }.onFailure { e ->
                Log.e("RootComponent", "Error handling link: $link", e)
                messageDisplayer.show("Error handling link")
                openBrowser(link)
            }
        }
    }

    private suspend fun processLinkAction(action: LinkAction, originalLink: String) {
        when (action) {
            is LinkAction.OpenChat -> navigateToChat(action.chatId)
            is LinkAction.OpenUser -> navigation.bringToFront(Config.Profile(action.userId))
            is LinkAction.OpenMessage -> navigateToChat(action.chatId, action.messageId)

            is LinkAction.OpenSettings -> {
                val config = when (action.settingsType) {
                    LinkAction.SettingsType.MAIN -> Config.Settings
                    LinkAction.SettingsType.PRIVACY -> Config.Privacy
                    LinkAction.SettingsType.SESSIONS -> Config.SessionsConfig
                    LinkAction.SettingsType.FOLDERS -> Config.Folders
                    LinkAction.SettingsType.CHAT -> Config.ChatSettings
                    LinkAction.SettingsType.DATA_STORAGE -> Config.DataStorage
                    LinkAction.SettingsType.POWER_SAVING -> Config.PowerSaving
                    LinkAction.SettingsType.PREMIUM -> Config.Premium
                }
                navigation.bringToFront(config)
            }

            is LinkAction.OpenStickerSet -> {
                val set = stickerRepository.getStickerSetByName(action.name)
                if (set != null) {
                    _stickerSetToPreview.update { it.copy(stickerSet = set.toUi()) }
                } else {
                    messageDisplayer.show("Sticker set not found")
                }
            }

            is LinkAction.JoinChat -> {
                val chatId = linkHandlerRepository.joinChat(action.inviteLink)
                if (chatId != null) {
                    navigateToChat(chatId)
                }
            }

            is LinkAction.ConfirmJoinChat -> {
                _chatToConfirmJoin.update { RootComponent.ChatConfirmJoinState(chat = action.chat, fullInfo = action.fullInfo) }
            }

            is LinkAction.ConfirmJoinInviteLink -> {
                _chatToConfirmJoin.update {
                    RootComponent.ChatConfirmJoinState(
                        inviteLink = action.inviteLink,
                        inviteTitle = action.title,
                        inviteDescription = action.description,
                        inviteMemberCount = action.memberCount,
                        inviteAvatarPath = action.avatarPath,
                        inviteIsChannel = action.isChannel
                    )
                }
            }

            is LinkAction.OpenWebApp -> openBrowser(action.url)
            is LinkAction.OpenExternalLink -> openBrowser(action.url)
            is LinkAction.OpenActiveSessions -> navigation.bringToFront(Config.SessionsConfig)
            is LinkAction.ShowToast -> messageDisplayer.show(action.message)
            is LinkAction.AddProxy -> {
                _proxyToConfirm.update {
                    RootComponent.ProxyConfirmState(
                        server = action.server,
                        port = action.port,
                        type = action.type,
                        ping = null,
                        isChecking = true
                    )
                }
                recheckProxyPing()
            }
            LinkAction.None -> openBrowser(originalLink)
        }
    }

    override fun dismissProxyConfirm() {
        _proxyToConfirm.update { RootComponent.ProxyConfirmState() }
    }

    override fun confirmProxy(server: String, port: Int, type: ProxyTypeModel) {
        scope.launch {
            val proxy = externalProxyRepository.addProxy(server, port, true, type)
            dismissProxyConfirm()
            if (proxy != null) {
                messageDisplayer.show("Proxy added and enabled")
            } else {
                messageDisplayer.show("Failed to add proxy")
            }
        }
    }

    override fun recheckProxyPing() {
        val currentState = _proxyToConfirm.value
        val server = currentState.server ?: return
        val port = currentState.port ?: return
        val type = currentState.type ?: return

        _proxyToConfirm.update { it.copy(isChecking = true, ping = null) }

        scope.launch {
            val ping = try {
                withContext(Dispatchers.IO) {
                    externalProxyRepository.testProxy(server, port, type)
                } ?: -1L
            } catch (e: Exception) {
                -1L
            }

            if (_proxyToConfirm.value.server == server && _proxyToConfirm.value.port == port) {
                _proxyToConfirm.update { it.copy(ping = ping, isChecking = false) }
            }
        }
    }

    override fun dismissChatConfirmJoin() {
        _chatToConfirmJoin.update { RootComponent.ChatConfirmJoinState() }
    }

    override fun confirmJoinChat(chatId: Long) {
        scope.launch {
            messageRepository.joinChat(chatId)
            dismissChatConfirmJoin()
            navigateToChat(chatId)
        }
    }

    override fun confirmJoinInviteLink(inviteLink: String) {
        scope.launch {
            val chatId = linkHandlerRepository.joinChat(inviteLink)
            dismissChatConfirmJoin()
            if (chatId != null) {
                navigateToChat(chatId)
            }
        }
    }

    override fun unlock(passcode: String): Boolean {
        val savedPasscode = appPreferences.passcode.value
        return if (savedPasscode == passcode) {
            _isLocked.update { false }
            true
        } else {
            false
        }
    }

    override fun unlockWithBiometrics() {
        _isLocked.update { false }
    }

    override fun logout() {
        authRepository.reset()
    }

    private fun openBrowser(url: String) {
        if (!url.startsWith("http")) return
        navigation.push(Config.WebView(url))
    }

    override fun navigateToChat(chatId: Long, messageId: Long?) {
        navigation.navigate { stack ->
            val newStack = stack.filterNot { it is Config.ChatDetail && it.chatId == chatId }
            newStack + Config.ChatDetail(chatId, messageId)
        }
    }

    @OptIn(DelicateDecomposeApi::class)
    private fun createChild(config: Config, context: AppComponentContext): RootComponent.Child {
        return when (config) {
            is Config.Startup -> RootComponent.Child.StartupChild(DefaultStartupComponent(context))
            is Config.Auth -> RootComponent.Child.AuthChild(
                DefaultAuthComponent(
                    context = context,
                    onOpenProxy = { navigation.bringToFront(Config.Proxy) }
                )
            )
            is Config.Chats -> RootComponent.Child.ChatsChild(
                DefaultChatListComponent(
                    context = context,
                    onSelect = { chatId, msgId ->
                        if (chatId == 0L) navigation.pop() else navigateToChat(chatId, msgId)
                    },
                    onProfileSelect = { chatId ->
                        navigation.bringToFront(Config.Profile(chatId))
                    },
                    onSettingsClick = { navigation.bringToFront(Config.Settings) },
                    onProxySettingsClick = { navigation.bringToFront(Config.Proxy) },
                    onConfirmForward = { targetChatIds ->
                        if (config.forwardingMessageIds != null) {
                            scope.launch {
                                targetChatIds.forEach { targetChatId ->
                                    config.forwardingMessageIds.forEach { msgId ->
                                        messageRepository.forwardMessage(targetChatId, config.fromChatId ?: 0L, msgId)
                                    }
                                }
                            }
                            navigation.pop()
                            if (targetChatIds.size == 1) navigateToChat(targetChatIds.first())
                        }
                    },
                    isForwarding = config.forwardingMessageIds != null,
                    onNewChatClick = { navigation.bringToFront(Config.NewChat) },
                    onEditFoldersClick = { navigation.bringToFront(Config.Folders) },
                    activeChatId = activeChatId
                )
            )
            is Config.NewChat -> RootComponent.Child.NewChatChild(
                DefaultNewChatComponent(
                    context = context,
                    onBackClicked = { navigation.pop() },
                    onChatCreated = { chatId ->
                        navigation.pop()
                        navigateToChat(chatId)
                    },
                    onProfileClicked = { userId ->
                        navigation.bringToFront(Config.Profile(chatId = userId))
                    }
                )
            )
            is Config.ChatDetail -> RootComponent.Child.ChatDetailChild(
                DefaultChatComponent(
                    context = context,
                    chatId = config.chatId,
                    onBack = { navigation.pop() },
                    onProfileClick = { navigation.bringToFront(Config.Profile(config.chatId)) },
                    onForward = { fromChatId, messageIds ->
                        navigation.push(Config.Chats(fromChatId = fromChatId, forwardingMessageIds = messageIds))
                    },
                    onLink = { handleLink(it) },
                    initialMessageId = config.messageId,
                    toProfiles = { id -> navigation.bringToFront(Config.Profile(chatId = id)) }
                )
            )
            is Config.Settings -> RootComponent.Child.SettingsChild(
                DefaultSettingsComponent(
                    context = context,
                    onBack = { navigation.pop() },
                    onEditProfileClick = { navigation.push(Config.EditProfile) },
                    onDevicesClick = { navigation.bringToFront(Config.SessionsConfig) },
                    onFoldersClick = { navigation.bringToFront(Config.Folders) },
                    onChatSettingsClick = { navigation.bringToFront(Config.ChatSettings) },
                    onDataStorageClick = { navigation.bringToFront(Config.DataStorage) },
                    onPowerSavingClick = { navigation.bringToFront(Config.PowerSaving) },
                    onPremiumClick = { navigation.bringToFront(Config.Premium) },
                    onPrivacyClick = { navigation.bringToFront(Config.Privacy) },
                    onNotificationsClick = { navigation.bringToFront(Config.Notifications) },
                    onProxySettingsClick = { navigation.bringToFront(Config.Proxy) },
                    onStickersClick = { navigation.bringToFront(Config.Stickers) },
                    onAboutClick = { navigation.bringToFront(Config.About) },
                    onDebugClick = { navigation.bringToFront(Config.Debug) }
                )
            )

            is Config.EditProfile -> RootComponent.Child.EditProfileChild(
                DefaultEditProfileComponent(
                    context = context,
                    onBackClicked = { navigation.pop() }
                )
            )
            is Config.SessionsConfig -> RootComponent.Child.SessionsChild(
                DefaultSessionsComponent(context = context, onBack = { navigation.pop() })
            )
            is Config.Folders -> RootComponent.Child.FoldersChild(
                DefaultFoldersComponent(context = context, onBack = { navigation.pop() })
            )
            is Config.ChatSettings -> RootComponent.Child.ChatSettingsChild(
                DefaultChatSettingsComponent(
                    context = context,
                    onBack = { navigation.pop() },
                    onAdBlock = { navigation.bringToFront(Config.AdBlock) })
            )
            is Config.DataStorage -> RootComponent.Child.DataStorageChild(
                DefaultDataStorageComponent(
                    context = context,
                    onBack = { navigation.pop() },
                    onStorageUsage = { navigation.bringToFront(Config.StorageUsage) },
                    onNetworkUsage = { navigation.bringToFront(Config.NetworkUsage) })
            )
            is Config.StorageUsage -> RootComponent.Child.StorageUsageChild(
                DefaultStorageUsageComponent(context = context, onBack = { navigation.pop() })
            )
            is Config.NetworkUsage -> RootComponent.Child.NetworkUsageChild(
                DefaultNetworkUsageComponent(context = context, onBack = { navigation.pop() })
            )
            is Config.Profile -> RootComponent.Child.ProfileChild(
                DefaultProfileComponent(
                    context = context,
                    chatId = config.chatId,
                    onBackClicked = { navigation.pop() },
                    onMessageClicked = { message ->
                        val path = when (val content = message.content) {
                            is MessageContent.Document -> content.path
                            is MessageContent.Voice -> content.path
                            else -> null
                        }
                        if (path != null) downloadUtils.openFile(path)
                        else messageDisplayer.show("Not implemented")
                    },
                    onAvatarClicked = { },
                    onEditClicked = {
                        scope.launch {
                            val me = userRepository.getMe()
                            if (config.chatId == me.id) {
                                navigation.push(Config.EditProfile)
                            } else {
                                navigation.push(Config.ChatEdit(config.chatId))
                            }
                        }
                    },
                    onSendMessageClicked = { navigateToChat(it) },
                    onShowLogsClicked = { navigation.push(Config.ProfileLogs(it)) },
                    onMemberLongClicked = { chatId, userId -> navigation.push(Config.AdminManage(chatId, userId)) }
                )
            )
            is Config.Premium -> RootComponent.Child.PremiumChild(
                DefaultPremiumComponent(context = context, onBack = { navigation.pop() })
            )
            is Config.Privacy -> RootComponent.Child.PrivacyChild(
                DefaultPrivacyComponent(
                    context = context,
                    onBack = { navigation.pop() },
                    onSessionsClick = { navigation.bringToFront(Config.SessionsConfig) },
                    onProfileClick = { navigation.bringToFront(Config.Profile(it)) },
                    onPasscodeClick = { navigation.push(Config.PasscodeConfig) })
            )
            is Config.AdBlock -> RootComponent.Child.AdBlockChild(
                DefaultAdBlockComponent(context = context, onBack = { navigation.pop() })
            )
            is Config.PowerSaving -> RootComponent.Child.PowerSavingChild(
                DefaultPowerSavingComponent(context = context, onBack = { navigation.pop() })
            )
            is Config.Notifications -> RootComponent.Child.NotificationsChild(
                DefaultNotificationsComponent(context = context, onBack = { navigation.pop() })
            )
            is Config.Proxy -> RootComponent.Child.ProxyChild(
                DefaultProxyComponent(context = context, onBack = { navigation.pop() })
            )
            is Config.ProfileLogs -> RootComponent.Child.ProfileLogsChild(
                DefaultProfileLogsComponent(
                    context = context,
                    chatId = config.chatId,
                    onBackClicked = { navigation.pop() },
                    onUserClicked = { navigation.bringToFront(Config.Profile(it)) },
                    downloadUtils = downloadUtils
                )
            )
            is Config.AdminManage -> RootComponent.Child.AdminManageChild(
                DefaultAdminManageComponent(
                    context = context,
                    chatId = config.chatId,
                    userId = config.userId,
                    onBackClicked = { navigation.pop() }
                )
            )
            is Config.ChatEdit -> RootComponent.Child.ChatEditChild(
                DefaultChatEditComponent(
                    context = context,
                    chatId = config.chatId,
                    onBackClicked = { navigation.pop() },
                    onManageAdminsClicked = {
                        navigation.push(
                            Config.MemberList(
                                it,
                                MemberListComponent.MemberListType.ADMINS
                            )
                        )
                    },
                    onManageMembersClicked = {
                        navigation.push(
                            Config.MemberList(
                                it,
                                MemberListComponent.MemberListType.MEMBERS
                            )
                        )
                    },
                    onManageBlacklistClicked = {
                        navigation.push(
                            Config.MemberList(
                                it,
                                MemberListComponent.MemberListType.BLACKLIST
                            )
                        )
                    },
                    onManagePermissionsClicked = { navigation.push(Config.ChatPermissions(it)) }
                )
            )

            is Config.MemberList -> RootComponent.Child.MemberListChild(
                DefaultMemberListComponent(
                    context = context,
                    chatId = config.chatId,
                    type = config.type,
                    onBackClicked = { navigation.pop() },
                    onMemberClicked = { navigation.push(Config.Profile(it)) },
                    onMemberLongClicked = { navigation.push(Config.AdminManage(config.chatId, it)) }
                )
            )

            is Config.ChatPermissions -> RootComponent.Child.ChatPermissionsChild(
                DefaultChatPermissionsComponent(
                    context = context,
                    chatId = config.chatId,
                    onBackClicked = { navigation.pop() }
                )
            )

            is Config.PasscodeConfig -> RootComponent.Child.PasscodeChild(
                DefaultPasscodeComponent(context = context, onBack = { navigation.pop() })
            )

            is Config.Stickers -> RootComponent.Child.StickersChild(
                DefaultStickersComponent(
                    context = context,
                    onBack = { navigation.pop() },
                    onStickerSetSelect = { set ->
                        _stickerSetToPreview.update { it.copy(stickerSet = set.toUi()) }
                    }
                )
            )

            is Config.About -> RootComponent.Child.AboutChild(
                DefaultAboutComponent(
                    context = context,
                    updateRepository = updateRepository,
                    onBack = { navigation.pop() },
                    onTermsOfService = { externalNavigator.openUrl("https://telegram.org/tos") },
                    onOpenSourceLicenses = { externalNavigator.openOssLicenses() }
                )
            )

            is Config.Debug -> RootComponent.Child.DebugChild(
                DefaultDebugComponent(
                    context = context,
                    onBack = { navigation.pop() }
                )
            )

            is Config.WebView -> RootComponent.Child.WebViewChild(
                DefaultWebViewComponent(
                    context = context,
                    url = config.url,
                    onDismiss = { navigation.pop() }
                )
            )
        }
    }

    @Serializable
    sealed class Config : Parcelable {
        @Parcelize @Serializable object Startup : Config()
        @Parcelize @Serializable object Auth : Config()
        @Parcelize @Serializable data class Chats(
            val fromChatId: Long? = null,
            val forwardingMessageIds: List<Long>? = null,
            val activeChatId: Long? = null
        ) : Config()
        @Parcelize @Serializable object NewChat : Config()
        @Parcelize @Serializable object SessionsConfig : Config()
        @Parcelize @Serializable data class ChatDetail(val chatId: Long, val messageId: Long? = null) : Config()
        @Parcelize @Serializable object Settings : Config()
        @Parcelize @Serializable object EditProfile : Config()
        @Parcelize @Serializable object Folders : Config()
        @Parcelize @Serializable object ChatSettings : Config()
        @Parcelize @Serializable object DataStorage : Config()
        @Parcelize @Serializable object StorageUsage : Config()
        @Parcelize @Serializable object NetworkUsage : Config()
        @Parcelize @Serializable data class Profile(val chatId: Long) : Config()
        @Parcelize @Serializable object Premium : Config()
        @Parcelize @Serializable object Privacy : Config()
        @Parcelize @Serializable object AdBlock : Config()
        @Parcelize @Serializable object PowerSaving : Config()
        @Parcelize @Serializable object Notifications : Config()
        @Parcelize @Serializable object Proxy : Config()
        @Parcelize @Serializable data class ProfileLogs(val chatId: Long) : Config()
        @Parcelize @Serializable data class AdminManage(val chatId: Long, val userId: Long) : Config()
        @Parcelize @Serializable data class ChatEdit(val chatId: Long) : Config()
        @Parcelize @Serializable data class MemberList(val chatId: Long, val type: MemberListComponent.MemberListType) : Config()
        @Parcelize @Serializable data class ChatPermissions(val chatId: Long) : Config()
        @Parcelize @Serializable object PasscodeConfig : Config()
        @Parcelize @Serializable object Stickers : Config()
        @Parcelize @Serializable object About : Config()
        @Parcelize @Serializable object Debug : Config()
        @Parcelize
        @Serializable
        data class WebView(val url: String) : Config()
    }
}