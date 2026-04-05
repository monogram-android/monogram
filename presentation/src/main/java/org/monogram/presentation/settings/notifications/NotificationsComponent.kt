package org.monogram.presentation.settings.notifications

import android.os.Parcelable
import com.arkivanov.decompose.router.stack.*
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import org.monogram.domain.managers.DistrManager
import org.monogram.domain.models.ChatModel
import org.monogram.domain.repository.NotificationSettingsRepository
import org.monogram.domain.repository.NotificationSettingsRepository.TdNotificationScope
import org.monogram.domain.repository.PushProvider
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

interface NotificationsComponent {
    val state: Value<State>
    val childStack: Value<ChildStack<*, Child>>

    fun onBackClicked()
    fun onPrivateChatsToggled(enabled: Boolean)
    fun onGroupsToggled(enabled: Boolean)
    fun onChannelsToggled(enabled: Boolean)
    fun onInAppSoundsToggled(enabled: Boolean)
    fun onInAppVibrateToggled(enabled: Boolean)
    fun onInAppPreviewToggled(enabled: Boolean)
    fun onContactJoinedToggled(enabled: Boolean)
    fun onPinnedMessagesToggled(enabled: Boolean)
    fun onBackgroundServiceToggled(enabled: Boolean)
    fun onHideForegroundNotificationToggled(enabled: Boolean)
    fun onVibrationPatternChanged(pattern: String)
    fun onPriorityChanged(priority: Int)
    fun onRepeatNotificationsChanged(minutes: Int)
    fun onShowSenderOnlyToggled(enabled: Boolean)
    fun onPushProviderChanged(provider: PushProvider)
    fun onResetNotificationsClicked()
    fun onExceptionClicked(scope: TdNotificationScope)
    fun onChatExceptionToggled(chatId: Long, enabled: Boolean)
    fun onChatExceptionReset(chatId: Long)

    data class State(
        val privateChatsEnabled: Boolean = true,
        val groupsEnabled: Boolean = true,
        val channelsEnabled: Boolean = true,
        val inAppSounds: Boolean = true,
        val inAppVibrate: Boolean = true,
        val inAppPreview: Boolean = true,
        val contactJoined: Boolean = true,
        val pinnedMessages: Boolean = true,
        val backgroundServiceEnabled: Boolean = true,
        val hideForegroundNotification: Boolean = false,
        val vibrationPattern: String = "default",
        val priority: Int = 1,
        val repeatNotifications: Int = 0,
        val showSenderOnly: Boolean = false,
        val pushProvider: PushProvider = PushProvider.FCM,
        val isGmsAvailable: Boolean = false,
        val privateExceptions: List<ChatModel>? = null,
        val groupExceptions: List<ChatModel>? = null,
        val channelExceptions: List<ChatModel>? = null
    )

    sealed class Child {
        object Main : Child()
        class Exceptions(val scope: TdNotificationScope) : Child()
    }
}

class DefaultNotificationsComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit
) : NotificationsComponent, AppComponentContext by context {

    private val appPreferences: AppPreferences = container.preferences.appPreferences
    private val notificationSettingsRepository: NotificationSettingsRepository =
        container.repositories.notificationSettingsRepository
    private val distrManager: DistrManager = container.utils.distrManager()

    private val scope = componentScope
    private val navigation = StackNavigation<Config>()

    override val childStack: Value<ChildStack<*, NotificationsComponent.Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = Config.Main,
        handleBackButton = true,
        childFactory = ::createChild
    )

    private fun createChild(config: Config, context: AppComponentContext): NotificationsComponent.Child =
        when (config) {
            is Config.Main -> NotificationsComponent.Child.Main
            is Config.Exceptions -> NotificationsComponent.Child.Exceptions(config.scope)
        }

    private val _state = MutableValue(NotificationsComponent.State())
    override val state: Value<NotificationsComponent.State> = _state

    init {
        appPreferences.privateChatsNotifications.onEach { value ->
            _state.update { it.copy(privateChatsEnabled = value) }
        }.launchIn(scope)

        appPreferences.groupsNotifications.onEach { value ->
            _state.update { it.copy(groupsEnabled = value) }
        }.launchIn(scope)

        appPreferences.channelsNotifications.onEach { value ->
            _state.update { it.copy(channelsEnabled = value) }
        }.launchIn(scope)

        appPreferences.inAppSounds.onEach { value ->
            _state.update { it.copy(inAppSounds = value) }
        }.launchIn(scope)

        appPreferences.inAppVibrate.onEach { value ->
            _state.update { it.copy(inAppVibrate = value) }
        }.launchIn(scope)

        appPreferences.inAppPreview.onEach { value ->
            _state.update { it.copy(inAppPreview = value) }
        }.launchIn(scope)

        appPreferences.contactJoinedNotifications.onEach { value ->
            _state.update { it.copy(contactJoined = value) }
        }.launchIn(scope)

        appPreferences.pinnedMessagesNotifications.onEach { value ->
            _state.update { it.copy(pinnedMessages = value) }
        }.launchIn(scope)

        appPreferences.backgroundServiceEnabled.onEach { value ->
            _state.update { it.copy(backgroundServiceEnabled = value) }
        }.launchIn(scope)

        appPreferences.hideForegroundNotification.onEach { value ->
            _state.update { it.copy(hideForegroundNotification = value) }
        }.launchIn(scope)

        appPreferences.notificationVibrationPattern.onEach { value ->
            _state.update { it.copy(vibrationPattern = value) }
        }.launchIn(scope)

        appPreferences.notificationPriority.onEach { value ->
            _state.update { it.copy(priority = value) }
        }.launchIn(scope)

        appPreferences.repeatNotifications.onEach { value ->
            _state.update { it.copy(repeatNotifications = value) }
        }.launchIn(scope)

        appPreferences.showSenderOnly.onEach { value ->
            _state.update { it.copy(showSenderOnly = value) }
        }.launchIn(scope)

        appPreferences.pushProvider.onEach { value ->
            _state.update { it.copy(pushProvider = value) }
        }.launchIn(scope)

        _state.update { it.copy(isGmsAvailable = distrManager.isGmsAvailable()) }

        syncSettings()
    }

    private fun syncSettings() {
        scope.launch {
            val privateEnabled =
                notificationSettingsRepository.getNotificationSettings(TdNotificationScope.PRIVATE_CHATS)
            appPreferences.setPrivateChatsNotifications(privateEnabled)

            val groupsEnabled = notificationSettingsRepository.getNotificationSettings(TdNotificationScope.GROUPS)
            appPreferences.setGroupsNotifications(groupsEnabled)

            val channelsEnabled = notificationSettingsRepository.getNotificationSettings(TdNotificationScope.CHANNELS)
            appPreferences.setChannelsNotifications(channelsEnabled)
        }
    }

    private fun loadExceptions(scope: TdNotificationScope) {
        this.scope.launch {
            _state.update {
                when (scope) {
                    TdNotificationScope.PRIVATE_CHATS -> it.copy(privateExceptions = null)
                    TdNotificationScope.GROUPS -> it.copy(groupExceptions = null)
                    TdNotificationScope.CHANNELS -> it.copy(channelExceptions = null)
                }
            }

            val exceptions = notificationSettingsRepository.getExceptions(scope)

            _state.update {
                when (scope) {
                    TdNotificationScope.PRIVATE_CHATS -> it.copy(privateExceptions = exceptions)
                    TdNotificationScope.GROUPS -> it.copy(groupExceptions = exceptions)
                    TdNotificationScope.CHANNELS -> it.copy(channelExceptions = exceptions)
                }
            }
        }
    }

    override fun onBackClicked() {
        if (childStack.value.items.size > 1) {
            navigation.pop()
        } else {
            onBack()
        }
    }

    override fun onPrivateChatsToggled(enabled: Boolean) {
        appPreferences.setPrivateChatsNotifications(enabled)
        scope.launch {
            notificationSettingsRepository.setNotificationSettings(TdNotificationScope.PRIVATE_CHATS, enabled)
        }
    }

    override fun onGroupsToggled(enabled: Boolean) {
        appPreferences.setGroupsNotifications(enabled)
        scope.launch {
            notificationSettingsRepository.setNotificationSettings(TdNotificationScope.GROUPS, enabled)
        }
    }

    override fun onChannelsToggled(enabled: Boolean) {
        appPreferences.setChannelsNotifications(enabled)
        scope.launch {
            notificationSettingsRepository.setNotificationSettings(TdNotificationScope.CHANNELS, enabled)
        }
    }

    override fun onInAppSoundsToggled(enabled: Boolean) {
        appPreferences.setInAppSounds(enabled)
    }

    override fun onInAppVibrateToggled(enabled: Boolean) {
        appPreferences.setInAppVibrate(enabled)
    }

    override fun onInAppPreviewToggled(enabled: Boolean) {
        appPreferences.setInAppPreview(enabled)
    }

    override fun onContactJoinedToggled(enabled: Boolean) {
        appPreferences.setContactJoinedNotifications(enabled)
    }

    override fun onPinnedMessagesToggled(enabled: Boolean) {
        appPreferences.setPinnedMessagesNotifications(enabled)
    }

    override fun onBackgroundServiceToggled(enabled: Boolean) {
        appPreferences.setBackgroundServiceEnabled(enabled)
    }

    override fun onHideForegroundNotificationToggled(enabled: Boolean) {
        appPreferences.setHideForegroundNotification(enabled)
    }

    override fun onVibrationPatternChanged(pattern: String) {
        appPreferences.setNotificationVibrationPattern(pattern)
    }

    override fun onPriorityChanged(priority: Int) {
        appPreferences.setNotificationPriority(priority)
    }

    override fun onRepeatNotificationsChanged(minutes: Int) {
        appPreferences.setRepeatNotifications(minutes)
    }

    override fun onShowSenderOnlyToggled(enabled: Boolean) {
        appPreferences.setShowSenderOnly(enabled)
    }

    override fun onPushProviderChanged(provider: PushProvider) {
        appPreferences.setPushProvider(provider)
    }

    override fun onResetNotificationsClicked() {
        onPrivateChatsToggled(true)
        onGroupsToggled(true)
        onChannelsToggled(true)
        onInAppSoundsToggled(true)
        onInAppVibrateToggled(true)
        onInAppPreviewToggled(true)
        onContactJoinedToggled(true)
        onPinnedMessagesToggled(true)
        onBackgroundServiceToggled(true)
        onHideForegroundNotificationToggled(false)
        onVibrationPatternChanged("default")
        onPriorityChanged(1)
        onRepeatNotificationsChanged(0)
        onShowSenderOnlyToggled(false)
        onPushProviderChanged(if (_state.value.isGmsAvailable) PushProvider.FCM else PushProvider.GMS_LESS)
    }

    override fun onExceptionClicked(scope: TdNotificationScope) {
        loadExceptions(scope)
        navigation.push(Config.Exceptions(scope))
    }

    override fun onChatExceptionToggled(chatId: Long, enabled: Boolean) {
        scope.launch {
            notificationSettingsRepository.setChatNotificationSettings(chatId, enabled)
            val currentChild = childStack.value.active.instance
            if (currentChild is NotificationsComponent.Child.Exceptions) {
                loadExceptions(currentChild.scope)
            }
        }
    }

    override fun onChatExceptionReset(chatId: Long) {
        scope.launch {
            notificationSettingsRepository.resetChatNotificationSettings(chatId)
            val currentChild = childStack.value.active.instance
            if (currentChild is NotificationsComponent.Child.Exceptions) {
                loadExceptions(currentChild.scope)
            }
        }
    }

    @Serializable
    sealed class Config : Parcelable {
        @Parcelize
        @Serializable
        object Main : Config()

        @Parcelize
        @Serializable
        data class Exceptions(val scope: TdNotificationScope) : Config()
    }
}
