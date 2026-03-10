package org.monogram.domain.repository

import kotlinx.coroutines.flow.StateFlow

enum class PushProvider {
    FCM, GMS_LESS
}

interface AppPreferencesProvider {
    val autoDownloadMobile: StateFlow<Boolean>
    val autoDownloadWifi: StateFlow<Boolean>
    val autoDownloadRoaming: StateFlow<Boolean>
    val autoDownloadFiles: StateFlow<Boolean>
    val autoDownloadStickers: StateFlow<Boolean>
    val autoDownloadVideoNotes: StateFlow<Boolean>
    val isArchivePinned: StateFlow<Boolean>
    val isArchiveAlwaysVisible: StateFlow<Boolean>
    val showLinkPreviews: StateFlow<Boolean>
    val isChatAnimationsEnabled: StateFlow<Boolean>
    val chatListMessageLines: StateFlow<Int>
    val showChatListPhotos: StateFlow<Boolean>

    val privateChatsNotifications: StateFlow<Boolean>
    val groupsNotifications: StateFlow<Boolean>
    val channelsNotifications: StateFlow<Boolean>
    val inAppSounds: StateFlow<Boolean>
    val inAppVibrate: StateFlow<Boolean>
    val inAppPreview: StateFlow<Boolean>
    val contactJoinedNotifications: StateFlow<Boolean>
    val pinnedMessagesNotifications: StateFlow<Boolean>
    val backgroundServiceEnabled: StateFlow<Boolean>
    val isPowerSavingMode: StateFlow<Boolean>
    val isWakeLockEnabled: StateFlow<Boolean>
    val hideForegroundNotification: StateFlow<Boolean>
    val batteryOptimizationEnabled: StateFlow<Boolean>

    val notificationVibrationPattern: StateFlow<String> // "default", "short", "long", "disabled"
    val notificationPriority: StateFlow<Int> // 0: Low, 1: Default, 2: High
    val repeatNotifications: StateFlow<Int> // 0: Never, 5, 10, 30, 60 minutes
    val showSenderOnly: StateFlow<Boolean>
    val pushProvider: StateFlow<PushProvider>

    val enabledProxyId: StateFlow<Int?>
    val isAutoBestProxyEnabled: StateFlow<Boolean>
    val isTelegaProxyEnabled: StateFlow<Boolean>
    val telegaProxyUrls: StateFlow<Set<String>>
    val preferIpv6: StateFlow<Boolean>

    val isBiometricEnabled: StateFlow<Boolean>
    val passcode: StateFlow<String?>

    val isPermissionRequested: StateFlow<Boolean>
    val isSupportViewed: StateFlow<Boolean>

    fun setAutoDownloadMobile(enabled: Boolean)
    fun setAutoDownloadWifi(enabled: Boolean)
    fun setAutoDownloadRoaming(enabled: Boolean)
    fun setAutoDownloadFiles(enabled: Boolean)
    fun setAutoDownloadStickers(enabled: Boolean)
    fun setAutoDownloadVideoNotes(enabled: Boolean)
    fun setArchivePinned(pinned: Boolean)
    fun setArchiveAlwaysVisible(enabled: Boolean)
    fun setShowLinkPreviews(enabled: Boolean)
    fun setChatAnimationsEnabled(enabled: Boolean)
    fun setChatListMessageLines(lines: Int)
    fun setShowChatListPhotos(enabled: Boolean)

    fun setPrivateChatsNotifications(enabled: Boolean)
    fun setGroupsNotifications(enabled: Boolean)
    fun setChannelsNotifications(enabled: Boolean)
    fun setInAppSounds(enabled: Boolean)
    fun setInAppVibrate(enabled: Boolean)
    fun setInAppPreview(enabled: Boolean)
    fun setContactJoinedNotifications(enabled: Boolean)
    fun setPinnedMessagesNotifications(enabled: Boolean)
    fun setBackgroundServiceEnabled(enabled: Boolean)
    fun setPowerSavingMode(enabled: Boolean)
    fun setWakeLockEnabled(enabled: Boolean)
    fun setHideForegroundNotification(enabled: Boolean)
    fun setBatteryOptimizationEnabled(enabled: Boolean)

    fun setNotificationVibrationPattern(pattern: String)
    fun setNotificationPriority(priority: Int)
    fun setRepeatNotifications(minutes: Int)
    fun setShowSenderOnly(enabled: Boolean)
    fun setPushProvider(provider: PushProvider)

    fun setEnabledProxyId(proxyId: Int?)
    fun setAutoBestProxyEnabled(enabled: Boolean)
    fun setTelegaProxyEnabled(enabled: Boolean)
    fun setTelegaProxyUrls(urls: Set<String>)
    fun setPreferIpv6(enabled: Boolean)

    fun setBiometricEnabled(enabled: Boolean)
    fun setPasscode(passcode: String?)

    fun setPermissionRequested(requested: Boolean)

    fun clearPreferences()
    fun clearSecurePreferences()
    fun setSupportViewed(viewed: Boolean)
}
