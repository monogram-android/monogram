package org.monogram.domain.repository

import kotlinx.coroutines.flow.StateFlow

enum class PushProvider {
    FCM, UNIFIED_PUSH, GMS_LESS
}

enum class ProxyNetworkType {
    WIFI,
    MOBILE,
    VPN,
    OTHER
}

enum class ProxyNetworkMode {
    DIRECT,
    BEST_PROXY,
    LAST_USED,
    SPECIFIC_PROXY
}

enum class ProxySortMode {
    ACTIVE_FIRST,
    LOWEST_PING,
    SERVER_NAME,
    PROXY_TYPE,
    STATUS
}

enum class ProxyUnavailableFallback {
    BEST_PROXY,
    DIRECT,
    KEEP_CURRENT
}

data class ProxyNetworkRule(
    val mode: ProxyNetworkMode,
    val specificProxyId: Int? = null,
    val lastUsedProxyId: Int? = null
)

fun defaultProxyNetworkMode(networkType: ProxyNetworkType): ProxyNetworkMode {
    return ProxyNetworkMode.BEST_PROXY
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
    val preferIpv6: StateFlow<Boolean>
    val proxySortMode: StateFlow<ProxySortMode>
    val proxyUnavailableFallback: StateFlow<ProxyUnavailableFallback>
    val hideOfflineProxies: StateFlow<Boolean>
    val favoriteProxyId: StateFlow<Int?>
    val proxyNetworkRules: StateFlow<Map<ProxyNetworkType, ProxyNetworkRule>>
    val userProxyBackups: StateFlow<Set<String>>

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
    fun setPreferIpv6(enabled: Boolean)
    fun setProxySortMode(mode: ProxySortMode)
    fun setProxyUnavailableFallback(fallback: ProxyUnavailableFallback)
    fun setHideOfflineProxies(enabled: Boolean)
    fun setFavoriteProxyId(proxyId: Int?)
    fun setProxyNetworkMode(networkType: ProxyNetworkType, mode: ProxyNetworkMode)
    fun setSpecificProxyIdForNetwork(networkType: ProxyNetworkType, proxyId: Int?)
    fun setLastUsedProxyIdForNetwork(networkType: ProxyNetworkType, proxyId: Int?)
    fun setUserProxyBackups(backups: Set<String>)

    fun setBiometricEnabled(enabled: Boolean)
    fun setPasscode(passcode: String?)

    fun setPermissionRequested(requested: Boolean)

    fun clearPreferences()
    fun clearSecurePreferences()
    fun setSupportViewed(viewed: Boolean)
}
