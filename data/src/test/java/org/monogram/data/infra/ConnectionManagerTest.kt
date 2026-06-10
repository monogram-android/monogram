package org.monogram.data.infra

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.DispatcherProvider
import org.monogram.data.datasource.remote.ChatRemoteSource
import org.monogram.data.datasource.remote.ProxyRemoteDataSource
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.domain.models.ProxyModel
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.ConnectionStatus
import org.monogram.domain.repository.DEFAULT_SMART_SWITCH_CHECK_INTERVAL_MINUTES
import org.monogram.domain.repository.ProxyNetworkMode
import org.monogram.domain.repository.ProxyNetworkRule
import org.monogram.domain.repository.ProxyNetworkType
import org.monogram.domain.repository.ProxySmartSwitchMode
import org.monogram.domain.repository.ProxySortMode
import org.monogram.domain.repository.ProxyUnavailableFallback
import org.monogram.domain.repository.PushProvider

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerTest {
    @Test
    fun `presence becomes online only when auth ready foreground and usable network`() =
        runManagerTest {
            authFlow.emit(
                TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady())
            )
            foregroundTracker.setForeground(true)
            networkProvider.update(
                NetworkSnapshot(
                    isAvailable = true,
                    isUsable = true,
                    type = ProxyNetworkType.WIFI,
                    networkId = 1
                )
            )

            scope.flush()
            scope.advanceAndFlush(800L)

            assertTrue(connectionManager.presenceOnlineFlow.value)
            assertEquals(listOf(true), proxyRemoteSource.onlineOptions)
        }

    @Test
    fun `background flips presence offline without stopping transport`() = runManagerTest {
        authFlow.emit(
            TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady())
        )
        foregroundTracker.setForeground(true)
        networkProvider.update(
            NetworkSnapshot(
                isAvailable = true,
                isUsable = true,
                type = ProxyNetworkType.WIFI,
                networkId = 1
            )
        )
        scope.flush()
        scope.advanceAndFlush(800L)

        val reconnectsBeforeBackground = chatRemoteSource.setNetworkTypeCalls

        foregroundTracker.setForeground(false)
        scope.advanceAndFlush(800L)

        assertFalse(connectionManager.presenceOnlineFlow.value)
        assertEquals(listOf(true, false), proxyRemoteSource.onlineOptions)
        assertEquals(reconnectsBeforeBackground, chatRemoteSource.setNetworkTypeCalls)
    }

    @Test
    fun `auth ready while offline policy applies false presence once`() = runManagerTest {
        authFlow.emit(
            TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady())
        )
        networkProvider.update(
            NetworkSnapshot(
                isAvailable = true,
                isUsable = false,
                type = ProxyNetworkType.WIFI,
                networkId = 1
            )
        )

        scope.flush()
        scope.advanceAndFlush(800L)

        assertFalse(connectionManager.presenceOnlineFlow.value)
        assertEquals(listOf(false), proxyRemoteSource.onlineOptions)
    }

    @Test
    fun `same effective snapshot does not repeat reconnect or proxy apply`() = runManagerTest {
        authFlow.emit(
            TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady())
        )
        val snapshot = NetworkSnapshot(
            isAvailable = true,
            isUsable = true,
            type = ProxyNetworkType.WIFI,
            networkId = 5
        )
        networkProvider.update(snapshot)
        scope.advanceAndFlush(400L)

        val reconnectCalls = chatRemoteSource.setNetworkTypeCalls
        val enableCalls = proxyRemoteSource.enableProxyCalls

        networkProvider.update(snapshot.copy())
        scope.advanceAndFlush(500L)

        assertEquals(reconnectCalls, chatRemoteSource.setNetworkTypeCalls)
        assertEquals(enableCalls, proxyRemoteSource.enableProxyCalls)
    }

    @Test
    fun `wifi to mobile triggers one proxy reapply and reconnect`() = runManagerTest {
        authFlow.emit(
            TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady())
        )
        preferences.setProxyNetworkMode(
            ProxyNetworkType.WIFI,
            ProxyNetworkMode.SPECIFIC_PROXY
        )
        preferences.setSpecificProxyIdForNetwork(ProxyNetworkType.WIFI, 1)
        preferences.setProxyNetworkMode(
            ProxyNetworkType.MOBILE,
            ProxyNetworkMode.SPECIFIC_PROXY
        )
        preferences.setSpecificProxyIdForNetwork(ProxyNetworkType.MOBILE, 2)
        proxyRemoteSource.proxies = listOf(
            proxy(id = 1, enabled = true),
            proxy(id = 2, enabled = false)
        )
        networkProvider.update(
            NetworkSnapshot(true, true, ProxyNetworkType.WIFI, 11)
        )
        scope.advanceAndFlush(400L)

        val reconnectCallsBefore = chatRemoteSource.setNetworkTypeCalls
        val enableCallsBefore = proxyRemoteSource.enableProxyCalls

        networkProvider.update(
            NetworkSnapshot(true, true, ProxyNetworkType.MOBILE, 12)
        )
        scope.advanceAndFlush(500L)

        assertEquals(reconnectCallsBefore + 1, chatRemoteSource.setNetworkTypeCalls)
        assertEquals(enableCallsBefore + 1, proxyRemoteSource.enableProxyCalls)
        assertEquals(2, preferences.enabledProxyId.value)
    }

    @Test
    fun `waiting for network is published immediately on usable network loss`() = runManagerTest {
        authFlow.emit(
            TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady())
        )
        networkProvider.update(
            NetworkSnapshot(true, true, ProxyNetworkType.WIFI, 1)
        )
        scope.advanceAndFlush(400L)

        networkProvider.update(
            NetworkSnapshot(true, false, ProxyNetworkType.WIFI, 1)
        )
        scope.flush()

        assertEquals(
            ConnectionStatus.WaitingForNetwork,
            connectionManager.connectionStateFlow.value
        )
    }

    @Test
    fun `transient updating after connected stays hidden during grace window`() = runManagerTest {
        authFlow.emit(
            TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady())
        )
        networkProvider.update(
            NetworkSnapshot(true, true, ProxyNetworkType.WIFI, 1)
        )
        scope.flush()
        connectionFlow.value = TdApi.UpdateConnectionState(TdApi.ConnectionStateReady())
        scope.flush()

        connectionFlow.value = TdApi.UpdateConnectionState(TdApi.ConnectionStateUpdating())
        scope.advanceAndFlush(1_000L)

        assertEquals(ConnectionStatus.Connected, connectionManager.connectionStateFlow.value)
    }

    @Test
    fun `repeated failure threshold triggers proxy smart switch but single reconnect does not`() =
        runManagerTest {
            authFlow.emit(
                TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady())
            )
            preferences.setAutoBestProxyEnabled(true)
            proxyRemoteSource.proxies = listOf(
                proxy(id = 1, enabled = true),
                proxy(id = 2, enabled = false)
            )
            proxyRemoteSource.pingResults[1] = 150
            proxyRemoteSource.pingResults[2] = 20
            chatRemoteSource.setNetworkTypeResult = false

            connectionManager.retryConnection()
            scope.advanceAndFlush(500L)
            assertEquals(0, proxyRemoteSource.enableProxyCalls)

            connectionManager.retryConnection()
            scope.advanceAndFlush(500L)
            assertEquals(0, proxyRemoteSource.enableProxyCalls)

            connectionManager.retryConnection()
            scope.advanceAndFlush(500L)

            assertTrue(proxyRemoteSource.enableProxyCalls >= 1)
            assertEquals(2, preferences.enabledProxyId.value)
        }

    @Test
    fun `manual retry forces reconnect regardless of debounce`() = runManagerTest {
        authFlow.emit(
            TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady())
        )
        scope.advanceAndFlush(400L)

        val before = chatRemoteSource.setNetworkTypeCalls
        connectionManager.retryConnection()
        connectionManager.retryConnection()
        scope.advanceAndFlush(500L)

        assertEquals(before + 1, chatRemoteSource.setNetworkTypeCalls)
    }

    private fun runManagerTest(block: suspend TestEnvironment.() -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val environment = TestEnvironment(this, dispatcher)
        environment.connectionManager = ConnectionManager(
            chatRemoteSource = environment.chatRemoteSource,
            proxyRemoteSource = environment.proxyRemoteSource,
            updates = environment.updates,
            appPreferences = environment.preferences,
            dispatchers = environment.dispatchers,
            networkSnapshotProvider = environment.networkProvider,
            appForegroundTracker = environment.foregroundTracker,
            scope = backgroundScope
        )
        environment.block()
    }

    private class TestEnvironment(
        val scope: TestScope,
        dispatcher: CoroutineDispatcher
    ) {
        val authFlow = MutableStateFlow(
            TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateWaitTdlibParameters())
        )
        val connectionFlow = MutableStateFlow(
            TdApi.UpdateConnectionState(TdApi.ConnectionStateConnecting())
        )
        val foregroundTracker = FakeAppForegroundTracker()
        val networkProvider = FakeNetworkSnapshotProvider()
        val chatRemoteSource = FakeChatRemoteSource()
        val proxyRemoteSource = FakeProxyRemoteDataSource()
        val preferences = FakeAppPreferencesProvider()
        val dispatchers = TestDispatcherProvider(dispatcher)
        val updates = FakeUpdateDispatcher(authFlow, connectionFlow)
        lateinit var connectionManager: ConnectionManager
    }

    private class TestDispatcherProvider(
        private val dispatcher: CoroutineDispatcher
    ) : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val mainImmediate: CoroutineDispatcher = dispatcher
    }

    private class FakeAppForegroundTracker : AppForegroundTracker {
        private val state = MutableStateFlow(false)
        override val isForeground: StateFlow<Boolean> = state.asStateFlow()
        override fun start() = Unit
        fun setForeground(value: Boolean) {
            state.value = value
        }
    }

    private class FakeNetworkSnapshotProvider : NetworkSnapshotProvider {
        private val state = MutableStateFlow(NetworkSnapshot.Unavailable)
        override val snapshot: StateFlow<NetworkSnapshot> = state.asStateFlow()
        fun update(snapshot: NetworkSnapshot) {
            state.value = snapshot
        }
    }

    private class FakeChatRemoteSource : ChatRemoteSource {
        var setNetworkTypeCalls = 0
        var setNetworkTypeResult = true
        var currentConnectionState: TdApi.ConnectionState = TdApi.ConnectionStateReady()

        override suspend fun setNetworkType(): Boolean {
            setNetworkTypeCalls++
            return setNetworkTypeResult
        }

        override suspend fun getConnectionState(): TdApi.ConnectionState = currentConnectionState

        override suspend fun loadChats(chatList: TdApi.ChatList, limit: Int) = Result.success(Unit)
        override suspend fun getChats(chatList: TdApi.ChatList, limit: Int): TdApi.Chats? = null
        override suspend fun searchChats(query: String, limit: Int): TdApi.Chats? = null
        override suspend fun searchPublicChats(query: String): TdApi.Chats? = null
        override suspend fun searchMessages(
            query: String,
            offset: String,
            limit: Int
        ): TdApi.FoundMessages? = null

        override suspend fun getChat(chatId: Long): TdApi.Chat? = null
        override suspend fun getUser(userId: Long): TdApi.User? = null
        override suspend fun createGroup(
            title: String,
            userIds: List<Long>,
            messageAutoDeleteTime: Int
        ): Long = 0

        override suspend fun createChannel(
            title: String,
            description: String,
            isMegagroup: Boolean,
            messageAutoDeleteTime: Int
        ): Long = 0

        override suspend fun setChatPhoto(chatId: Long, photoPath: String) = Unit
        override suspend fun setChatTitle(chatId: Long, title: String) = Unit
        override suspend fun setChatDescription(chatId: Long, description: String) = Unit
        override suspend fun setChatUsername(chatId: Long, username: String) = Unit
        override suspend fun setChatPermissions(
            chatId: Long,
            permissions: org.monogram.domain.models.ChatPermissionsModel
        ) = Unit

        override suspend fun setChatSlowModeDelay(chatId: Long, slowModeDelay: Int) = Unit
        override suspend fun toggleChatIsForum(chatId: Long, isForum: Boolean) = Unit
        override suspend fun toggleChatIsTranslatable(chatId: Long, isTranslatable: Boolean) = Unit
        override suspend fun getChatLink(chatId: Long): String? = null
        override suspend fun deleteFolder(folderId: Int) = Unit
        override suspend fun muteChat(chatId: Long, muteFor: Int) = Unit
        override suspend fun archiveChat(chatId: Long, archive: Boolean) = Unit
        override suspend fun toggleChatIsPinned(
            chatList: TdApi.ChatList,
            chatId: Long,
            isPinned: Boolean
        ) = Unit

        override suspend fun toggleChatIsMarkedAsUnread(chatId: Long, isMarkedAsUnread: Boolean) =
            Unit

        override suspend fun markChatAsRead(chatId: Long) = Unit
        override suspend fun markForumTopicAsRead(chatId: Long, topicId: Int) = Unit
        override suspend fun deleteChat(chatId: Long) = Unit
        override suspend fun leaveChat(chatId: Long) = Unit
        override suspend fun clearChatHistory(chatId: Long, revoke: Boolean) = Unit
        override suspend fun reportChat(chatId: Long, reason: String, messageIds: List<Long>) = Unit
        override suspend fun getMyUserId(): Long = 0
        override suspend fun getForumTopics(
            chatId: Long,
            query: String,
            offsetDate: Int,
            offsetMessageId: Long,
            offsetForumTopicId: Int,
            limit: Int
        ): TdApi.ForumTopics? = null
    }

    private class FakeProxyRemoteDataSource : ProxyRemoteDataSource {
        var proxies: List<ProxyModel> = emptyList()
        val pingResults = mutableMapOf<Int, Long>()
        var enableProxyCalls = 0
        val onlineOptions = mutableListOf<Boolean>()

        override suspend fun getProxies(): List<ProxyModel> = proxies

        override suspend fun enableProxy(proxyId: Int): Boolean {
            enableProxyCalls++
            proxies = proxies.map { it.copy(isEnabled = it.id == proxyId) }
            return true
        }

        override suspend fun disableProxy() {
            proxies = proxies.map { it.copy(isEnabled = false) }
        }

        override suspend fun pingProxy(server: String, port: Int, type: ProxyTypeModel): Long {
            val proxy = proxies.first { it.server == server && it.port == port }
            return pingResults[proxy.id] ?: Long.MAX_VALUE
        }

        override suspend fun setOption(key: String, value: TdApi.OptionValue) {
            if (key == "online" && value is TdApi.OptionValueBoolean) {
                onlineOptions += value.value
            }
        }

        override suspend fun addProxy(
            server: String,
            port: Int,
            enable: Boolean,
            comment: String?,
            type: ProxyTypeModel
        ): ProxyModel = throw UnsupportedOperationException()

        override suspend fun editProxy(
            proxyId: Int,
            server: String,
            port: Int,
            enable: Boolean,
            comment: String?,
            type: ProxyTypeModel
        ): ProxyModel = throw UnsupportedOperationException()

        override suspend fun removeProxy(proxyId: Int) = Unit
        override suspend fun testProxy(server: String, port: Int, type: ProxyTypeModel): Long = 0
        override suspend fun testProxyAtDc(
            server: String,
            port: Int,
            type: ProxyTypeModel,
            dcId: Int
        ): Long = 0

        override suspend fun testDirectDc(dcId: Int): Long = 0
        override suspend fun getOption(key: String): TdApi.OptionValue? = null
    }

    private class FakeUpdateDispatcher(
        override val authorizationState: Flow<TdApi.UpdateAuthorizationState>,
        override val connectionState: Flow<TdApi.UpdateConnectionState>
    ) : UpdateDispatcher {
        override val all: Flow<TdApi.Update> = MutableSharedFlow()
        override val newMessage: Flow<TdApi.UpdateNewMessage> = MutableSharedFlow()
        override val messageEdited: Flow<TdApi.UpdateMessageEdited> = MutableSharedFlow()
        override val messageContent: Flow<TdApi.UpdateMessageContent> = MutableSharedFlow()
        override val messageSendSucceeded: Flow<TdApi.UpdateMessageSendSucceeded> =
            MutableSharedFlow()
        override val messageSendFailed: Flow<TdApi.UpdateMessageSendFailed> = MutableSharedFlow()
        override val messageDeleted: Flow<TdApi.UpdateDeleteMessages> = MutableSharedFlow()
        override val messagePinned: Flow<TdApi.UpdateChatLastMessage> = MutableSharedFlow()
        override val messageInteractionInfo: Flow<TdApi.UpdateMessageInteractionInfo> =
            MutableSharedFlow()
        override val chatLastMessage: Flow<TdApi.UpdateChatLastMessage> = MutableSharedFlow()
        override val chatPosition: Flow<TdApi.UpdateChatPosition> = MutableSharedFlow()
        override val chatReadInbox: Flow<TdApi.UpdateChatReadInbox> = MutableSharedFlow()
        override val chatReadOutbox: Flow<TdApi.UpdateChatReadOutbox> = MutableSharedFlow()
        override val chatUnreadMentionCount: Flow<TdApi.UpdateChatUnreadMentionCount> =
            MutableSharedFlow()
        override val chatNotificationSettings: Flow<TdApi.UpdateChatNotificationSettings> =
            MutableSharedFlow()
        override val chatTitle: Flow<TdApi.UpdateChatTitle> = MutableSharedFlow()
        override val chatPhoto: Flow<TdApi.UpdateChatPhoto> = MutableSharedFlow()
        override val chatPermissions: Flow<TdApi.UpdateChatPermissions> = MutableSharedFlow()
        override val chatDraftMessage: Flow<TdApi.UpdateChatDraftMessage> = MutableSharedFlow()
        override val chatAction: Flow<TdApi.UpdateChatAction> = MutableSharedFlow()
        override val chatOnlineMemberCount: Flow<TdApi.UpdateChatOnlineMemberCount> =
            MutableSharedFlow()
        override val chatFolders: Flow<TdApi.UpdateChatFolders> = MutableSharedFlow()
        override val userStatus: Flow<TdApi.UpdateUserStatus> = MutableSharedFlow()
        override val user: Flow<TdApi.UpdateUser> = MutableSharedFlow()
        override val userPrivacySettingRules: Flow<TdApi.UpdateUserPrivacySettingRules> =
            MutableSharedFlow()
        override val file: Flow<TdApi.UpdateFile> = MutableSharedFlow()
        override val installedStickerSets: Flow<TdApi.UpdateInstalledStickerSets> =
            MutableSharedFlow()
        override val newChat: Flow<TdApi.UpdateNewChat> = MutableSharedFlow()
        override val attachmentMenuBots: Flow<TdApi.UpdateAttachmentMenuBots> = MutableSharedFlow()
        override val chatsListUpdates: Flow<TdApi.Update> = MutableSharedFlow()
    }

    private class FakeAppPreferencesProvider : AppPreferencesProvider {
        override val autoDownloadMobile = MutableStateFlow(false)
        override val autoDownloadWifi = MutableStateFlow(false)
        override val autoDownloadRoaming = MutableStateFlow(false)
        override val autoDownloadFiles = MutableStateFlow(false)
        override val autoDownloadStickers = MutableStateFlow(false)
        override val autoDownloadVideoNotes = MutableStateFlow(false)
        override val isArchivePinned = MutableStateFlow(false)
        override val isArchiveAlwaysVisible = MutableStateFlow(false)
        override val showLinkPreviews = MutableStateFlow(false)
        override val isChatAnimationsEnabled = MutableStateFlow(false)
        override val chatListMessageLines = MutableStateFlow(2)
        override val showChatListPhotos = MutableStateFlow(true)
        override val privateChatsNotifications = MutableStateFlow(true)
        override val groupsNotifications = MutableStateFlow(true)
        override val channelsNotifications = MutableStateFlow(true)
        override val inAppSounds = MutableStateFlow(true)
        override val inAppVibrate = MutableStateFlow(true)
        override val inAppPreview = MutableStateFlow(true)
        override val contactJoinedNotifications = MutableStateFlow(true)
        override val pinnedMessagesNotifications = MutableStateFlow(true)
        override val backgroundServiceEnabled = MutableStateFlow(false)
        override val isPowerSavingMode = MutableStateFlow(false)
        override val isWakeLockEnabled = MutableStateFlow(false)
        override val hideForegroundNotification = MutableStateFlow(false)
        override val batteryOptimizationEnabled = MutableStateFlow(false)
        override val notificationVibrationPattern = MutableStateFlow("default")
        override val notificationPriority = MutableStateFlow(1)
        override val repeatNotifications = MutableStateFlow(0)
        override val showSenderOnly = MutableStateFlow(false)
        override val pushProvider = MutableStateFlow(PushProvider.GMS_LESS)
        override val enabledProxyId = MutableStateFlow<Int?>(null)
        override val isAutoBestProxyEnabled = MutableStateFlow(false)
        override val proxySmartSwitchMode = MutableStateFlow(ProxySmartSwitchMode.BEST_PING)
        override val proxyAutoCheckIntervalMinutes =
            MutableStateFlow(DEFAULT_SMART_SWITCH_CHECK_INTERVAL_MINUTES)
        override val preferIpv6 = MutableStateFlow(false)
        override val proxySortMode = MutableStateFlow(ProxySortMode.ACTIVE_FIRST)
        override val proxyUnavailableFallback = MutableStateFlow(ProxyUnavailableFallback.DIRECT)
        override val hideOfflineProxies = MutableStateFlow(false)
        override val favoriteProxyId = MutableStateFlow<Int?>(null)
        override val proxyNetworkRules = MutableStateFlow(
            ProxyNetworkType.entries.associateWith {
                ProxyNetworkRule(ProxyNetworkMode.DIRECT)
            }
        )
        override val userProxyBackups = MutableStateFlow(emptySet<String>())
        override val isBiometricEnabled = MutableStateFlow(false)
        override val passcode = MutableStateFlow<String?>(null)
        override val isPermissionRequested = MutableStateFlow(false)
        override val isSupportViewed = MutableStateFlow(false)
        override val inAppBrowserEnabled = MutableStateFlow(true)

        override fun setEnabledProxyId(proxyId: Int?) {
            enabledProxyId.value = proxyId
        }

        override fun setAutoBestProxyEnabled(enabled: Boolean) {
            isAutoBestProxyEnabled.value = enabled
        }

        override fun setProxyNetworkMode(networkType: ProxyNetworkType, mode: ProxyNetworkMode) {
            proxyNetworkRules.value = proxyNetworkRules.value.toMutableMap().apply {
                this[networkType] = (this[networkType] ?: ProxyNetworkRule(mode)).copy(mode = mode)
            }
        }

        override fun setSpecificProxyIdForNetwork(networkType: ProxyNetworkType, proxyId: Int?) {
            proxyNetworkRules.value = proxyNetworkRules.value.toMutableMap().apply {
                this[networkType] =
                    (this[networkType] ?: ProxyNetworkRule(ProxyNetworkMode.SPECIFIC_PROXY))
                        .copy(specificProxyId = proxyId)
            }
        }

        override fun setLastUsedProxyIdForNetwork(networkType: ProxyNetworkType, proxyId: Int?) {
            proxyNetworkRules.value = proxyNetworkRules.value.toMutableMap().apply {
                this[networkType] =
                    (this[networkType] ?: ProxyNetworkRule(ProxyNetworkMode.LAST_USED))
                        .copy(lastUsedProxyId = proxyId)
            }
        }

        override fun setProxySmartSwitchMode(mode: ProxySmartSwitchMode) {
            proxySmartSwitchMode.value = mode
        }

        override fun setProxyAutoCheckIntervalMinutes(minutes: Int) {
            proxyAutoCheckIntervalMinutes.value = minutes
        }

        override fun setPreferIpv6(enabled: Boolean) {
            preferIpv6.value = enabled
        }

        override fun setProxySortMode(mode: ProxySortMode) {
            proxySortMode.value = mode
        }

        override fun setProxyUnavailableFallback(fallback: ProxyUnavailableFallback) {
            proxyUnavailableFallback.value = fallback
        }

        override fun setHideOfflineProxies(enabled: Boolean) {
            hideOfflineProxies.value = enabled
        }

        override fun setFavoriteProxyId(proxyId: Int?) {
            favoriteProxyId.value = proxyId
        }

        override fun setUserProxyBackups(backups: Set<String>) {
            userProxyBackups.value = backups
        }

        override fun setInAppBrowserEnabled(enabled: Boolean) {
            inAppBrowserEnabled.value = enabled
        }

        override fun setAutoDownloadMobile(enabled: Boolean) {
            autoDownloadMobile.value = enabled
        }

        override fun setAutoDownloadWifi(enabled: Boolean) {
            autoDownloadWifi.value = enabled
        }

        override fun setAutoDownloadRoaming(enabled: Boolean) {
            autoDownloadRoaming.value = enabled
        }

        override fun setAutoDownloadFiles(enabled: Boolean) {
            autoDownloadFiles.value = enabled
        }

        override fun setAutoDownloadStickers(enabled: Boolean) {
            autoDownloadStickers.value = enabled
        }

        override fun setAutoDownloadVideoNotes(enabled: Boolean) {
            autoDownloadVideoNotes.value = enabled
        }

        override fun setArchivePinned(pinned: Boolean) {
            isArchivePinned.value = pinned
        }

        override fun setArchiveAlwaysVisible(enabled: Boolean) {
            isArchiveAlwaysVisible.value = enabled
        }

        override fun setShowLinkPreviews(enabled: Boolean) {
            showLinkPreviews.value = enabled
        }

        override fun setChatAnimationsEnabled(enabled: Boolean) {
            isChatAnimationsEnabled.value = enabled
        }

        override fun setChatListMessageLines(lines: Int) {
            chatListMessageLines.value = lines
        }

        override fun setShowChatListPhotos(enabled: Boolean) {
            showChatListPhotos.value = enabled
        }

        override fun setPrivateChatsNotifications(enabled: Boolean) {
            privateChatsNotifications.value = enabled
        }

        override fun setGroupsNotifications(enabled: Boolean) {
            groupsNotifications.value = enabled
        }

        override fun setChannelsNotifications(enabled: Boolean) {
            channelsNotifications.value = enabled
        }

        override fun setInAppSounds(enabled: Boolean) {
            inAppSounds.value = enabled
        }

        override fun setInAppVibrate(enabled: Boolean) {
            inAppVibrate.value = enabled
        }

        override fun setInAppPreview(enabled: Boolean) {
            inAppPreview.value = enabled
        }

        override fun setContactJoinedNotifications(enabled: Boolean) {
            contactJoinedNotifications.value = enabled
        }

        override fun setPinnedMessagesNotifications(enabled: Boolean) {
            pinnedMessagesNotifications.value = enabled
        }

        override fun setBackgroundServiceEnabled(enabled: Boolean) {
            backgroundServiceEnabled.value = enabled
        }

        override fun setPowerSavingMode(enabled: Boolean) {
            isPowerSavingMode.value = enabled
        }

        override fun setWakeLockEnabled(enabled: Boolean) {
            isWakeLockEnabled.value = enabled
        }

        override fun setHideForegroundNotification(enabled: Boolean) {
            hideForegroundNotification.value = enabled
        }

        override fun setBatteryOptimizationEnabled(enabled: Boolean) {
            batteryOptimizationEnabled.value = enabled
        }

        override fun setNotificationVibrationPattern(pattern: String) {
            notificationVibrationPattern.value = pattern
        }

        override fun setNotificationPriority(priority: Int) {
            notificationPriority.value = priority
        }

        override fun setRepeatNotifications(minutes: Int) {
            repeatNotifications.value = minutes
        }

        override fun setShowSenderOnly(enabled: Boolean) {
            showSenderOnly.value = enabled
        }

        override fun setPushProvider(provider: PushProvider) {
            pushProvider.value = provider
        }

        override fun setBiometricEnabled(enabled: Boolean) {
            isBiometricEnabled.value = enabled
        }

        override fun setPasscode(passcode: String?) {
            this.passcode.value = passcode
        }

        override fun setPermissionRequested(requested: Boolean) {
            isPermissionRequested.value = requested
        }

        override fun clearPreferences() = Unit
        override fun clearSecurePreferences() = Unit
        override fun setSupportViewed(viewed: Boolean) {
            isSupportViewed.value = viewed
        }
    }

    companion object {
        private fun proxy(id: Int, enabled: Boolean): ProxyModel = ProxyModel(
            id = id,
            server = "proxy$id.example.org",
            port = 443 + id,
            lastUsedDate = 0,
            comment = null,
            isEnabled = enabled,
            type = ProxyTypeModel.Socks5("", ""),
            ping = 0L
        )
    }

    private fun TestScope.flush() {
        runCurrent()
    }

    private fun TestScope.advanceAndFlush(timeMs: Long) {
        advanceTimeBy(timeMs)
        runCurrent()
    }
}
