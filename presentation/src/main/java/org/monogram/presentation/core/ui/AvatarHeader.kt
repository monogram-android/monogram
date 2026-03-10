package org.monogram.presentation.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import org.monogram.presentation.core.util.generateColorFromHash
import org.monogram.presentation.features.chats.currentChat.components.AvatarPlayer
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import java.io.File

@Composable
fun AvatarHeader(
    path: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    fontSize: Int = 64,
    isOnline: Boolean = false,
    avatarCornerPercent: Int = 0,
    videoPlayerPool: VideoPlayerPool
) {
    val combinedModifier = modifier
        .size(size)
        .clip(RoundedCornerShape(percent = avatarCornerPercent))

    Box(modifier = combinedModifier) {
        val avatarFile = path?.let { File(it) }

        if (avatarFile != null && avatarFile.exists()) {
            val isVideo = path.endsWith(".mp4", ignoreCase = true)

            when {
                isVideo && avatarCornerPercent == 0 -> {
                    AvatarPlayer(
                        path = path,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                        videoPlayerPool = videoPlayerPool
                    )
                }

                else -> {
                    Image(
                        painter = rememberAsyncImagePainter(avatarFile),
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        } else {
            PlaceholderAvatar(name, fontSize, generateColorFromHash(name))
        }

        if (isOnline) {
            Box(
                modifier = Modifier
                    .size(size / 4)
                    .align(Alignment.BottomEnd)
                    .background(MaterialTheme.colorScheme.background, CircleShape)
                    .padding(2.dp)
                    .background(Color(0xFF4CAF50), CircleShape)
            )
        }
    }
}
