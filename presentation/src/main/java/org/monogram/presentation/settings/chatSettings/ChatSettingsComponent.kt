package org.monogram.presentation.settings.chatSettings

import android.graphics.Color.HSVToColor
import android.graphics.Color.colorToHSV
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.monogram.domain.managers.AssetsManager
import org.monogram.domain.managers.DistrManager
import org.monogram.domain.models.WallpaperModel
import org.monogram.domain.repository.EmojiRepository
import org.monogram.domain.repository.StickerRepository
import org.monogram.domain.repository.WallpaperRepository
import org.monogram.presentation.core.util.*
import org.monogram.presentation.root.AppComponentContext
import java.io.File
import java.net.URL

interface ChatSettingsComponent {
    val state: Value<State>
    val downloadUtils: IDownloadUtils
    fun onBackClicked()
    fun onFontSizeChanged(size: Float)
    fun onLetterSpacingChanged(size: Float)
    fun onBubbleRadiusChanged(radius: Float)
    fun onStickerSizeChanged(size: Float)
    fun onWallpaperChanged(wallpaper: String?)
    fun onWallpaperSelected(wallpaper: WallpaperModel)
    fun onWallpaperBlurChanged(wallpaper: WallpaperModel, isBlurred: Boolean)
    fun onWallpaperBlurIntensityChanged(intensity: Int)
    fun onWallpaperMotionChanged(wallpaper: WallpaperModel, isMoving: Boolean)
    fun onWallpaperDimmingChanged(dimming: Int)
    fun onWallpaperGrayscaleChanged(isGrayscale: Boolean)
    fun onPlayerGesturesEnabledChanged(enabled: Boolean)
    fun onPlayerDoubleTapSeekEnabledChanged(enabled: Boolean)
    fun onPlayerSeekDurationChanged(duration: Int)
    fun onPlayerZoomEnabledChanged(enabled: Boolean)
    fun onClearRecentStickers()
    fun onClearRecentEmojis()
    fun onArchivePinnedChanged(pinned: Boolean)
    fun onArchiveAlwaysVisibleChanged(enabled: Boolean)
    fun onShowLinkPreviewsChanged(enabled: Boolean)
    fun onNightModeChanged(mode: NightMode)
    fun onDynamicColorsChanged(enabled: Boolean)
    fun onAmoledThemeChanged(enabled: Boolean)
    fun onCustomThemeEnabledChanged(enabled: Boolean)
    fun onThemePrimaryColorChanged(color: Int)
    fun onThemeSecondaryColorChanged(color: Int)
    fun onThemeTertiaryColorChanged(color: Int)
    fun onThemeBackgroundColorChanged(color: Int)
    fun onThemeSurfaceColorChanged(color: Int)
    fun onThemePrimaryContainerColorChanged(color: Int)
    fun onThemeSecondaryContainerColorChanged(color: Int)
    fun onThemeTertiaryContainerColorChanged(color: Int)
    fun onThemeSurfaceVariantColorChanged(color: Int)
    fun onThemeOutlineColorChanged(color: Int)
    fun onThemeDarkPrimaryColorChanged(color: Int)
    fun onThemeDarkSecondaryColorChanged(color: Int)
    fun onThemeDarkTertiaryColorChanged(color: Int)
    fun onThemeDarkBackgroundColorChanged(color: Int)
    fun onThemeDarkSurfaceColorChanged(color: Int)
    fun onThemeDarkPrimaryContainerColorChanged(color: Int)
    fun onThemeDarkSecondaryContainerColorChanged(color: Int)
    fun onThemeDarkTertiaryContainerColorChanged(color: Int)
    fun onThemeDarkSurfaceVariantColorChanged(color: Int)
    fun onThemeDarkOutlineColorChanged(color: Int)
    fun onApplyThemeAccent(color: Int, darkPalette: Boolean)
    fun exportCustomThemeJson(): String
    fun importCustomThemeJson(json: String): Boolean
    fun onNightModeStartTimeChanged(time: String)
    fun onNightModeEndTimeChanged(time: String)
    fun onNightModeBrightnessThresholdChanged(threshold: Float)
    fun onDragToBackChanged(enabled: Boolean)
    fun onAdBlockClick()
    fun onEmojiStyleChanged(style: EmojiStyle)
    fun onEmojiStyleLongClick(style: EmojiStyle)
    fun onConfirmRemoveEmojiPack()
    fun onDismissRemoveEmojiPack()
    fun onCompressPhotosChanged(enabled: Boolean)
    fun onCompressVideosChanged(enabled: Boolean)
    fun onChatListMessageLinesChanged(lines: Int)
    fun onShowChatListPhotosChanged(enabled: Boolean)

