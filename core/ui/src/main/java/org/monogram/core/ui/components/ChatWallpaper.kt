package org.monogram.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import java.io.File
import org.monogram.core.ui.WallpaperMode
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue

@Composable
fun ChatWallpaper(path: String?, dim: Float, mode: WallpaperMode, modifier: Modifier = Modifier) {
    val dimming by animateFloatAsState(dim.coerceIn(0f, 0.8f), label = "wallpaper-dim")
    Box(modifier.background(MaterialTheme.colorScheme.surface)) {
        Crossfade(targetState = mode to path, animationSpec = tween(220), label = "wallpaper") { (visibleMode, visiblePath) ->
        if (visibleMode == WallpaperMode.Monogram) MonogramWallpaper(Modifier.fillMaxSize())
        if (visibleMode == WallpaperMode.Image && visiblePath != null) {
            AsyncImage(model = File(visiblePath), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimming)))
        }
        }
    }
}
