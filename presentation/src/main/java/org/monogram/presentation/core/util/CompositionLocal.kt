package org.monogram.presentation.core.util

import androidx.compose.runtime.staticCompositionLocalOf
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool

val LocalVideoPlayerPool = staticCompositionLocalOf<VideoPlayerPool> {
    error("VideoPlayerPool not provided")
}