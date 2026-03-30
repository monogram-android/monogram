package org.monogram.presentation.core.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.PushProvider

enum class NightMode {
    SYSTEM, LIGHT, DARK, SCHEDULED, BRIGHTNESS
}

enum class EmojiStyle {
    SYSTEM, APPLE, TWITTER, WINDOWS, CATMOJI, NOTO
}

class AppPreferences(
    private val context: Context,
    private val externalScope: CoroutineScope
) : AppPreferencesProvider {
    private val prefs: SharedPreferences = context.getSharedPreferences("monogram_prefs", Context.MODE_PRIVATE)

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "monogram_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _fontSize = MutableStateFlow(prefs.getFloat(KEY_FONT_SIZE, 16f))
    val fontSize: StateFlow<Float> = _fontSize

    private val _letterSpacing = MutableStateFlow(prefs.getFloat(KEY_LETTER_SPACING, 0f))
    val letterSpacing: StateFlow<Float> = _letterSpacing
    
    private val _bubbleRadius = MutableStateFlow(prefs.getFloat(KEY_BUBBLE_RADIUS, 18f))
    val bubbleRadius: StateFlow<Float> = _bubbleRadius

    private val _wallpaper = MutableStateFlow(prefs.getString(KEY_WALLPAPER, null))
    val wallpaper: StateFlow<String?> = _wallpaper

    private val _isWallpaperBlurred = MutableStateFlow(prefs.getBoolean(KEY_WALLPAPER_BLURRED, false))
    val isWallpaperBlurred: StateFlow<Boolean> = _isWallpaperBlurred

    private val _wallpaperBlurIntensity = MutableStateFlow(prefs.getInt(KEY_WALLPAPER_BLUR_INTENSITY, 20))
    val wallpaperBlurIntensity: StateFlow<Int> = _wallpaperBlurIntensity

    private val _isWallpaperMoving = MutableStateFlow(prefs.getBoolean(KEY_WALLPAPER_MOVING, false))
    val isWallpaperMoving: StateFlow<Boolean> = _isWallpaperMoving

    private val _wallpaperDimming = MutableStateFlow(prefs.getInt(KEY_WALLPAPER_DIMMING, 0))
    val wallpaperDimming: StateFlow<Int> = _wallpaperDimming

    private val _isWallpaperGrayscale = MutableStateFlow(prefs.getBoolean(KEY_WALLPAPER_GRAYSCALE, false))
    val isWallpaperGrayscale: StateFlow<Boolean> = _isWallpaperGrayscale

    private val _isPlayerGesturesEnabled = MutableStateFlow(prefs.getBoolean(KEY_PLAYER_GESTURES_ENABLED, true))
    val isPlayerGesturesEnabled: StateFlow<Boolean> = _isPlayerGesturesEnabled

    private val _isPlayerDoubleTapSeekEnabled = MutableStateFlow(prefs.getBoolean(KEY_PLAYER_DOUBLE_TAP_SEEK, true))
    val isPlayerDoubleTapSeekEnabled: StateFlow<Boolean> = _isPlayerDoubleTapSeekEnabled

    private val _playerSeekDuration = MutableStateFlow(prefs.getInt(KEY_PLAYER_SEEK_DURATION, 10))
    val playerSeekDuration: StateFlow<Int> = _playerSeekDuration

    private val _isPlayerZoomEnabled = MutableStateFlow(prefs.getBoolean(KEY_PLAYER_ZOOM_ENABLED, true))
    val isPlayerZoomEnabled: StateFlow<Boolean> = _isPlayerZoomEnabled

    private val _nightMode = MutableStateFlow(NightMode.entries[prefs.getInt(KEY_NIGHT_MODE, NightMode.SYSTEM.ordinal)])
    val nightMode: StateFlow<NightMode> = _nightMode

    private val _isDynamicColorsEnabled = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_COLORS, true))
    val isDynamicColorsEnabled: StateFlow<Boolean> = _isDynamicColorsEnabled

    private val _isAmoledThemeEnabled = MutableStateFlow(prefs.getBoolean(KEY_AMOLED_THEME, false))
    val isAmoledThemeEnabled: StateFlow<Boolean> = _isAmoledThemeEnabled

    private val _isCustomThemeEnabled = MutableStateFlow(prefs.getBoolean(KEY_CUSTOM_THEME_ENABLED, false))
    val isCustomThemeEnabled: StateFlow<Boolean> = _isCustomThemeEnabled

    private val _themePrimaryColor = MutableStateFlow(prefs.getInt(KEY_THEME_PRIMARY_COLOR, 0xFF3390EC.toInt()))
    val themePrimaryColor: StateFlow<Int> = _themePrimaryColor

    private val _themeSecondaryColor = MutableStateFlow(prefs.getInt(KEY_THEME_SECONDARY_COLOR, 0xFF4C7599.toInt()))
    val themeSecondaryColor: StateFlow<Int> = _themeSecondaryColor

    private val _themeTertiaryColor = MutableStateFlow(prefs.getInt(KEY_THEME_TERTIARY_COLOR, 0xFF00ACC1.toInt()))
    val themeTertiaryColor: StateFlow<Int> = _themeTertiaryColor

    private val _themeBackgroundColor =
        MutableStateFlow(prefs.getInt(KEY_THEME_BACKGROUND_COLOR, 0xFFFFFBFE.toInt()))
    val themeBackgroundColor: StateFlow<Int> = _themeBackgroundColor

    private val _themeSurfaceColor = MutableStateFlow(prefs.getInt(KEY_THEME_SURFACE_COLOR, 0xFFFFFBFE.toInt()))
    val themeSurfaceColor: StateFlow<Int> = _themeSurfaceColor

    private val _themePrimaryContainerColor =
        MutableStateFlow(prefs.getInt(KEY_THEME_PRIMARY_CONTAINER_COLOR, 0xFFD4E3FF.toInt()))
    val themePrimaryContainerColor: StateFlow<Int> = _themePrimaryContainerColor

    private val _themeSecondaryContainerColor =
        MutableStateFlow(prefs.getInt(KEY_THEME_SECONDARY_CONTAINER_COLOR, 0xFFD0E4F7.toInt()))
    val themeSecondaryContainerColor: StateFlow<Int> = _themeSecondaryContainerColor

    private val _themeTertiaryContainerColor =
        MutableStateFlow(prefs.getInt(KEY_THEME_TERTIARY_CONTAINER_COLOR, 0xFFC4EEF4.toInt()))
    val themeTertiaryContainerColor: StateFlow<Int> = _themeTertiaryContainerColor

    private val _themeSurfaceVariantColor =
        MutableStateFlow(prefs.getInt(KEY_THEME_SURFACE_VARIANT_COLOR, 0xFFE1E2EC.toInt()))
    val themeSurfaceVariantColor: StateFlow<Int> = _themeSurfaceVariantColor

    private val _themeOutlineColor =
        MutableStateFlow(prefs.getInt(KEY_THEME_OUTLINE_COLOR, 0xFF757680.toInt()))
    val themeOutlineColor: StateFlow<Int> = _themeOutlineColor

    private val _themeDarkPrimaryColor =
        MutableStateFlow(prefs.getInt(KEY_THEME_DARK_PRIMARY_COLOR, 0xFF64B5F6.toInt()))
    val themeDarkPrimaryColor: StateFlow<Int> = _themeDarkPrimaryColor

    private val _themeDarkSecondaryColor =
        MutableStateFlow(prefs.getInt(KEY_THEME_DARK_SECONDARY_COLOR, 0xFF81A9CA.toInt()))
    val themeDarkSecondaryColor: StateFlow<Int> = _themeDarkSecondaryColor

    private val _themeDarkTertiaryColor =
        MutableStateFlow(prefs.getInt(KEY_THEME_DARK_TERTIARY_COLOR, 0xFF4DD0E1.toInt()))
    val themeDarkTertiaryColor: StateFlow<Int> = _themeDarkTertiaryColor

    private val _themeDarkBackgroundColor =
        MutableStateFlow(prefs.getInt(KEY_THEME_DARK_BACKGROUND_COLOR, 0xFF121212.toInt()))
    val themeDarkBackgroundColor: StateFlow<Int> = _themeDarkBackgroundColor

    private val _themeDarkSurfaceColor =
        MutableStateFlow(prefs.getInt(KEY_THEME_DARK_SURFACE_COLOR, 0xFF121212.toInt()))
    val themeDarkSurfaceColor: StateFlow<Int> = _themeDarkSurfaceColor

    private val _themeDarkPrimaryContainerColor =
        MutableStateFlow(prefs.getInt(KEY_THEME_DARK_PRIMARY_CONTAINER_COLOR, 0xFF224A77.toInt()))
    val themeDarkPrimaryContainerColor: StateFlow<Int> = _themeDarkPrimaryContainerColor

    private val _themeDarkSecondaryContainerColor =
        MutableStateFlow(prefs.getInt(KEY_THEME_DARK_SECONDARY_CONTAINER_COLOR, 0xFF334F65.toInt()))
    val themeDarkSecondaryContainerColor: StateFlow<Int> = _themeDarkSecondaryContainerColor

    private val _themeDarkTertiaryContainerColor =
        MutableStateFlow(prefs.getInt(KEY_THEME_DARK_TERTIARY_CONTAINER_COLOR, 0xFF1E636F.toInt()))
    val themeDarkTertiaryContainerColor: StateFlow<Int> = _themeDarkTertiaryContainerColor

    private val _themeDarkSurfaceVariantColor =
        MutableStateFlow(prefs.getInt(KEY_THEME_DARK_SURFACE_VARIANT_COLOR, 0xFF44474F.toInt()))
    val themeDarkSurfaceVariantColor: StateFlow<Int> = _themeDarkSurfaceVariantColor

    private val _themeDarkOutlineColor =
        MutableStateFlow(prefs.getInt(KEY_THEME_DARK_OUTLINE_COLOR, 0xFF8E9099.toInt()))
    val themeDarkOutlineColor: StateFlow<Int> = _themeDarkOutlineColor

    private val _nightModeStartTime = MutableStateFlow(prefs.getString(KEY_NIGHT_MODE_START, "22:00") ?: "22:00")
    val nightModeStartTime: StateFlow<String> = _nightModeStartTime

    private val _nightModeEndTime = MutableStateFlow(prefs.getString(KEY_NIGHT_MODE_END, "07:00") ?: "07:00")
    val nightModeEndTime: StateFlow<String> = _nightModeEndTime

    private val _nightModeBrightnessThreshold = MutableStateFlow(prefs.getFloat(KEY_NIGHT_MODE_BRIGHTNESS, 0.2f))
    val nightModeBrightnessThreshold: StateFlow<Float> = _nightModeBrightnessThreshold

    private val _emojiStyle =
        MutableStateFlow(EmojiStyle.entries[prefs.getInt(KEY_EMOJI_STYLE, EmojiStyle.SYSTEM.ordinal)])
    val emojiStyle: StateFlow<EmojiStyle> = _emojiStyle

    private val _isAppleEmojiDownloaded = MutableStateFlow(prefs.getBoolean(KEY_APPLE_EMOJI_DOWNLOADED, false))
    val isAppleEmojiDownloaded: StateFlow<Boolean> = _isAppleEmojiDownloaded

    private val _isTwitterEmojiDownloaded = MutableStateFlow(prefs.getBoolean(KEY_TWITTER_EMOJI_DOWNLOADED, false))
    val isTwitterEmojiDownloaded: StateFlow<Boolean> = _isTwitterEmojiDownloaded

    private val _isWindowsEmojiDownloaded = MutableStateFlow(prefs.getBoolean(KEY_WINDOWS_EMOJI_DOWNLOADED, false))
    val isWindowsEmojiDownloaded: StateFlow<Boolean> = _isWindowsEmojiDownloaded

    private val _isCatmojiEmojiDownloaded = MutableStateFlow(prefs.getBoolean(KEY_CATMOJI_EMOJI_DOWNLOADED, false))
    val isCatmojiEmojiDownloaded: StateFlow<Boolean> = _isCatmojiEmojiDownloaded

    private val _isNotoEmojiDownloaded = MutableStateFlow(prefs.getBoolean(KEY_NOTO_EMOJI_DOWNLOADED, false))
    val isNotoEmojiDownloaded: StateFlow<Boolean> = _isNotoEmojiDownloaded

    private val _autoDownloadMobile = MutableStateFlow(prefs.getBoolean(KEY_AUTO_DOWNLOAD_MOBILE, true))
    override val autoDownloadMobile: StateFlow<Boolean> = _autoDownloadMobile

    private val _autoDownloadWifi = MutableStateFlow(prefs.getBoolean(KEY_AUTO_DOWNLOAD_WIFI, true))
    override val autoDownloadWifi: StateFlow<Boolean> = _autoDownloadWifi

    private val _autoDownloadRoaming = MutableStateFlow(prefs.getBoolean(KEY_AUTO_DOWNLOAD_ROAMING, false))
    override val autoDownloadRoaming: StateFlow<Boolean> = _autoDownloadRoaming

    private val _autoDownloadFiles = MutableStateFlow(prefs.getBoolean(KEY_AUTO_DOWNLOAD_FILES, false))
    override val autoDownloadFiles: StateFlow<Boolean> = _autoDownloadFiles

    private val _autoDownloadStickers = MutableStateFlow(prefs.getBoolean(KEY_AUTO_DOWNLOAD_STICKERS, true))
    override val autoDownloadStickers: StateFlow<Boolean> = _autoDownloadStickers

    private val _autoDownloadVideoNotes = MutableStateFlow(prefs.getBoolean(KEY_AUTO_DOWNLOAD_VIDEO_NOTES, true))
    override val autoDownloadVideoNotes: StateFlow<Boolean> = _autoDownloadVideoNotes

    private val _autoplayGifs = MutableStateFlow(prefs.getBoolean(KEY_AUTOPLAY_GIFS, true))
    val autoplayGifs: StateFlow<Boolean> = _autoplayGifs

    private val _autoplayVideos = MutableStateFlow(prefs.getBoolean(KEY_AUTOPLAY_VIDEOS, true))
    val autoplayVideos: StateFlow<Boolean> = _autoplayVideos

    private val _enableStreaming = MutableStateFlow(prefs.getBoolean(KEY_ENABLE_STREAMING, true))
    val enableStreaming: StateFlow<Boolean> = _enableStreaming

    private val _compressPhotos = MutableStateFlow(prefs.getBoolean(KEY_COMPRESS_PHOTOS, true))
    val compressPhotos: StateFlow<Boolean> = _compressPhotos

    private val _compressVideos = MutableStateFlow(prefs.getBoolean(KEY_COMPRESS_VIDEOS, true))
    val compressVideos: StateFlow<Boolean> = _compressVideos

    private val _cacheLimitSize = MutableStateFlow(prefs.getLong(KEY_CACHE_LIMIT_SIZE, 10L * 1024 * 1024 * 1024))
    val cacheLimitSize: StateFlow<Long> = _cacheLimitSize

    private val _autoClearCacheTime = MutableStateFlow(prefs.getInt(KEY_AUTO_CLEAR_CACHE_TIME, 7))
    val autoClearCacheTime: StateFlow<Int> = _autoClearCacheTime

    private val _privateChatsNotifications = MutableStateFlow(prefs.getBoolean(KEY_NOTIF_PRIVATE, true))
    override val privateChatsNotifications: StateFlow<Boolean> = _privateChatsNotifications

    private val _groupsNotifications = MutableStateFlow(prefs.getBoolean(KEY_NOTIF_GROUPS, true))
    override val groupsNotifications: StateFlow<Boolean> = _groupsNotifications

    private val _channelsNotifications = MutableStateFlow(prefs.getBoolean(KEY_NOTIF_CHANNELS, true))
    override val channelsNotifications: StateFlow<Boolean> = _channelsNotifications

    private val _inAppSounds = MutableStateFlow(prefs.getBoolean(KEY_IN_APP_SOUNDS, true))
    override val inAppSounds: StateFlow<Boolean> = _inAppSounds

    private val _inAppVibrate = MutableStateFlow(prefs.getBoolean(KEY_IN_APP_VIBRATE, true))
    override val inAppVibrate: StateFlow<Boolean> = _inAppVibrate

    private val _inAppPreview = MutableStateFlow(prefs.getBoolean(KEY_IN_APP_PREVIEW, true))
    override val inAppPreview: StateFlow<Boolean> = _inAppPreview

    private val _contactJoinedNotifications = MutableStateFlow(prefs.getBoolean(KEY_CONTACT_JOINED, true))
    override val contactJoinedNotifications: StateFlow<Boolean> = _contactJoinedNotifications

    private val _pinnedMessagesNotifications = MutableStateFlow(prefs.getBoolean(KEY_PINNED_MESSAGES, true))
    override val pinnedMessagesNotifications: StateFlow<Boolean> = _pinnedMessagesNotifications

    private val _backgroundServiceEnabled = MutableStateFlow(prefs.getBoolean(KEY_BACKGROUND_SERVICE_ENABLED, true))
    override val backgroundServiceEnabled: StateFlow<Boolean> = _backgroundServiceEnabled

    private val _isPowerSavingMode = MutableStateFlow(prefs.getBoolean(KEY_POWER_SAVING_MODE, false))
    override val isPowerSavingMode: StateFlow<Boolean> = _isPowerSavingMode

    private val _isWakeLockEnabled = MutableStateFlow(prefs.getBoolean(KEY_WAKE_LOCK_ENABLED, true))
    override val isWakeLockEnabled: StateFlow<Boolean> = _isWakeLockEnabled

    private val _hideForegroundNotification =
        MutableStateFlow(prefs.getBoolean(KEY_HIDE_FOREGROUND_NOTIFICATION, false))
    override val hideForegroundNotification: StateFlow<Boolean> = _hideForegroundNotification

    private val _batteryOptimizationEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_BATTERY_OPTIMIZATION_ENABLED, false))
    override val batteryOptimizationEnabled: StateFlow<Boolean> = _batteryOptimizationEnabled

    private val _notificationVibrationPattern =
        MutableStateFlow(prefs.getString(KEY_NOTIF_VIBRATION, "default") ?: "default")
    override val notificationVibrationPattern: StateFlow<String> = _notificationVibrationPattern

    private val _notificationPriority = MutableStateFlow(prefs.getInt(KEY_NOTIF_PRIORITY, 1))
    override val notificationPriority: StateFlow<Int> = _notificationPriority

    private val _repeatNotifications = MutableStateFlow(prefs.getInt(KEY_REPEAT_NOTIFICATIONS, 60))
    override val repeatNotifications: StateFlow<Int> = _repeatNotifications

    private val _showSenderOnly = MutableStateFlow(prefs.getBoolean(KEY_SHOW_SENDER_ONLY, false))
    override val showSenderOnly: StateFlow<Boolean> = _showSenderOnly

    private val _pushProvider =
        MutableStateFlow(PushProvider.entries[prefs.getInt(KEY_PUSH_PROVIDER, PushProvider.FCM.ordinal)])
    override val pushProvider: StateFlow<PushProvider> = _pushProvider

    private val _isArchivePinned = MutableStateFlow(prefs.getBoolean(KEY_IS_ARCHIVE_PINNED, true))
    override val isArchivePinned: StateFlow<Boolean> = _isArchivePinned

    private val _isArchiveAlwaysVisible = MutableStateFlow(prefs.getBoolean(KEY_IS_ARCHIVE_ALWAYS_VISIBLE, false))
    override val isArchiveAlwaysVisible: StateFlow<Boolean> = _isArchiveAlwaysVisible

    private val _showLinkPreviews = MutableStateFlow(prefs.getBoolean(KEY_SHOW_LINK_PREVIEWS, true))
    override val showLinkPreviews: StateFlow<Boolean> = _showLinkPreviews

    private val _isDragToBackEnabled = MutableStateFlow(prefs.getBoolean(KEY_DRAG_TO_BACK, true))
    val isDragToBackEnabled: StateFlow<Boolean> = _isDragToBackEnabled

    private val _isChatAnimationsEnabled = MutableStateFlow(prefs.getBoolean(KEY_CHAT_ANIMATIONS_ENABLED, true))
    override val isChatAnimationsEnabled: StateFlow<Boolean> = _isChatAnimationsEnabled

    private val _chatListMessageLines = MutableStateFlow(prefs.getInt(KEY_CHAT_LIST_MESSAGE_LINES, 1))
    override val chatListMessageLines: StateFlow<Int> = _chatListMessageLines

    private val _showChatListPhotos = MutableStateFlow(prefs.getBoolean(KEY_SHOW_CHAT_LIST_PHOTOS, true))
    override val showChatListPhotos: StateFlow<Boolean> = _showChatListPhotos

    private val _isAdBlockEnabled = MutableStateFlow(prefs.getBoolean(KEY_ADBLOCK_ENABLED, false))
    val isAdBlockEnabled: StateFlow<Boolean> = _isAdBlockEnabled

    private val _adBlockKeywords = MutableStateFlow(prefs.getStringSet(KEY_ADBLOCK_KEYWORDS, emptySet()) ?: emptySet())
    val adBlockKeywords: StateFlow<Set<String>> = _adBlockKeywords

    private val _adBlockWhitelistedChannels = MutableStateFlow(
        prefs.getStringSet(KEY_ADBLOCK_WHITELISTED_CHANNELS, emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet()
            ?: emptySet()
    )
    val adBlockWhitelistedChannels: StateFlow<Set<Long>> = _adBlockWhitelistedChannels

    private val _enabledProxyId =
        MutableStateFlow(if (prefs.contains(KEY_ENABLED_PROXY_ID)) prefs.getInt(KEY_ENABLED_PROXY_ID, 0) else null)
    override val enabledProxyId: StateFlow<Int?> = _enabledProxyId

    private val _isAutoBestProxyEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_BEST_PROXY, false))
    override val isAutoBestProxyEnabled: StateFlow<Boolean> = _isAutoBestProxyEnabled

    private val _isTelegaProxyEnabled = MutableStateFlow(prefs.getBoolean(KEY_TELEGA_PROXY, false))
    override val isTelegaProxyEnabled: StateFlow<Boolean> = _isTelegaProxyEnabled

    private val _telegaProxyUrls = MutableStateFlow(prefs.getStringSet(KEY_TELEGA_PROXY_URLS, emptySet()) ?: emptySet())
    override val telegaProxyUrls: StateFlow<Set<String>> = _telegaProxyUrls

    private val _preferIpv6 = MutableStateFlow(prefs.getBoolean(KEY_PREFER_IPV6, false))
    override val preferIpv6: StateFlow<Boolean> = _preferIpv6

    private val _userProxyBackups = MutableStateFlow(prefs.getStringSet(KEY_USER_PROXY_BACKUPS, emptySet()) ?: emptySet())
    override val userProxyBackups: StateFlow<Set<String>> = _userProxyBackups

    private val _isBiometricEnabled = MutableStateFlow(securePrefs.getBoolean(KEY_BIOMETRIC_ENABLED, false))
    override val isBiometricEnabled: StateFlow<Boolean> = _isBiometricEnabled

    private val _passcode = MutableStateFlow(securePrefs.getString(KEY_PASSCODE, null))
    override val passcode: StateFlow<String?> = _passcode

    private val _isPermissionRequested = MutableStateFlow(prefs.getBoolean(KEY_PERMISSION_REQUESTED, false))
    override val isPermissionRequested: StateFlow<Boolean> = _isPermissionRequested

    private val _isSupportViewed = MutableStateFlow(prefs.getBoolean(KEY_SUPPORT_VIEWED, false))
    override val isSupportViewed: StateFlow<Boolean> = _isSupportViewed

    init {
        if (_adBlockKeywords.value.isEmpty()) {
            externalScope.launch {
                loadBaseKeywords()
            }
        }
    }

    private suspend fun loadBaseKeywords() {
        val keywords = withContext(Dispatchers.IO) {
            try {
                context.assets.open("adblock_keywords.txt").bufferedReader().useLines { lines ->
                    lines.filter { it.isNotBlank() }.toSet()
                }
            } catch (e: Exception) {
                setOf("erid", "#ad", "#реклама")
            }
        }
        setAdBlockKeywords(keywords)
    }

    fun setFontSize(size: Float) {
        prefs.edit().putFloat(KEY_FONT_SIZE, size).apply()
        _fontSize.value = size
    }
    
    fun setLetterSpacing(spacing: Float) {
        prefs.edit().putFloat(KEY_LETTER_SPACING, spacing).apply()
        _letterSpacing.value = spacing
    }

    fun setBubbleRadius(radius: Float) {
        prefs.edit().putFloat(KEY_BUBBLE_RADIUS, radius).apply()
        _bubbleRadius.value = radius
    }

    fun setWallpaper(wallpaper: String?) {
        prefs.edit().putString(KEY_WALLPAPER, wallpaper).apply()
        _wallpaper.value = wallpaper
    }

    fun setWallpaperBlurred(isBlurred: Boolean) {
        prefs.edit().putBoolean(KEY_WALLPAPER_BLURRED, isBlurred).apply()
        _isWallpaperBlurred.value = isBlurred
    }

    fun setWallpaperBlurIntensity(intensity: Int) {
        prefs.edit().putInt(KEY_WALLPAPER_BLUR_INTENSITY, intensity).apply()
        _wallpaperBlurIntensity.value = intensity
    }

    fun setWallpaperMoving(isMoving: Boolean) {
        prefs.edit().putBoolean(KEY_WALLPAPER_MOVING, isMoving).apply()
        _isWallpaperMoving.value = isMoving
    }

    fun setWallpaperDimming(dimming: Int) {
        prefs.edit().putInt(KEY_WALLPAPER_DIMMING, dimming).apply()
        _wallpaperDimming.value = dimming
    }

    fun setWallpaperGrayscale(isGrayscale: Boolean) {
        prefs.edit().putBoolean(KEY_WALLPAPER_GRAYSCALE, isGrayscale).apply()
        _isWallpaperGrayscale.value = isGrayscale
    }

    fun setPlayerGesturesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PLAYER_GESTURES_ENABLED, enabled).apply()
        _isPlayerGesturesEnabled.value = enabled
    }

    fun setPlayerDoubleTapSeekEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PLAYER_DOUBLE_TAP_SEEK, enabled).apply()
        _isPlayerDoubleTapSeekEnabled.value = enabled
    }

    fun setPlayerSeekDuration(duration: Int) {
        prefs.edit().putInt(KEY_PLAYER_SEEK_DURATION, duration).apply()
        _playerSeekDuration.value = duration
    }

    fun setPlayerZoomEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PLAYER_ZOOM_ENABLED, enabled).apply()
        _isPlayerZoomEnabled.value = enabled
    }

    fun setNightMode(mode: NightMode) {
        prefs.edit().putInt(KEY_NIGHT_MODE, mode.ordinal).apply()
        _nightMode.value = mode
    }

    fun setDynamicColorsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLORS, enabled).apply()
        _isDynamicColorsEnabled.value = enabled
    }

    fun setAmoledThemeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AMOLED_THEME, enabled).apply()
        _isAmoledThemeEnabled.value = enabled
    }

    fun setCustomThemeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CUSTOM_THEME_ENABLED, enabled).apply()
        _isCustomThemeEnabled.value = enabled
    }

    fun setThemePrimaryColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_PRIMARY_COLOR, color).apply()
        _themePrimaryColor.value = color
    }

    fun setThemeSecondaryColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_SECONDARY_COLOR, color).apply()
        _themeSecondaryColor.value = color
    }

    fun setThemeTertiaryColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_TERTIARY_COLOR, color).apply()
        _themeTertiaryColor.value = color
    }

    fun setThemeBackgroundColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_BACKGROUND_COLOR, color).apply()
        _themeBackgroundColor.value = color
    }

    fun setThemeSurfaceColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_SURFACE_COLOR, color).apply()
        _themeSurfaceColor.value = color
    }

    fun setThemePrimaryContainerColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_PRIMARY_CONTAINER_COLOR, color).apply()
        _themePrimaryContainerColor.value = color
    }

    fun setThemeSecondaryContainerColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_SECONDARY_CONTAINER_COLOR, color).apply()
        _themeSecondaryContainerColor.value = color
    }

    fun setThemeTertiaryContainerColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_TERTIARY_CONTAINER_COLOR, color).apply()
        _themeTertiaryContainerColor.value = color
    }

    fun setThemeSurfaceVariantColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_SURFACE_VARIANT_COLOR, color).apply()
        _themeSurfaceVariantColor.value = color
    }

    fun setThemeOutlineColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_OUTLINE_COLOR, color).apply()
        _themeOutlineColor.value = color
    }

    fun setThemeDarkPrimaryColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_DARK_PRIMARY_COLOR, color).apply()
        _themeDarkPrimaryColor.value = color
    }

    fun setThemeDarkSecondaryColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_DARK_SECONDARY_COLOR, color).apply()
        _themeDarkSecondaryColor.value = color
    }

    fun setThemeDarkTertiaryColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_DARK_TERTIARY_COLOR, color).apply()
        _themeDarkTertiaryColor.value = color
    }

    fun setThemeDarkBackgroundColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_DARK_BACKGROUND_COLOR, color).apply()
        _themeDarkBackgroundColor.value = color
    }

    fun setThemeDarkSurfaceColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_DARK_SURFACE_COLOR, color).apply()
        _themeDarkSurfaceColor.value = color
    }

    fun setThemeDarkPrimaryContainerColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_DARK_PRIMARY_CONTAINER_COLOR, color).apply()
        _themeDarkPrimaryContainerColor.value = color
    }

    fun setThemeDarkSecondaryContainerColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_DARK_SECONDARY_CONTAINER_COLOR, color).apply()
        _themeDarkSecondaryContainerColor.value = color
    }

    fun setThemeDarkTertiaryContainerColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_DARK_TERTIARY_CONTAINER_COLOR, color).apply()
        _themeDarkTertiaryContainerColor.value = color
    }

    fun setThemeDarkSurfaceVariantColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_DARK_SURFACE_VARIANT_COLOR, color).apply()
        _themeDarkSurfaceVariantColor.value = color
    }

    fun setThemeDarkOutlineColor(color: Int) {
        prefs.edit().putInt(KEY_THEME_DARK_OUTLINE_COLOR, color).apply()
        _themeDarkOutlineColor.value = color
    }

    fun setNightModeStartTime(time: String) {
        prefs.edit().putString(KEY_NIGHT_MODE_START, time).apply()
        _nightModeStartTime.value = time
    }

    fun setNightModeEndTime(time: String) {
        prefs.edit().putString(KEY_NIGHT_MODE_END, time).apply()
        _nightModeEndTime.value = time
    }

    fun setNightModeBrightnessThreshold(threshold: Float) {
        prefs.edit().putFloat(KEY_NIGHT_MODE_BRIGHTNESS, threshold).apply()
        _nightModeBrightnessThreshold.value = threshold
    }

    fun setEmojiStyle(style: EmojiStyle) {
        prefs.edit().putInt(KEY_EMOJI_STYLE, style.ordinal).apply()
        _emojiStyle.value = style
    }

    fun setAppleEmojiDownloaded(downloaded: Boolean) {
        prefs.edit().putBoolean(KEY_APPLE_EMOJI_DOWNLOADED, downloaded).apply()
        _isAppleEmojiDownloaded.value = downloaded
    }

    fun setTwitterEmojiDownloaded(downloaded: Boolean) {
        prefs.edit().putBoolean(KEY_TWITTER_EMOJI_DOWNLOADED, downloaded).apply()
        _isTwitterEmojiDownloaded.value = downloaded
    }

    fun setWindowsEmojiDownloaded(downloaded: Boolean) {
        prefs.edit().putBoolean(KEY_WINDOWS_EMOJI_DOWNLOADED, downloaded).apply()
        _isWindowsEmojiDownloaded.value = downloaded
    }

    fun setCatmojiEmojiDownloaded(downloaded: Boolean) {
        prefs.edit().putBoolean(KEY_CATMOJI_EMOJI_DOWNLOADED, downloaded).apply()
        _isCatmojiEmojiDownloaded.value = downloaded
    }

    fun setNotoEmojiDownloaded(downloaded: Boolean) {
        prefs.edit().putBoolean(KEY_NOTO_EMOJI_DOWNLOADED, downloaded).apply()
        _isNotoEmojiDownloaded.value = downloaded
    }

    override fun setAutoDownloadMobile(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_MOBILE, enabled).apply()
        _autoDownloadMobile.value = enabled
    }

    override fun setAutoDownloadWifi(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_WIFI, enabled).apply()
        _autoDownloadWifi.value = enabled
    }

    override fun setAutoDownloadRoaming(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_ROAMING, enabled).apply()
        _autoDownloadRoaming.value = enabled
    }

    override fun setAutoDownloadFiles(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_FILES, enabled).apply()
        _autoDownloadFiles.value = enabled
    }

    override fun setAutoDownloadStickers(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_STICKERS, enabled).apply()
        _autoDownloadStickers.value = enabled
    }

    override fun setAutoDownloadVideoNotes(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_VIDEO_NOTES, enabled).apply()
        _autoDownloadVideoNotes.value = enabled
    }

    fun setAutoplayGifs(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOPLAY_GIFS, enabled).apply()
        _autoplayGifs.value = enabled
    }

    fun setAutoplayVideos(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOPLAY_VIDEOS, enabled).apply()
        _autoplayVideos.value = enabled
    }

    fun setEnableStreaming(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLE_STREAMING, enabled).apply()
        _enableStreaming.value = enabled
    }

    fun setCompressPhotos(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COMPRESS_PHOTOS, enabled).apply()
        _compressPhotos.value = enabled
    }

    fun setCompressVideos(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COMPRESS_VIDEOS, enabled).apply()
        _compressVideos.value = enabled
    }

    fun setCacheLimitSize(size: Long) {
        prefs.edit().putLong(KEY_CACHE_LIMIT_SIZE, size).apply()
        _cacheLimitSize.value = size
    }

    fun setAutoClearCacheTime(time: Int) {
        prefs.edit().putInt(KEY_AUTO_CLEAR_CACHE_TIME, time).apply()
        _autoClearCacheTime.value = time
    }

    override fun setPrivateChatsNotifications(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIF_PRIVATE, enabled).apply()
        _privateChatsNotifications.value = enabled
    }

    override fun setGroupsNotifications(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIF_GROUPS, enabled).apply()
        _groupsNotifications.value = enabled
    }

    override fun setChannelsNotifications(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIF_CHANNELS, enabled).apply()
        _channelsNotifications.value = enabled
    }

    override fun setInAppSounds(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IN_APP_SOUNDS, enabled).apply()
        _inAppSounds.value = enabled
    }

    override fun setInAppVibrate(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IN_APP_VIBRATE, enabled).apply()
        _inAppVibrate.value = enabled
    }

    override fun setInAppPreview(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IN_APP_PREVIEW, enabled).apply()
        _inAppPreview.value = enabled
    }

    override fun setContactJoinedNotifications(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONTACT_JOINED, enabled).apply()
        _contactJoinedNotifications.value = enabled
    }

    override fun setPinnedMessagesNotifications(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PINNED_MESSAGES, enabled).apply()
        _pinnedMessagesNotifications.value = enabled
    }

    override fun setBackgroundServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND_SERVICE_ENABLED, enabled).apply()
        _backgroundServiceEnabled.value = enabled
    }

    override fun setPowerSavingMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_POWER_SAVING_MODE, enabled).apply()
        _isPowerSavingMode.value = enabled
    }

    override fun setWakeLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WAKE_LOCK_ENABLED, enabled).apply()
        _isWakeLockEnabled.value = enabled
    }

    override fun setHideForegroundNotification(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_FOREGROUND_NOTIFICATION, enabled).apply()
        _hideForegroundNotification.value = enabled
    }

    override fun setBatteryOptimizationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BATTERY_OPTIMIZATION_ENABLED, enabled).apply()
        _batteryOptimizationEnabled.value = enabled
    }

    override fun setNotificationVibrationPattern(pattern: String) {
        prefs.edit().putString(KEY_NOTIF_VIBRATION, pattern).apply()
        _notificationVibrationPattern.value = pattern
    }

    override fun setNotificationPriority(priority: Int) {
        prefs.edit().putInt(KEY_NOTIF_PRIORITY, priority).apply()
        _notificationPriority.value = priority
    }

    override fun setRepeatNotifications(minutes: Int) {
        prefs.edit().putInt(KEY_REPEAT_NOTIFICATIONS, minutes).apply()
        _repeatNotifications.value = minutes
    }

    override fun setShowSenderOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_SENDER_ONLY, enabled).apply()
        _showSenderOnly.value = enabled
    }

    override fun setPushProvider(provider: PushProvider) {
        prefs.edit().putInt(KEY_PUSH_PROVIDER, provider.ordinal).apply()
        _pushProvider.value = provider
    }

    override fun setArchivePinned(pinned: Boolean) {
        prefs.edit().putBoolean(KEY_IS_ARCHIVE_PINNED, pinned).apply()
        _isArchivePinned.value = pinned
    }

    override fun setArchiveAlwaysVisible(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IS_ARCHIVE_ALWAYS_VISIBLE, enabled).apply()
        _isArchiveAlwaysVisible.value = enabled
    }

    override fun setShowLinkPreviews(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_LINK_PREVIEWS, enabled).apply()
        _showLinkPreviews.value = enabled
    }

    fun setDragToBackEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DRAG_TO_BACK, enabled).apply()
        _isDragToBackEnabled.value = enabled
    }

    override fun setChatAnimationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CHAT_ANIMATIONS_ENABLED, enabled).apply()
        _isChatAnimationsEnabled.value = enabled
    }

    override fun setChatListMessageLines(lines: Int) {
        prefs.edit().putInt(KEY_CHAT_LIST_MESSAGE_LINES, lines).apply()
        _chatListMessageLines.value = lines
    }

    override fun setShowChatListPhotos(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_CHAT_LIST_PHOTOS, enabled).apply()
        _showChatListPhotos.value = enabled
    }

    fun setAdBlockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ADBLOCK_ENABLED, enabled).apply()
        _isAdBlockEnabled.value = enabled
    }

    fun setAdBlockKeywords(keywords: Set<String>) {
        prefs.edit().putStringSet(KEY_ADBLOCK_KEYWORDS, keywords).apply()
        _adBlockKeywords.value = keywords
    }

    fun setAdBlockWhitelistedChannels(channels: Set<Long>) {
        prefs.edit().putStringSet(KEY_ADBLOCK_WHITELISTED_CHANNELS, channels.map { it.toString() }.toSet()).apply()
        _adBlockWhitelistedChannels.value = channels
    }

    override fun setEnabledProxyId(proxyId: Int?) {
        if (proxyId != null) {
            prefs.edit().putInt(KEY_ENABLED_PROXY_ID, proxyId).apply()
        } else {
            prefs.edit().remove(KEY_ENABLED_PROXY_ID).apply()
        }
        _enabledProxyId.value = proxyId
    }

    override fun setAutoBestProxyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_BEST_PROXY, enabled).apply()
        _isAutoBestProxyEnabled.value = enabled
    }

    override fun setTelegaProxyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TELEGA_PROXY, enabled).apply()
        _isTelegaProxyEnabled.value = enabled
    }

    override fun setTelegaProxyUrls(urls: Set<String>) {
        prefs.edit().putStringSet(KEY_TELEGA_PROXY_URLS, urls).apply()
        _telegaProxyUrls.value = urls
    }

    override fun setPreferIpv6(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREFER_IPV6, enabled).apply()
        _preferIpv6.value = enabled
    }

    override fun setUserProxyBackups(backups: Set<String>) {
        prefs.edit().putStringSet(KEY_USER_PROXY_BACKUPS, backups).apply()
        _userProxyBackups.value = backups
    }

    override fun setBiometricEnabled(enabled: Boolean) {
        securePrefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
        _isBiometricEnabled.value = enabled
    }

    override fun setPasscode(passcode: String?) {
        if (passcode != null) {
            securePrefs.edit().putString(KEY_PASSCODE, passcode).apply()
        } else {
            securePrefs.edit().remove(KEY_PASSCODE).apply()
        }
        _passcode.value = passcode
    }

    override fun setPermissionRequested(requested: Boolean) {
        prefs.edit().putBoolean(KEY_PERMISSION_REQUESTED, requested).apply()
        _isPermissionRequested.value = requested
    }

    override fun clearPreferences() {
        prefs.edit().clear().apply()
        _fontSize.value = 16f
        _bubbleRadius.value = 18f
        _wallpaper.value = null
        _isWallpaperBlurred.value = false
        _wallpaperBlurIntensity.value = 20
        _isWallpaperMoving.value = false
        _wallpaperDimming.value = 0
        _isWallpaperGrayscale.value = false
        _isPlayerGesturesEnabled.value = true
        _isPlayerDoubleTapSeekEnabled.value = true
        _playerSeekDuration.value = 10
        _isPlayerZoomEnabled.value = true
        _nightMode.value = NightMode.SYSTEM
        _isDynamicColorsEnabled.value = true
        _isAmoledThemeEnabled.value = false
        _isCustomThemeEnabled.value = false
        _themePrimaryColor.value = 0xFF3390EC.toInt()
        _themeSecondaryColor.value = 0xFF4C7599.toInt()
        _themeTertiaryColor.value = 0xFF00ACC1.toInt()
        _themeBackgroundColor.value = 0xFFFFFBFE.toInt()
        _themeSurfaceColor.value = 0xFFFFFBFE.toInt()
        _themePrimaryContainerColor.value = 0xFFD4E3FF.toInt()
        _themeSecondaryContainerColor.value = 0xFFD0E4F7.toInt()
        _themeTertiaryContainerColor.value = 0xFFC4EEF4.toInt()
        _themeSurfaceVariantColor.value = 0xFFE1E2EC.toInt()
        _themeOutlineColor.value = 0xFF757680.toInt()
        _themeDarkPrimaryColor.value = 0xFF64B5F6.toInt()
        _themeDarkSecondaryColor.value = 0xFF81A9CA.toInt()
        _themeDarkTertiaryColor.value = 0xFF4DD0E1.toInt()
        _themeDarkBackgroundColor.value = 0xFF121212.toInt()
        _themeDarkSurfaceColor.value = 0xFF121212.toInt()
        _themeDarkPrimaryContainerColor.value = 0xFF224A77.toInt()
        _themeDarkSecondaryContainerColor.value = 0xFF334F65.toInt()
        _themeDarkTertiaryContainerColor.value = 0xFF1E636F.toInt()
        _themeDarkSurfaceVariantColor.value = 0xFF44474F.toInt()
        _themeDarkOutlineColor.value = 0xFF8E9099.toInt()
        _nightModeStartTime.value = "22:00"
        _nightModeEndTime.value = "07:00"
        _nightModeBrightnessThreshold.value = 0.2f
        _emojiStyle.value = EmojiStyle.SYSTEM
        _isAppleEmojiDownloaded.value = false
        _isTwitterEmojiDownloaded.value = false
        _isWindowsEmojiDownloaded.value = false
        _isCatmojiEmojiDownloaded.value = false
        _isNotoEmojiDownloaded.value = false
        _autoDownloadMobile.value = true
        _autoDownloadWifi.value = true
        _autoDownloadRoaming.value = false
        _autoDownloadFiles.value = false
        _autoDownloadStickers.value = true
        _autoDownloadVideoNotes.value = true
        _autoplayGifs.value = true
        _autoplayVideos.value = true
        _enableStreaming.value = true
        _compressPhotos.value = true
        _compressVideos.value = true
        _cacheLimitSize.value = 10L * 1024 * 1024 * 1024
        _autoClearCacheTime.value = 7
        _privateChatsNotifications.value = true
        _groupsNotifications.value = true
        _channelsNotifications.value = true
        _inAppSounds.value = true
        _inAppVibrate.value = true
        _inAppPreview.value = true
        _contactJoinedNotifications.value = true
        _pinnedMessagesNotifications.value = true
        _backgroundServiceEnabled.value = true
        _isPowerSavingMode.value = false
        _isWakeLockEnabled.value = true
        _hideForegroundNotification.value = false
        _batteryOptimizationEnabled.value = false
        _notificationVibrationPattern.value = "default"
        _notificationPriority.value = 1
        _repeatNotifications.value = 60
        _showSenderOnly.value = false
        _pushProvider.value = PushProvider.FCM
        _isArchivePinned.value = true
        _isArchiveAlwaysVisible.value = false
        _showLinkPreviews.value = true
        _isDragToBackEnabled.value = true
        _isChatAnimationsEnabled.value = true
        _chatListMessageLines.value = 1
        _showChatListPhotos.value = true
        _isAdBlockEnabled.value = false
        _adBlockKeywords.value = emptySet()
        _adBlockWhitelistedChannels.value = emptySet()
        _enabledProxyId.value = null
        _isAutoBestProxyEnabled.value = false
        _isTelegaProxyEnabled.value = false
        _telegaProxyUrls.value = emptySet()
        _preferIpv6.value = false
        _userProxyBackups.value = emptySet()
        _isPermissionRequested.value = false
    }

    override fun clearSecurePreferences() {
        securePrefs.edit().clear().apply()
        _isBiometricEnabled.value = false
        _passcode.value = null
    }

    override fun setSupportViewed(viewed: Boolean) {
        prefs.edit().putBoolean(KEY_SUPPORT_VIEWED, viewed).apply()
        _isSupportViewed.value = viewed
    }

    companion object {
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_LETTER_SPACING = "letter_spacing"
        private const val KEY_BUBBLE_RADIUS = "bubble_radius"
        private const val KEY_WALLPAPER = "wallpaper"
        private const val KEY_WALLPAPER_BLURRED = "wallpaper_blurred"
        private const val KEY_WALLPAPER_BLUR_INTENSITY = "wallpaper_blur_intensity"
        private const val KEY_WALLPAPER_MOVING = "wallpaper_moving"
        private const val KEY_WALLPAPER_DIMMING = "wallpaper_dimming"
        private const val KEY_WALLPAPER_GRAYSCALE = "wallpaper_grayscale"
        private const val KEY_PLAYER_GESTURES_ENABLED = "player_gestures_enabled"
        private const val KEY_PLAYER_DOUBLE_TAP_SEEK = "player_double_tap_seek"
        private const val KEY_PLAYER_SEEK_DURATION = "player_seek_duration"
        private const val KEY_PLAYER_ZOOM_ENABLED = "player_zoom_enabled"
        private const val KEY_NIGHT_MODE = "night_mode"
        private const val KEY_DYNAMIC_COLORS = "dynamic_colors"
        private const val KEY_AMOLED_THEME = "amoled_theme"
        private const val KEY_CUSTOM_THEME_ENABLED = "custom_theme_enabled"
        private const val KEY_THEME_PRIMARY_COLOR = "theme_primary_color"
        private const val KEY_THEME_SECONDARY_COLOR = "theme_secondary_color"
        private const val KEY_THEME_TERTIARY_COLOR = "theme_tertiary_color"
        private const val KEY_THEME_BACKGROUND_COLOR = "theme_background_color"
        private const val KEY_THEME_SURFACE_COLOR = "theme_surface_color"
        private const val KEY_THEME_PRIMARY_CONTAINER_COLOR = "theme_primary_container_color"
        private const val KEY_THEME_SECONDARY_CONTAINER_COLOR = "theme_secondary_container_color"
        private const val KEY_THEME_TERTIARY_CONTAINER_COLOR = "theme_tertiary_container_color"
        private const val KEY_THEME_SURFACE_VARIANT_COLOR = "theme_surface_variant_color"
        private const val KEY_THEME_OUTLINE_COLOR = "theme_outline_color"
        private const val KEY_THEME_DARK_PRIMARY_COLOR = "theme_dark_primary_color"
        private const val KEY_THEME_DARK_SECONDARY_COLOR = "theme_dark_secondary_color"
        private const val KEY_THEME_DARK_TERTIARY_COLOR = "theme_dark_tertiary_color"
        private const val KEY_THEME_DARK_BACKGROUND_COLOR = "theme_dark_background_color"
        private const val KEY_THEME_DARK_SURFACE_COLOR = "theme_dark_surface_color"
        private const val KEY_THEME_DARK_PRIMARY_CONTAINER_COLOR = "theme_dark_primary_container_color"
        private const val KEY_THEME_DARK_SECONDARY_CONTAINER_COLOR = "theme_dark_secondary_container_color"
        private const val KEY_THEME_DARK_TERTIARY_CONTAINER_COLOR = "theme_dark_tertiary_container_color"
        private const val KEY_THEME_DARK_SURFACE_VARIANT_COLOR = "theme_dark_surface_variant_color"
        private const val KEY_THEME_DARK_OUTLINE_COLOR = "theme_dark_outline_color"
        private const val KEY_NIGHT_MODE_START = "night_mode_start"
        private const val KEY_NIGHT_MODE_END = "night_mode_end"
        private const val KEY_NIGHT_MODE_BRIGHTNESS = "night_mode_brightness"
        private const val KEY_EMOJI_STYLE = "emoji_style"
        private const val KEY_APPLE_EMOJI_DOWNLOADED = "apple_emoji_downloaded"
        private const val KEY_TWITTER_EMOJI_DOWNLOADED = "twitter_emoji_downloaded"
        private const val KEY_WINDOWS_EMOJI_DOWNLOADED = "windows_emoji_downloaded"
        private const val KEY_CATMOJI_EMOJI_DOWNLOADED = "catmoji_emoji_downloaded"
        private const val KEY_NOTO_EMOJI_DOWNLOADED = "noto_emoji_downloaded"

        private const val KEY_AUTO_DOWNLOAD_MOBILE = "auto_download_mobile"
        private const val KEY_AUTO_DOWNLOAD_WIFI = "auto_download_wifi"
        private const val KEY_AUTO_DOWNLOAD_ROAMING = "auto_download_roaming"
        private const val KEY_AUTO_DOWNLOAD_FILES = "auto_download_files"
        private const val KEY_AUTO_DOWNLOAD_STICKERS = "auto_download_stickers"
        private const val KEY_AUTO_DOWNLOAD_VIDEO_NOTES = "auto_download_video_notes"
        private const val KEY_AUTOPLAY_GIFS = "autoplay_gifs"
        private const val KEY_AUTOPLAY_VIDEOS = "autoplay_videos"
        private const val KEY_ENABLE_STREAMING = "enable_streaming"
        private const val KEY_COMPRESS_PHOTOS = "compress_photos"
        private const val KEY_COMPRESS_VIDEOS = "compress_videos"

        private const val KEY_CACHE_LIMIT_SIZE = "cache_limit_size"
        private const val KEY_AUTO_CLEAR_CACHE_TIME = "auto_clear_cache_time"

        private const val KEY_NOTIF_PRIVATE = "notif_private"
        private const val KEY_NOTIF_GROUPS = "notif_groups"
        private const val KEY_NOTIF_CHANNELS = "notif_channels"
        private const val KEY_IN_APP_SOUNDS = "in_app_sounds"
        private const val KEY_IN_APP_VIBRATE = "in_app_vibrate"
        private const val KEY_IN_APP_PREVIEW = "in_app_preview"
        private const val KEY_CONTACT_JOINED = "contact_joined"
        private const val KEY_PINNED_MESSAGES = "pinned_messages"
        private const val KEY_BACKGROUND_SERVICE_ENABLED = "background_service_enabled"
        private const val KEY_POWER_SAVING_MODE = "power_saving_mode"
        private const val KEY_WAKE_LOCK_ENABLED = "wake_lock_enabled"
        private const val KEY_HIDE_FOREGROUND_NOTIFICATION = "hide_foreground_notification"
        private const val KEY_BATTERY_OPTIMIZATION_ENABLED = "battery_optimization_enabled"
        private const val KEY_NOTIF_VIBRATION = "notif_vibration"
        private const val KEY_NOTIF_PRIORITY = "notif_priority"
        private const val KEY_REPEAT_NOTIFICATIONS = "repeat_notifications"
        private const val KEY_SHOW_SENDER_ONLY = "show_sender_only"
        private const val KEY_PUSH_PROVIDER = "push_provider"

        private const val KEY_IS_ARCHIVE_PINNED = "is_archive_pinned"
        private const val KEY_IS_ARCHIVE_ALWAYS_VISIBLE = "is_archive_always_visible"
        private const val KEY_SHOW_LINK_PREVIEWS = "show_link_previews"
        private const val KEY_DRAG_TO_BACK = "drag_to_back"
        private const val KEY_CHAT_ANIMATIONS_ENABLED = "chat_animations_enabled"
        private const val KEY_CHAT_LIST_MESSAGE_LINES = "chat_list_message_lines"
        private const val KEY_SHOW_CHAT_LIST_PHOTOS = "show_chat_list_photos"

        private const val KEY_ADBLOCK_ENABLED = "adblock_enabled"
        private const val KEY_ADBLOCK_KEYWORDS = "adblock_keywords"
        private const val KEY_ADBLOCK_WHITELISTED_CHANNELS = "adblock_whitelisted_channels"

        private const val KEY_ENABLED_PROXY_ID = "enabled_proxy_id"
        private const val KEY_AUTO_BEST_PROXY = "auto_best_proxy"
        private const val KEY_TELEGA_PROXY = "telega_proxy"
        private const val KEY_TELEGA_PROXY_URLS = "telega_proxy_urls"
        private const val KEY_PREFER_IPV6 = "prefer_ipv6"
        private const val KEY_USER_PROXY_BACKUPS = "user_proxy_backups"

        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_PASSCODE = "passcode"

        private const val KEY_PERMISSION_REQUESTED = "permission_requested"
        private const val KEY_SUPPORT_VIEWED = "support_viewed"
    }
}
