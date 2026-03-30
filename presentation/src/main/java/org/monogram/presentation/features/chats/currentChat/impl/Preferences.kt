package org.monogram.presentation.features.chats.currentChat.impl


import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.monogram.domain.models.WallpaperModel
import org.monogram.presentation.features.chats.currentChat.DefaultChatComponent

internal fun DefaultChatComponent.observePreferences(availableWallpapers: List<WallpaperModel>) {
    appPreferences.fontSize
        .onEach { size ->
            _state.update { it.copy(fontSize = size) }
        }
        .launchIn(scope)
    
    appPreferences.letterSpacing
        .onEach { spacing ->
            _state.update { it.copy(letterSpacing = spacing) }
        }
        .launchIn(scope)

    appPreferences.bubbleRadius
        .onEach { radius ->
            _state.update { it.copy(bubbleRadius = radius) }
        }
        .launchIn(scope)

    val firstThree = combine(
        appPreferences.wallpaper,
        appPreferences.isWallpaperBlurred,
        appPreferences.wallpaperBlurIntensity
    ) { wallpaper, blurred, intensity ->
        Triple(wallpaper, blurred, intensity)
    }

    val lastThree = combine(
        appPreferences.isWallpaperMoving,
        appPreferences.wallpaperDimming,
        appPreferences.isWallpaperGrayscale
    ) { moving, dimming, grayscale ->
        Triple(moving, dimming, grayscale)
    }

    combine(firstThree, lastThree) { first, last ->
        val (wallpaper, blurred, intensity) = first
        val (moving, dimming, grayscale) = last
        val model = if (wallpaper != null) {
            availableWallpapers.find { it.slug == wallpaper || it.localPath == wallpaper }
        } else {
            null
        }

        _state.update { currentState ->
            currentState.copy(
                wallpaper = wallpaper,
                wallpaperModel = model,
                isWallpaperBlurred = blurred,
                wallpaperBlurIntensity = intensity,
                isWallpaperMoving = moving,
                wallpaperDimming = dimming,
                isWallpaperGrayscale = grayscale
            )
        }
    }.launchIn(scope)

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

    appPreferences.autoDownloadMobile.onEach { value ->
        _state.update { it.copy(autoDownloadMobile = value) }
    }.launchIn(scope)

    appPreferences.autoDownloadWifi.onEach { value ->
        _state.update { it.copy(autoDownloadWifi = value) }
    }.launchIn(scope)

    appPreferences.autoDownloadRoaming.onEach { value ->
        _state.update { it.copy(autoDownloadRoaming = value) }
    }.launchIn(scope)

    appPreferences.autoDownloadFiles.onEach { value ->
        _state.update { it.copy(autoDownloadFiles = value) }
    }.launchIn(scope)

    appPreferences.autoplayGifs.onEach { value ->
        _state.update { it.copy(autoplayGifs = value) }
    }.launchIn(scope)

    appPreferences.autoplayVideos.onEach { value ->
        _state.update { it.copy(autoplayVideos = value) }
    }.launchIn(scope)

    appPreferences.showLinkPreviews.onEach { value ->
        _state.update { it.copy(showLinkPreviews = value) }
    }.launchIn(scope)

    combine(appPreferences.isChatAnimationsEnabled, appPreferences.isPowerSavingMode) { enabled, powerSaving ->
        if (powerSaving) false else enabled
    }.onEach { value ->
        _state.update { it.copy(isChatAnimationsEnabled = value) }
    }.launchIn(scope)

    combine(appPreferences.isAdBlockEnabled, appPreferences.adBlockKeywords) { enabled, keywords ->
        enabled to keywords
    }.onEach {
        if (_state.value.isChannel) {
            loadMessages()
        }
    }.launchIn(scope)
}

internal fun DefaultChatComponent.loadWallpapers(onLoaded: (List<WallpaperModel>) -> Unit) {
    settingsRepository.getWallpapers()
        .onEach { wallpapers ->
            onLoaded(wallpapers)
            val currentWallpaper = appPreferences.wallpaper.value
            val model = if (currentWallpaper != null) {
                wallpapers.find { it.slug == currentWallpaper || it.localPath == currentWallpaper }
            } else {
                null
            }
            _state.update { it.copy(wallpaperModel = model) }
        }
        .launchIn(scope)
}
