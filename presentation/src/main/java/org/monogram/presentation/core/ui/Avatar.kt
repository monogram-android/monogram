package org.monogram.presentation.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.monogram.presentation.R
import org.monogram.presentation.core.util.generateColorFromHash
import org.monogram.presentation.features.chats.currentChat.components.AvatarPlayer
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import java.io.File

@Composable
fun Avatar(
    path: String?,
    name: String,
    size: Dp,
    videoPlayerPool: VideoPlayerPool,
    modifier: Modifier = Modifier,
    fontSize: Int = 14,
    isOnline: Boolean = false,
    isLocal: Boolean = false,
    onClick: () -> Unit = {}
) {
    val combinedModifier = modifier
        .size(size)
        .clip(CircleShape)
        .clickable { onClick() }

    Box(modifier = modifier.size(size)) {
        val placeholder = @Composable {
            PlaceholderAvatar(
                name = name,
                fontSize = fontSize,
                color = generateColorFromHash(name),
                modifier = Modifier.fillMaxSize()
            )
        }

        if (path != null) {
            if (isLocal) {
                AsyncImage(
                    model = R.raw.konata,
                    contentDescription = null,
                    modifier = combinedModifier,
                    contentScale = ContentScale.Crop,
                )
            } else {
                val isVideo = remember(path) { path.endsWith(".mp4", ignoreCase = true) }
                if (isVideo) {
                    AvatarPlayer(
                        path = path,
                        modifier = combinedModifier,
                        contentScale = ContentScale.Crop,
                        videoPlayerPool = videoPlayerPool
                    )
                } else {
                    Box(modifier = combinedModifier) {
                        placeholder()
                        AsyncImage(
                            model = remember(path) { File(path) },
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        } else {
            Box(modifier = combinedModifier) {
                placeholder()
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

@Composable
fun AvatarForChat(
    path: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    fontSize: Int = 14,
    isOnline: Boolean = false,
    isLocal: Boolean = false,
    videoPlayerPool: VideoPlayerPool
) {
    val combinedModifier = modifier
        .size(size)
        .clip(CircleShape)

    Box(modifier = modifier.size(size)) {
        val placeholder = @Composable {
            PlaceholderAvatar(
                name = name,
                fontSize = fontSize,
                color = generateColorFromHash(name),
                modifier = Modifier.fillMaxSize()
            )
        }

        if (path != null) {
            if (isLocal) {
                AsyncImage(
                    model = R.raw.konata,
                    contentDescription = null,
                    modifier = combinedModifier,
                    contentScale = ContentScale.Crop
                )
            } else {
                val isVideo = remember(path) { path.endsWith(".mp4", ignoreCase = true) }
                if (isVideo) {
                    AvatarPlayer(
                        path = path,
                        modifier = combinedModifier,
                        contentScale = ContentScale.Crop,
                        videoPlayerPool = videoPlayerPool
                    )
                } else {
                    Box(modifier = combinedModifier) {
                        placeholder()
                        AsyncImage(
                            model = remember(path) { File(path) },
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        } else {
            Box(modifier = combinedModifier) {
                placeholder()
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