    data class State(
        val fontSize: Float = 16f,
        val letterSpacing: Float = 0f,
        val bubbleRadius: Float = 18f,
        val stickerSize: Float = 200f,
        val wallpaper: String? = null,
        val isWallpaperBlurred: Boolean = false,
        val wallpaperBlurIntensity: Int = 20,
        val isWallpaperMoving: Boolean = false,
        val wallpaperDimming: Int = 0,
        val isWallpaperGrayscale: Boolean = false,
        val availableWallpapers: List<WallpaperModel> = emptyList(),
        val selectedWallpaper: WallpaperModel? = null,
        val isPlayerGesturesEnabled: Boolean = true,
        val isPlayerDoubleTapSeekEnabled: Boolean = true,
        val playerSeekDuration: Int = 10,
        val isPlayerZoomEnabled: Boolean = true,
        val isArchivePinned: Boolean = true,
        val isArchiveAlwaysVisible: Boolean = false,
        val showLinkPreviews: Boolean = true,
        val nightMode: NightMode = NightMode.SYSTEM,
        val isDynamicColorsEnabled: Boolean = true,
        val isAmoledThemeEnabled: Boolean = false,
        val isCustomThemeEnabled: Boolean = false,
        val themePrimaryColor: Int = 0xFF3390EC.toInt(),
        val themeSecondaryColor: Int = 0xFF4C7599.toInt(),
        val themeTertiaryColor: Int = 0xFF00ACC1.toInt(),
        val themeBackgroundColor: Int = 0xFFFFFBFE.toInt(),
        val themeSurfaceColor: Int = 0xFFFFFBFE.toInt(),
        val themePrimaryContainerColor: Int = 0xFFD4E3FF.toInt(),
        val themeSecondaryContainerColor: Int = 0xFFD0E4F7.toInt(),
        val themeTertiaryContainerColor: Int = 0xFFC4EEF4.toInt(),
        val themeSurfaceVariantColor: Int = 0xFFE1E2EC.toInt(),
        val themeOutlineColor: Int = 0xFF757680.toInt(),
        val themeDarkPrimaryColor: Int = 0xFF64B5F6.toInt(),
        val themeDarkSecondaryColor: Int = 0xFF81A9CA.toInt(),
        val themeDarkTertiaryColor: Int = 0xFF4DD0E1.toInt(),
        val themeDarkBackgroundColor: Int = 0xFF121212.toInt(),
        val themeDarkSurfaceColor: Int = 0xFF121212.toInt(),
        val themeDarkPrimaryContainerColor: Int = 0xFF224A77.toInt(),
        val themeDarkSecondaryContainerColor: Int = 0xFF334F65.toInt(),
        val themeDarkTertiaryContainerColor: Int = 0xFF1E636F.toInt(),
        val themeDarkSurfaceVariantColor: Int = 0xFF44474F.toInt(),
        val themeDarkOutlineColor: Int = 0xFF8E9099.toInt(),
        val nightModeStartTime: String = "22:00",
        val nightModeEndTime: String = "07:00",
        val nightModeBrightnessThreshold: Float = 0.2f,
        val isDragToBackEnabled: Boolean = true,
        val emojiStyle: EmojiStyle = EmojiStyle.SYSTEM,
        val isAppleEmojiDownloaded: Boolean = false,
        val isTwitterEmojiDownloaded: Boolean = false,
        val isWindowsEmojiDownloaded: Boolean = false,
        val isCatmojiEmojiDownloaded: Boolean = false,
        val isNotoEmojiDownloaded: Boolean = false,
        val isAppleEmojiDownloading: Boolean = false,
        val isTwitterEmojiDownloading: Boolean = false,
        val isWindowsEmojiDownloading: Boolean = false,
        val isCatmojiEmojiDownloading: Boolean = false,
        val isNotoEmojiDownloading: Boolean = false,
        val emojiPackToRemove: EmojiStyle? = null,
        val compressPhotos: Boolean = true,
        val compressVideos: Boolean = true,
        val chatListMessageLines: Int = 1,
        val showChatListPhotos: Boolean = true,
        val isInstalledFromGooglePlay: Boolean = true
    )
}

class DefaultChatSettingsComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit,
    private val onAdBlock: () -> Unit
) : ChatSettingsComponent, AppComponentContext by context {

    private val appPreferences: AppPreferences = container.preferences.appPreferences
    override val downloadUtils: IDownloadUtils = container.utils.downloadUtils()
    private val wallpaperRepository: WallpaperRepository = container.repositories.wallpaperRepository
    private val stickerRepository: StickerRepository = container.repositories.stickerRepository
    private val emojiRepository: EmojiRepository = container.repositories.emojiRepository
    private val distrManager: DistrManager = container.utils.distrManager()
    private val assetsManager: AssetsManager = container.utils.assetsManager()

    private val _state = MutableValue(
        ChatSettingsComponent.State(
            fontSize = appPreferences.fontSize.value,
            letterSpacing = appPreferences.letterSpacing.value,
            bubbleRadius = appPreferences.bubbleRadius.value,
            stickerSize = appPreferences.stickerSize.value,
            wallpaper = appPreferences.wallpaper.value,
            isWallpaperBlurred = appPreferences.isWallpaperBlurred.value,
            wallpaperBlurIntensity = appPreferences.wallpaperBlurIntensity.value,
            isWallpaperMoving = appPreferences.isWallpaperMoving.value,
            wallpaperDimming = appPreferences.wallpaperDimming.value,
            isWallpaperGrayscale = appPreferences.isWallpaperGrayscale.value,
            isPlayerGesturesEnabled = appPreferences.isPlayerGesturesEnabled.value,
            isPlayerDoubleTapSeekEnabled = appPreferences.isPlayerDoubleTapSeekEnabled.value,
            playerSeekDuration = appPreferences.playerSeekDuration.value,
            isPlayerZoomEnabled = appPreferences.isPlayerZoomEnabled.value,
            isArchivePinned = appPreferences.isArchivePinned.value,
            isArchiveAlwaysVisible = appPreferences.isArchiveAlwaysVisible.value,
            showLinkPreviews = appPreferences.showLinkPreviews.value,
            nightMode = appPreferences.nightMode.value,
            isDynamicColorsEnabled = appPreferences.isDynamicColorsEnabled.value,
            isAmoledThemeEnabled = appPreferences.isAmoledThemeEnabled.value,
            isCustomThemeEnabled = appPreferences.isCustomThemeEnabled.value,
            themePrimaryColor = appPreferences.themePrimaryColor.value,
            themeSecondaryColor = appPreferences.themeSecondaryColor.value,
            themeTertiaryColor = appPreferences.themeTertiaryColor.value,
            themeBackgroundColor = appPreferences.themeBackgroundColor.value,
            themeSurfaceColor = appPreferences.themeSurfaceColor.value,
            themePrimaryContainerColor = appPreferences.themePrimaryContainerColor.value,
            themeSecondaryContainerColor = appPreferences.themeSecondaryContainerColor.value,
            themeTertiaryContainerColor = appPreferences.themeTertiaryContainerColor.value,
            themeSurfaceVariantColor = appPreferences.themeSurfaceVariantColor.value,
            themeOutlineColor = appPreferences.themeOutlineColor.value,
            themeDarkPrimaryColor = appPreferences.themeDarkPrimaryColor.value,
            themeDarkSecondaryColor = appPreferences.themeDarkSecondaryColor.value,
            themeDarkTertiaryColor = appPreferences.themeDarkTertiaryColor.value,
            themeDarkBackgroundColor = appPreferences.themeDarkBackgroundColor.value,
            themeDarkSurfaceColor = appPreferences.themeDarkSurfaceColor.value,
            themeDarkPrimaryContainerColor = appPreferences.themeDarkPrimaryContainerColor.value,
            themeDarkSecondaryContainerColor = appPreferences.themeDarkSecondaryContainerColor.value,
            themeDarkTertiaryContainerColor = appPreferences.themeDarkTertiaryContainerColor.value,
            themeDarkSurfaceVariantColor = appPreferences.themeDarkSurfaceVariantColor.value,
            themeDarkOutlineColor = appPreferences.themeDarkOutlineColor.value,
            nightModeStartTime = appPreferences.nightModeStartTime.value,
            nightModeEndTime = appPreferences.nightModeEndTime.value,
            nightModeBrightnessThreshold = appPreferences.nightModeBrightnessThreshold.value,
            isDragToBackEnabled = appPreferences.isDragToBackEnabled.value,
            emojiStyle = appPreferences.emojiStyle.value,
            isAppleEmojiDownloaded = appPreferences.isAppleEmojiDownloaded.value,
            isTwitterEmojiDownloaded = appPreferences.isTwitterEmojiDownloaded.value,
            isWindowsEmojiDownloaded = appPreferences.isWindowsEmojiDownloaded.value,
            isCatmojiEmojiDownloaded = appPreferences.isCatmojiEmojiDownloaded.value,
            isNotoEmojiDownloaded = appPreferences.isNotoEmojiDownloaded.value,
            compressPhotos = appPreferences.compressPhotos.value,
            compressVideos = appPreferences.compressVideos.value,
            chatListMessageLines = appPreferences.chatListMessageLines.value,
            showChatListPhotos = appPreferences.showChatListPhotos.value,
            isInstalledFromGooglePlay = distrManager.isInstalledFromGooglePlay()
        )
    )
    override val state: Value<ChatSettingsComponent.State> = _state
    private val scope = componentScope

    init {
        appPreferences.fontSize
            .onEach { size ->
                _state.update { it.copy(fontSize = size) }
            }
            .launchIn(scope)

        appPreferences.letterSpacing
            .onEach { spacing  ->
                _state.update { it.copy(letterSpacing = spacing) }
            }
            .launchIn(scope)

        appPreferences.bubbleRadius
            .onEach { radius ->
                _state.update { it.copy(bubbleRadius = radius) }
            }
            .launchIn(scope)

        appPreferences.stickerSize
            .onEach { size ->
                _state.update { it.copy(stickerSize = size) }
            }
            .launchIn(scope)

        appPreferences.wallpaper
            .onEach { wallpaper ->
                _state.update { it.copy(wallpaper = wallpaper) }
            }
            .launchIn(scope)

        appPreferences.isWallpaperBlurred
            .onEach { blurred ->
                _state.update { it.copy(isWallpaperBlurred = blurred) }
            }
            .launchIn(scope)

        appPreferences.wallpaperBlurIntensity
            .onEach { intensity ->
                _state.update { it.copy(wallpaperBlurIntensity = intensity) }
            }
            .launchIn(scope)

        appPreferences.isWallpaperMoving
            .onEach { moving ->
                _state.update { it.copy(isWallpaperMoving = moving) }
            }
            .launchIn(scope)

        appPreferences.wallpaperDimming
            .onEach { dimming ->
                _state.update { it.copy(wallpaperDimming = dimming) }
            }
            .launchIn(scope)

        appPreferences.isWallpaperGrayscale
            .onEach { grayscale ->
                _state.update { it.copy(isWallpaperGrayscale = grayscale) }
            }
            .launchIn(scope)

        appPreferences.isPlayerGesturesEnabled
            .onEach { enabled ->
                _state.update { it.copy(isPlayerGesturesEnabled = enabled) }
            }
            .launchIn(scope)

        appPreferences.isPlayerDoubleTapSeekEnabled
            .onEach { enabled ->
                _state.update { it.copy(isPlayerDoubleTapSeekEnabled = enabled) }
            }
            .launchIn(scope)

        appPreferences.playerSeekDuration
            .onEach { duration ->
                _state.update { it.copy(playerSeekDuration = duration) }
            }
            .launchIn(scope)

        appPreferences.isPlayerZoomEnabled
            .onEach { enabled ->
                _state.update { it.copy(isPlayerZoomEnabled = enabled) }
            }
            .launchIn(scope)

        appPreferences.isArchivePinned
            .onEach { pinned ->
                _state.update { it.copy(isArchivePinned = pinned) }
            }
            .launchIn(scope)

        appPreferences.isArchiveAlwaysVisible
            .onEach { enabled ->
                _state.update { it.copy(isArchiveAlwaysVisible = enabled) }
            }
            .launchIn(scope)

        appPreferences.showLinkPreviews
            .onEach { enabled ->
                _state.update { it.copy(showLinkPreviews = enabled) }
            }
            .launchIn(scope)

        appPreferences.nightMode
            .onEach { mode ->
                _state.update { it.copy(nightMode = mode) }
            }
            .launchIn(scope)

        appPreferences.isDynamicColorsEnabled
            .onEach { enabled ->
                _state.update { it.copy(isDynamicColorsEnabled = enabled) }
            }
            .launchIn(scope)

        appPreferences.isAmoledThemeEnabled
            .onEach { enabled ->
                _state.update { it.copy(isAmoledThemeEnabled = enabled) }
            }
            .launchIn(scope)

        appPreferences.isCustomThemeEnabled
            .onEach { enabled ->
                _state.update { it.copy(isCustomThemeEnabled = enabled) }
            }
            .launchIn(scope)

        appPreferences.themePrimaryColor
            .onEach { color ->
                _state.update { it.copy(themePrimaryColor = color) }
            }
            .launchIn(scope)

        appPreferences.themeSecondaryColor
            .onEach { color ->
                _state.update { it.copy(themeSecondaryColor = color) }
            }
            .launchIn(scope)

        appPreferences.themeTertiaryColor
            .onEach { color ->
                _state.update { it.copy(themeTertiaryColor = color) }
            }
            .launchIn(scope)

        appPreferences.themeBackgroundColor
            .onEach { color ->
                _state.update { it.copy(themeBackgroundColor = color) }
            }
            .launchIn(scope)

        appPreferences.themeSurfaceColor
            .onEach { color ->
                _state.update { it.copy(themeSurfaceColor = color) }
            }
            .launchIn(scope)

        appPreferences.themePrimaryContainerColor
            .onEach { color ->
                _state.update { it.copy(themePrimaryContainerColor = color) }
            }
            .launchIn(scope)

        appPreferences.themeSecondaryContainerColor
            .onEach { color ->
                _state.update { it.copy(themeSecondaryContainerColor = color) }
            }
            .launchIn(scope)

        appPreferences.themeTertiaryContainerColor
            .onEach { color ->
                _state.update { it.copy(themeTertiaryContainerColor = color) }
            }
            .launchIn(scope)

        appPreferences.themeSurfaceVariantColor
            .onEach { color ->
                _state.update { it.copy(themeSurfaceVariantColor = color) }
            }
            .launchIn(scope)

        appPreferences.themeOutlineColor
            .onEach { color ->
                _state.update { it.copy(themeOutlineColor = color) }
            }
            .launchIn(scope)

        appPreferences.themeDarkPrimaryColor
            .onEach { color ->
                _state.update { it.copy(themeDarkPrimaryColor = color) }
            }
            .launchIn(scope)

        appPreferences.themeDarkSecondaryColor
            .onEach { color ->
                _state.update { it.copy(themeDarkSecondaryColor = color) }
            }
            .launchIn(scope)

        appPreferences.themeDarkTertiaryColor
            .onEach { color ->
                _state.update { it.copy(themeDarkTertiaryColor = color) }
            }
            .launchIn(scope)

        appPreferences.themeDarkBackgroundColor
            .onEach { color ->
                _state.update { it.copy(themeDarkBackgroundColor = color) }
            }
            .launchIn(scope)

        appPreferences.themeDarkSurfaceColor
            .onEach { color ->
                _state.update { it.copy(themeDarkSurfaceColor = color) }
            }
            .launchIn(scope)

        appPreferences.themeDarkPrimaryContainerColor
            .onEach { color ->
                _state.update { it.copy(themeDarkPrimaryContainerColor = color) }
            }
            .launchIn(scope)

        appPreferences.themeDarkSecondaryContainerColor
            .onEach { color ->
                _state.update { it.copy(themeDarkSecondaryContainerColor = color) }
            }
            .launchIn(scope)

        appPreferences.themeDarkTertiaryContainerColor
            .onEach { color ->
                _state.update { it.copy(themeDarkTertiaryContainerColor = color) }
            }
            .launchIn(scope)

        appPreferences.themeDarkSurfaceVariantColor
            .onEach { color ->
                _state.update { it.copy(themeDarkSurfaceVariantColor = color) }
            }
            .launchIn(scope)

        appPreferences.themeDarkOutlineColor
            .onEach { color ->
                _state.update { it.copy(themeDarkOutlineColor = color) }
            }
            .launchIn(scope)

        appPreferences.nightModeStartTime
            .onEach { time ->
                _state.update { it.copy(nightModeStartTime = time) }
            }
            .launchIn(scope)

        appPreferences.nightModeEndTime
            .onEach { time ->
                _state.update { it.copy(nightModeEndTime = time) }
            }
            .launchIn(scope)

        appPreferences.nightModeBrightnessThreshold
            .onEach { threshold ->
                _state.update { it.copy(nightModeBrightnessThreshold = threshold) }
            }
            .launchIn(scope)

        appPreferences.isDragToBackEnabled
            .onEach { enabled ->
                _state.update { it.copy(isDragToBackEnabled = enabled) }
            }
            .launchIn(scope)

        appPreferences.emojiStyle
            .onEach { style ->
                _state.update { it.copy(emojiStyle = style) }
            }
            .launchIn(scope)

        appPreferences.isAppleEmojiDownloaded
            .onEach { downloaded ->
                _state.update { it.copy(isAppleEmojiDownloaded = downloaded) }
            }
            .launchIn(scope)

        appPreferences.isTwitterEmojiDownloaded
            .onEach { downloaded ->
                _state.update { it.copy(isTwitterEmojiDownloaded = downloaded) }
            }
            .launchIn(scope)

        appPreferences.isWindowsEmojiDownloaded
            .onEach { downloaded ->
                _state.update { it.copy(isWindowsEmojiDownloaded = downloaded) }
            }
            .launchIn(scope)

        appPreferences.isCatmojiEmojiDownloaded
            .onEach { downloaded ->
                _state.update { it.copy(isCatmojiEmojiDownloaded = downloaded) }
            }
            .launchIn(scope)

        appPreferences.isNotoEmojiDownloaded
            .onEach { downloaded ->
                _state.update { it.copy(isNotoEmojiDownloaded = downloaded) }
            }
            .launchIn(scope)

        appPreferences.compressPhotos
            .onEach { enabled ->
                _state.update { it.copy(compressPhotos = enabled) }
            }
            .launchIn(scope)

        appPreferences.compressVideos
            .onEach { enabled ->
                _state.update { it.copy(compressVideos = enabled) }
            }
            .launchIn(scope)

        appPreferences.chatListMessageLines
            .onEach { lines ->
                _state.update { it.copy(chatListMessageLines = lines) }
            }
            .launchIn(scope)

        appPreferences.showChatListPhotos
            .onEach { enabled ->
                _state.update { it.copy(showChatListPhotos = enabled) }
            }
            .launchIn(scope)

        loadWallpapers()
        checkEmojiFiles()
    }

    private fun checkEmojiFiles() {
        val filesDir = assetsManager.getFilesDir()
        val appleFile = File(filesDir, "fonts/apple.ttf")
        val twitterFile = File(filesDir, "fonts/twemoji.ttf")
        val windowsFile = File(filesDir, "fonts/win11.ttf")
        val catmojiFile = File(filesDir, "fonts/catmoji.ttf")
        val notoFile = File(filesDir, "fonts/notoemoji.ttf")

        val isAppleDownloaded = appleFile.exists()
        val isTwitterDownloaded = twitterFile.exists()
        val isWindowsDownloaded = windowsFile.exists()
        val isCatmojiDownloaded = catmojiFile.exists()
        val isNotoDownloaded = notoFile.exists()

        appPreferences.setAppleEmojiDownloaded(isAppleDownloaded)
        appPreferences.setTwitterEmojiDownloaded(isTwitterDownloaded)
        appPreferences.setWindowsEmojiDownloaded(isWindowsDownloaded)
        appPreferences.setCatmojiEmojiDownloaded(isCatmojiDownloaded)
        appPreferences.setNotoEmojiDownloaded(isNotoDownloaded)

        if (appPreferences.emojiStyle.value == EmojiStyle.APPLE && !isAppleDownloaded) {
            appPreferences.setEmojiStyle(EmojiStyle.SYSTEM)
        }
        if (appPreferences.emojiStyle.value == EmojiStyle.TWITTER && !isTwitterDownloaded) {
            appPreferences.setEmojiStyle(EmojiStyle.SYSTEM)
        }
        if (appPreferences.emojiStyle.value == EmojiStyle.WINDOWS && !isWindowsDownloaded) {
            appPreferences.setEmojiStyle(EmojiStyle.SYSTEM)
        }
        if (appPreferences.emojiStyle.value == EmojiStyle.CATMOJI && !isCatmojiDownloaded) {
            appPreferences.setEmojiStyle(EmojiStyle.SYSTEM)
        }
        if (appPreferences.emojiStyle.value == EmojiStyle.NOTO && !isNotoDownloaded) {
            appPreferences.setEmojiStyle(EmojiStyle.SYSTEM)
        }
    }

    private fun loadWallpapers() {
        wallpaperRepository.getWallpapers()
            .onEach { wallpapers ->
                _state.update { it.copy(availableWallpapers = wallpapers) }
            }
            .launchIn(scope)
    }

    override fun onBackClicked() {
        onBack()
    }

    override fun onFontSizeChanged(size: Float) {
        appPreferences.setFontSize(size)
    }

    override fun onLetterSpacingChanged(spacing: Float) {
        appPreferences.setLetterSpacing(spacing)
    }
    
    override fun onBubbleRadiusChanged(radius: Float) {
        appPreferences.setBubbleRadius(radius)
    }

    override fun onStickerSizeChanged(size: Float) {
        appPreferences.setStickerSize(size)
    }

    override fun onWallpaperChanged(wallpaper: String?) {
        appPreferences.setWallpaper(wallpaper)
    }

    override fun onWallpaperSelected(wallpaper: WallpaperModel) {
        _state.update { it.copy(selectedWallpaper = wallpaper) }

        if (!wallpaper.isDownloaded && wallpaper.documentId != 0L) {
            scope.launch {
                wallpaperRepository.downloadWallpaper(wallpaper.documentId.toInt())
            }
        }

        val key = when {
            wallpaper.slug.isNotEmpty() -> wallpaper.slug
            !wallpaper.localPath.isNullOrEmpty() -> wallpaper.localPath
            else -> null
        }

        key?.let { appPreferences.setWallpaper(it) }
    }

    override fun onWallpaperBlurChanged(wallpaper: WallpaperModel, isBlurred: Boolean) {
        _state.update { it.copy(isWallpaperBlurred = isBlurred) }
        appPreferences.setWallpaperBlurred(isBlurred)
    }

    override fun onWallpaperBlurIntensityChanged(intensity: Int) {
        appPreferences.setWallpaperBlurIntensity(intensity)
    }

    override fun onWallpaperMotionChanged(wallpaper: WallpaperModel, isMoving: Boolean) {
        _state.update { it.copy(isWallpaperMoving = isMoving) }
        appPreferences.setWallpaperMoving(isMoving)
    }

    override fun onWallpaperDimmingChanged(dimming: Int) {
        appPreferences.setWallpaperDimming(dimming)
    }

    override fun onWallpaperGrayscaleChanged(isGrayscale: Boolean) {
        appPreferences.setWallpaperGrayscale(isGrayscale)
    }

    override fun onPlayerGesturesEnabledChanged(enabled: Boolean) {
        appPreferences.setPlayerGesturesEnabled(enabled)
    }

    override fun onPlayerDoubleTapSeekEnabledChanged(enabled: Boolean) {
        appPreferences.setPlayerDoubleTapSeekEnabled(enabled)
    }

    override fun onPlayerSeekDurationChanged(duration: Int) {
        appPreferences.setPlayerSeekDuration(duration)
    }

    override fun onPlayerZoomEnabledChanged(enabled: Boolean) {
        appPreferences.setPlayerZoomEnabled(enabled)
    }

    override fun onClearRecentStickers() {
        scope.launch {
            stickerRepository.clearRecentStickers()
        }
    }

    override fun onClearRecentEmojis() {
        scope.launch {
            emojiRepository.clearRecentEmojis()
        }
    }

    override fun onArchivePinnedChanged(pinned: Boolean) {
        appPreferences.setArchivePinned(pinned)
    }

    override fun onArchiveAlwaysVisibleChanged(enabled: Boolean) {
        appPreferences.setArchiveAlwaysVisible(enabled)
    }

    override fun onShowLinkPreviewsChanged(enabled: Boolean) {
        appPreferences.setShowLinkPreviews(enabled)
    }

    override fun onNightModeChanged(mode: NightMode) {
        appPreferences.setNightMode(mode)
    }

    override fun onDynamicColorsChanged(enabled: Boolean) {
        appPreferences.setDynamicColorsEnabled(enabled)
    }

    override fun onAmoledThemeChanged(enabled: Boolean) {
        appPreferences.setAmoledThemeEnabled(enabled)
    }

    override fun onCustomThemeEnabledChanged(enabled: Boolean) {
        appPreferences.setCustomThemeEnabled(enabled)
    }

    override fun onThemePrimaryColorChanged(color: Int) {
        appPreferences.setThemePrimaryColor(color)
    }

    override fun onThemeSecondaryColorChanged(color: Int) {
        appPreferences.setThemeSecondaryColor(color)
    }

    override fun onThemeTertiaryColorChanged(color: Int) {
        appPreferences.setThemeTertiaryColor(color)
    }

    override fun onThemeBackgroundColorChanged(color: Int) {
        appPreferences.setThemeBackgroundColor(color)
    }

    override fun onThemeSurfaceColorChanged(color: Int) {
        appPreferences.setThemeSurfaceColor(color)
    }

    override fun onThemePrimaryContainerColorChanged(color: Int) {
        appPreferences.setThemePrimaryContainerColor(color)
    }

    override fun onThemeSecondaryContainerColorChanged(color: Int) {
        appPreferences.setThemeSecondaryContainerColor(color)
    }

    override fun onThemeTertiaryContainerColorChanged(color: Int) {
        appPreferences.setThemeTertiaryContainerColor(color)
    }

    override fun onThemeSurfaceVariantColorChanged(color: Int) {
        appPreferences.setThemeSurfaceVariantColor(color)
    }

    override fun onThemeOutlineColorChanged(color: Int) {
        appPreferences.setThemeOutlineColor(color)
    }

    override fun onThemeDarkPrimaryColorChanged(color: Int) {
        appPreferences.setThemeDarkPrimaryColor(color)
    }

    override fun onThemeDarkSecondaryColorChanged(color: Int) {
        appPreferences.setThemeDarkSecondaryColor(color)
    }

    override fun onThemeDarkTertiaryColorChanged(color: Int) {
        appPreferences.setThemeDarkTertiaryColor(color)
    }

    override fun onThemeDarkBackgroundColorChanged(color: Int) {
        appPreferences.setThemeDarkBackgroundColor(color)
    }

    override fun onThemeDarkSurfaceColorChanged(color: Int) {
        appPreferences.setThemeDarkSurfaceColor(color)
    }

    override fun onThemeDarkPrimaryContainerColorChanged(color: Int) {
        appPreferences.setThemeDarkPrimaryContainerColor(color)
    }

    override fun onThemeDarkSecondaryContainerColorChanged(color: Int) {
        appPreferences.setThemeDarkSecondaryContainerColor(color)
    }

    override fun onThemeDarkTertiaryContainerColorChanged(color: Int) {
        appPreferences.setThemeDarkTertiaryContainerColor(color)
    }

    override fun onThemeDarkSurfaceVariantColorChanged(color: Int) {
        appPreferences.setThemeDarkSurfaceVariantColor(color)
    }

    override fun onThemeDarkOutlineColorChanged(color: Int) {
        appPreferences.setThemeDarkOutlineColor(color)
    }

    override fun onApplyThemeAccent(color: Int, darkPalette: Boolean) {
        val secondary = shiftHue(color, 18f)
        val tertiary = shiftHue(color, 36f)
        val primaryContainer = shiftSaturationAndValue(color, saturationScale = 0.45f, valueScale = if (darkPalette) 0.65f else 1.15f)
        val secondaryContainer = shiftSaturationAndValue(secondary, saturationScale = 0.45f, valueScale = if (darkPalette) 0.65f else 1.15f)
        val tertiaryContainer = shiftSaturationAndValue(tertiary, saturationScale = 0.45f, valueScale = if (darkPalette) 0.65f else 1.15f)
        if (darkPalette) {
            appPreferences.setThemeDarkPrimaryColor(color)
            appPreferences.setThemeDarkSecondaryColor(secondary)
            appPreferences.setThemeDarkTertiaryColor(tertiary)
            appPreferences.setThemeDarkPrimaryContainerColor(primaryContainer)
            appPreferences.setThemeDarkSecondaryContainerColor(secondaryContainer)
            appPreferences.setThemeDarkTertiaryContainerColor(tertiaryContainer)
        } else {
            appPreferences.setThemePrimaryColor(color)
            appPreferences.setThemeSecondaryColor(secondary)
            appPreferences.setThemeTertiaryColor(tertiary)
            appPreferences.setThemePrimaryContainerColor(primaryContainer)
            appPreferences.setThemeSecondaryContainerColor(secondaryContainer)
            appPreferences.setThemeTertiaryContainerColor(tertiaryContainer)
        }
    }

    override fun exportCustomThemeJson(): String {
        return JSONObject()
            .put(
                "lightPalette",
                JSONObject()
                    .put("primaryColor", state.value.themePrimaryColor)
                    .put("secondaryColor", state.value.themeSecondaryColor)
                    .put("tertiaryColor", state.value.themeTertiaryColor)
                    .put("backgroundColor", state.value.themeBackgroundColor)
                    .put("surfaceColor", state.value.themeSurfaceColor)
                    .put("primaryContainerColor", state.value.themePrimaryContainerColor)
                    .put("secondaryContainerColor", state.value.themeSecondaryContainerColor)
                    .put("tertiaryContainerColor", state.value.themeTertiaryContainerColor)
                    .put("surfaceVariantColor", state.value.themeSurfaceVariantColor)
                    .put("outlineColor", state.value.themeOutlineColor)
            )
            .put(
                "darkPalette",
                JSONObject()
                    .put("primaryColor", state.value.themeDarkPrimaryColor)
                    .put("secondaryColor", state.value.themeDarkSecondaryColor)
                    .put("tertiaryColor", state.value.themeDarkTertiaryColor)
                    .put("backgroundColor", state.value.themeDarkBackgroundColor)
                    .put("surfaceColor", state.value.themeDarkSurfaceColor)
                    .put("primaryContainerColor", state.value.themeDarkPrimaryContainerColor)
                    .put("secondaryContainerColor", state.value.themeDarkSecondaryContainerColor)
                    .put("tertiaryContainerColor", state.value.themeDarkTertiaryContainerColor)
                    .put("surfaceVariantColor", state.value.themeDarkSurfaceVariantColor)
                    .put("outlineColor", state.value.themeDarkOutlineColor)
            )
            .put("amoledEnabled", state.value.isAmoledThemeEnabled)
            .put("customThemeEnabled", state.value.isCustomThemeEnabled)
            .put("dynamicColorsEnabled", state.value.isDynamicColorsEnabled)
            .toString(2)
    }

    override fun importCustomThemeJson(json: String): Boolean {
        return coRunCatching {
            val data = JSONObject(json)
            val light = data.optJSONObject("lightPalette") ?: data
            val dark = data.optJSONObject("darkPalette") ?: data

            val primary = light.getInt("primaryColor")
            val secondary = light.getInt("secondaryColor")
            val tertiary = light.getInt("tertiaryColor")
            val background = light.getInt("backgroundColor")
            val surface = light.getInt("surfaceColor")
            val darkPrimary = dark.optInt("primaryColor", primary)
            val darkSecondary = dark.optInt("secondaryColor", secondary)
            val darkTertiary = dark.optInt("tertiaryColor", tertiary)
            val darkBackground = dark.optInt("backgroundColor", 0xFF121212.toInt())
            val darkSurface = dark.optInt("surfaceColor", 0xFF121212.toInt())
            val primaryContainer = light.optInt("primaryContainerColor", shiftSaturationAndValue(primary, 0.45f, 1.15f))
            val secondaryContainer = light.optInt("secondaryContainerColor", shiftSaturationAndValue(secondary, 0.45f, 1.15f))
            val tertiaryContainer = light.optInt("tertiaryContainerColor", shiftSaturationAndValue(tertiary, 0.45f, 1.15f))
            val surfaceVariant = light.optInt("surfaceVariantColor", 0xFFE1E2EC.toInt())
            val outline = light.optInt("outlineColor", 0xFF757680.toInt())
            val darkPrimaryContainer = dark.optInt("primaryContainerColor", shiftSaturationAndValue(darkPrimary, 0.45f, 0.65f))
            val darkSecondaryContainer = dark.optInt("secondaryContainerColor", shiftSaturationAndValue(darkSecondary, 0.45f, 0.65f))
            val darkTertiaryContainer = dark.optInt("tertiaryContainerColor", shiftSaturationAndValue(darkTertiary, 0.45f, 0.65f))
            val darkSurfaceVariant = dark.optInt("surfaceVariantColor", 0xFF44474F.toInt())
            val darkOutline = dark.optInt("outlineColor", 0xFF8E9099.toInt())
            val amoled = data.optBoolean("amoledEnabled", false)
            val customEnabled = data.optBoolean("customThemeEnabled", true)
            val dynamicEnabled = data.optBoolean("dynamicColorsEnabled", true)

            appPreferences.setThemePrimaryColor(primary)
            appPreferences.setThemeSecondaryColor(secondary)
            appPreferences.setThemeTertiaryColor(tertiary)
            appPreferences.setThemeBackgroundColor(background)
            appPreferences.setThemeSurfaceColor(surface)
            appPreferences.setThemePrimaryContainerColor(primaryContainer)
            appPreferences.setThemeSecondaryContainerColor(secondaryContainer)
            appPreferences.setThemeTertiaryContainerColor(tertiaryContainer)
            appPreferences.setThemeSurfaceVariantColor(surfaceVariant)
            appPreferences.setThemeOutlineColor(outline)
            appPreferences.setThemeDarkPrimaryColor(darkPrimary)
            appPreferences.setThemeDarkSecondaryColor(darkSecondary)
            appPreferences.setThemeDarkTertiaryColor(darkTertiary)
            appPreferences.setThemeDarkBackgroundColor(darkBackground)
            appPreferences.setThemeDarkSurfaceColor(darkSurface)
            appPreferences.setThemeDarkPrimaryContainerColor(darkPrimaryContainer)
            appPreferences.setThemeDarkSecondaryContainerColor(darkSecondaryContainer)
            appPreferences.setThemeDarkTertiaryContainerColor(darkTertiaryContainer)
            appPreferences.setThemeDarkSurfaceVariantColor(darkSurfaceVariant)
            appPreferences.setThemeDarkOutlineColor(darkOutline)
            appPreferences.setAmoledThemeEnabled(amoled)
            appPreferences.setCustomThemeEnabled(customEnabled)
            appPreferences.setDynamicColorsEnabled(dynamicEnabled)
        }.isSuccess
    }

    override fun onNightModeStartTimeChanged(time: String) {
        appPreferences.setNightModeStartTime(time)
    }

    override fun onNightModeEndTimeChanged(time: String) {
        appPreferences.setNightModeEndTime(time)
    }

    override fun onNightModeBrightnessThresholdChanged(threshold: Float) {
        appPreferences.setNightModeBrightnessThreshold(threshold)
    }

    override fun onDragToBackChanged(enabled: Boolean) {
        appPreferences.setDragToBackEnabled(enabled)
    }

    override fun onAdBlockClick() {
        onAdBlock()
    }

    override fun onEmojiStyleChanged(style: EmojiStyle) {
        val isDownloaded = when (style) {
            EmojiStyle.APPLE -> state.value.isAppleEmojiDownloaded
            EmojiStyle.TWITTER -> state.value.isTwitterEmojiDownloaded
            EmojiStyle.WINDOWS -> state.value.isWindowsEmojiDownloaded
            EmojiStyle.CATMOJI -> state.value.isCatmojiEmojiDownloaded
            EmojiStyle.NOTO -> state.value.isNotoEmojiDownloaded
            EmojiStyle.SYSTEM -> true
        }

        if (isDownloaded) {
            appPreferences.setEmojiStyle(style)
        } else {
            downloadEmojiPack(style)
        }
    }

    override fun onEmojiStyleLongClick(style: EmojiStyle) {
        val isDownloaded = when (style) {
            EmojiStyle.APPLE -> state.value.isAppleEmojiDownloaded
            EmojiStyle.TWITTER -> state.value.isTwitterEmojiDownloaded
            EmojiStyle.WINDOWS -> state.value.isWindowsEmojiDownloaded
            EmojiStyle.CATMOJI -> state.value.isCatmojiEmojiDownloaded
            EmojiStyle.NOTO -> state.value.isNotoEmojiDownloaded
            EmojiStyle.SYSTEM -> false
        }
        if (isDownloaded) {
            _state.update { it.copy(emojiPackToRemove = style) }
        }
    }

    override fun onConfirmRemoveEmojiPack() {
        val style = state.value.emojiPackToRemove ?: return
        _state.update { it.copy(emojiPackToRemove = null) }
        removeEmojiPack(style)
    }

    override fun onDismissRemoveEmojiPack() {
        _state.update { it.copy(emojiPackToRemove = null) }
    }

    private fun removeEmojiPack(style: EmojiStyle) {
        val fileName = when (style) {
            EmojiStyle.APPLE -> "apple.ttf"
            EmojiStyle.TWITTER -> "twemoji.ttf"
            EmojiStyle.WINDOWS -> "win11.ttf"
            EmojiStyle.CATMOJI -> "catmoji.ttf"
            EmojiStyle.NOTO -> "notoemoji.ttf"
            else -> return
        }

        scope.launch(Dispatchers.IO) {
            val fontsDir = File(assetsManager.getFilesDir(), "fonts")
            val file = File(fontsDir, fileName)
            if (file.exists()) {
                file.delete()
            }

            withContext(Dispatchers.Main) {
                when (style) {
                    EmojiStyle.APPLE -> appPreferences.setAppleEmojiDownloaded(false)
                    EmojiStyle.TWITTER -> appPreferences.setTwitterEmojiDownloaded(false)
                    EmojiStyle.WINDOWS -> appPreferences.setWindowsEmojiDownloaded(false)
                    EmojiStyle.CATMOJI -> appPreferences.setCatmojiEmojiDownloaded(false)
                    EmojiStyle.NOTO -> appPreferences.setNotoEmojiDownloaded(false)
                    else -> {}
                }
                if (appPreferences.emojiStyle.value == style) {
                    appPreferences.setEmojiStyle(EmojiStyle.SYSTEM)
                }
            }
        }
    }

    private fun downloadEmojiPack(style: EmojiStyle) {
        val url = when (style) {
            EmojiStyle.APPLE -> "https://github.com/monogram-android/emojies/releases/download/1.0/apple.ttf"
            EmojiStyle.TWITTER -> "https://github.com/monogram-android/emojies/releases/download/1.0/twemoji.ttf"
            EmojiStyle.WINDOWS -> "https://github.com/monogram-android/emojies/releases/download/1.0/win11.ttf"
            EmojiStyle.CATMOJI -> "https://github.com/monogram-android/emojies/releases/download/1.0/catmoji.ttf"
            EmojiStyle.NOTO -> "https://github.com/monogram-android/emojies/releases/download/1.0/notoemoji.ttf"
            else -> return
        }
        val fileName = when (style) {
            EmojiStyle.APPLE -> "apple.ttf"
            EmojiStyle.TWITTER -> "twemoji.ttf"
            EmojiStyle.WINDOWS -> "win11.ttf"
            EmojiStyle.CATMOJI -> "catmoji.ttf"
            EmojiStyle.NOTO -> "notoemoji.ttf"
        }

        when (style) {
            EmojiStyle.APPLE -> _state.update { it.copy(isAppleEmojiDownloading = true) }
            EmojiStyle.TWITTER -> _state.update { it.copy(isTwitterEmojiDownloading = true) }
            EmojiStyle.WINDOWS -> _state.update { it.copy(isWindowsEmojiDownloading = true) }
            EmojiStyle.CATMOJI -> _state.update { it.copy(isCatmojiEmojiDownloading = true) }
            EmojiStyle.NOTO -> _state.update { it.copy(isNotoEmojiDownloading = true) }
            else -> {}
        }

        scope.launch(Dispatchers.IO) {
            try {
                val fontsDir = File(assetsManager.getFilesDir(), "fonts")
                if (!fontsDir.exists()) fontsDir.mkdirs()
                val file = File(fontsDir, fileName)

                URL(url).openStream().use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                withContext(Dispatchers.Main) {
                    when (style) {
                        EmojiStyle.APPLE -> {
                            appPreferences.setAppleEmojiDownloaded(true)
                            _state.update { it.copy(isAppleEmojiDownloading = false) }
                        }

                        EmojiStyle.TWITTER -> {
                            appPreferences.setTwitterEmojiDownloaded(true)
                            _state.update { it.copy(isTwitterEmojiDownloading = false) }
                        }

                        EmojiStyle.WINDOWS -> {
                            appPreferences.setWindowsEmojiDownloaded(true)
                            _state.update { it.copy(isWindowsEmojiDownloading = false) }
                        }

                        EmojiStyle.CATMOJI -> {
                            appPreferences.setCatmojiEmojiDownloaded(true)
                            _state.update { it.copy(isCatmojiEmojiDownloading = false) }
                        }

                        EmojiStyle.NOTO -> {
                            appPreferences.setNotoEmojiDownloaded(true)
                            _state.update { it.copy(isNotoEmojiDownloading = false) }
                        }

                        else -> {}
                    }
                    appPreferences.setEmojiStyle(style)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    when (style) {
                        EmojiStyle.APPLE -> _state.update { it.copy(isAppleEmojiDownloading = false) }
                        EmojiStyle.TWITTER -> _state.update { it.copy(isTwitterEmojiDownloading = false) }
                        EmojiStyle.WINDOWS -> _state.update { it.copy(isWindowsEmojiDownloading = false) }
                        EmojiStyle.CATMOJI -> _state.update { it.copy(isCatmojiEmojiDownloading = false) }
                        EmojiStyle.NOTO -> _state.update { it.copy(isNotoEmojiDownloading = false) }
                        else -> {}
                    }
                }
                e.printStackTrace()
            }
        }
    }

    override fun onCompressPhotosChanged(enabled: Boolean) {
        appPreferences.setCompressPhotos(enabled)
    }

    override fun onCompressVideosChanged(enabled: Boolean) {
        appPreferences.setCompressVideos(enabled)
    }

    override fun onChatListMessageLinesChanged(lines: Int) {
        appPreferences.setChatListMessageLines(lines)
    }

    override fun onShowChatListPhotosChanged(enabled: Boolean) {
        appPreferences.setShowChatListPhotos(enabled)
    }

    private fun shiftHue(color: Int, delta: Float): Int {
        val hsv = FloatArray(3)
        colorToHSV(color, hsv)
        hsv[0] = (hsv[0] + delta + 360f) % 360f
        return HSVToColor(hsv)
    }

    private fun shiftSaturationAndValue(color: Int, saturationScale: Float, valueScale: Float): Int {
        val hsv = FloatArray(3)
        colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * saturationScale).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * valueScale).coerceIn(0f, 1f)
        return HSVToColor(hsv)
    }
}
