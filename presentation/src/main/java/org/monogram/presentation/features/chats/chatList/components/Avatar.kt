package org.monogram.presentation.features.chats.chatList.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import org.monogram.presentation.core.util.generateColorFromHash
import org.monogram.presentation.features.chats.currentChat.components.AvatarPlayer
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import java.io.File

@Composable
fun AvatarTopAppBar(
    path: String?,
    name: String,
    size: Dp,
    videoPlayerPool: VideoPlayerPool,
    modifier: Modifier = Modifier,
    fontSize: Int = 14,
    isOnline: Boolean = false
) {
    val combinedModifier = modifier
        .size(size)
        .clip(CircleShape)

    Box(modifier = modifier.size(size)) {
        val avatarFile = path?.let { File(it) }
        if (avatarFile != null && avatarFile.exists()) {
            if (path.endsWith(".mp4", ignoreCase = true)) {
                AvatarPlayer(
                    path = path,
                    modifier = combinedModifier,
                    contentScale = ContentScale.Crop,
                    videoPlayerPool = videoPlayerPool
                )
            } else {
                Image(
                    painter = rememberAsyncImagePainter(avatarFile),
                    contentDescription = null,
                    modifier = combinedModifier,
                    contentScale = ContentScale.Crop
                )
            }
        } else {
            Box(
                modifier = combinedModifier.background(generateColorFromHash(name).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = fontSize.sp),
                    color = generateColorFromHash(name)
                )
            }
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