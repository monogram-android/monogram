package org.monogram.presentation.features.chats.currentChat.chatContent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.monogram.presentation.features.chats.currentChat.ChatComponent
import org.monogram.presentation.settings.chatSettings.components.WallpaperBackground

import java.io.File

@Composable
fun ChatContentBackground(
    state: ChatComponent.State,
    modifier: Modifier = Modifier
) {
    val wallpaper = state.wallpaperModel
    if (wallpaper != null) {
        WallpaperBackground(
            wallpaper = wallpaper,
            modifier = modifier.fillMaxSize(),
            isBlurred = state.isWallpaperBlurred,
            isMoving = state.isWallpaperMoving,
            blurIntensity = state.wallpaperBlurIntensity,
            dimming = state.wallpaperDimming,
            isGrayscale = state.isWallpaperGrayscale
        )
    } else if (state.wallpaper != null) {
        if (File(state.wallpaper!!).exists()) {
            var imageModifier = Modifier.fillMaxSize()
            if (state.isWallpaperBlurred && state.wallpaperBlurIntensity > 0) {
                imageModifier = imageModifier.blur((state.wallpaperBlurIntensity / 4f).dp)
            }
            AsyncImage(
                model = File(state.wallpaper!!),
                contentDescription = null,
                modifier = imageModifier,
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
            )
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        )
    }
}
