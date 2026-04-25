package org.monogram.presentation.core.util

import androidx.compose.runtime.staticCompositionLocalOf
import org.monogram.presentation.core.media.VideoPlayerPool

val LocalVideoPlayerPool = staticCompositionLocalOf<VideoPlayerPool> {
    error("VideoPlayerPool not provided")
}

val LocalTabletInterfaceEnabled = staticCompositionLocalOf { true }
