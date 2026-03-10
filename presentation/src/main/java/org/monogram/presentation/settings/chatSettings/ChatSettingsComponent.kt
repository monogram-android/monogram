package org.monogram.presentation.settings.chatSettings

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.domain.managers.AssetsManager
import org.monogram.domain.managers.DistrManager
import org.monogram.domain.models.WallpaperModel
import org.monogram.domain.repository.SettingsRepository
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.core.util.*
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.root.AppComponentContext
import java.io.File
import java.net.URL

interface ChatSettingsComponent {
    val state: Value<State>
    val downloadUtils: IDownloadUtils
    val videoPlayerPool: VideoPlayerPool
    fun onBackClicked()
    fun onFontSizeChanged(size: Float)
    fun onBubbleRadiusChanged(radius: Float)
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
        val bubbleRadius: Float = 18f,
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
    private val settingsRepository: SettingsRepository = container.repositories.settingsRepository
    private val stickerRepository: StickerRepository = container.repositories.stickerRepository
    override val videoPlayerPool: VideoPlayerPool = container.utils.videoPlayerPool
    private val distrManager: DistrManager = container.utils.distrManager()
    private val assetsManager: AssetsManager = container.utils.assetsManager()

    private val _state = MutableValue(
        ChatSettingsComponent.State(
            fontSize = appPreferences.fontSize.value,
            bubbleRadius = appPreferences.bubbleRadius.value,
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

        appPreferences.bubbleRadius
            .onEach { radius ->
                _state.update { it.copy(bubbleRadius = radius) }
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
        settingsRepository.getWallpapers()
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

    override fun onBubbleRadiusChanged(radius: Float) {
        appPreferences.setBubbleRadius(radius)
    }

    override fun onWallpaperChanged(wallpaper: String?) {
        appPreferences.setWallpaper(wallpaper)
    }

    override fun onWallpaperSelected(wallpaper: WallpaperModel) {
        _state.update { it.copy(selectedWallpaper = wallpaper) }

        if (!wallpaper.isDownloaded && wallpaper.documentId != 0L) {
            scope.launch {
                settingsRepository.downloadWallpaper(wallpaper.documentId.toInt())
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
            stickerRepository.clearRecentEmojis()
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
}
