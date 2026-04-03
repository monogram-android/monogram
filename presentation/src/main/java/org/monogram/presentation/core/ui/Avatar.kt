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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.monogram.presentation.R
import org.monogram.presentation.core.util.generateColorFromHash
import org.monogram.presentation.features.chats.currentChat.components.AvatarPlayer
import java.io.File

@Composable
fun Avatar(
    path: String?,
    fallbackPath: String? = null,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    fontSize: Int = 14,
    isOnline: Boolean = false,
    isLocal: Boolean = false,
    onClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val combinedModifier = modifier
        .size(size)
        .clip(CircleShape)
        .clickable { onClick() }

    val resolvedPath = resolveAvatarPath(path, fallbackPath)
    val imageSource = resolvedPath?.let(::resolveAvatarImageSource)

    Box(modifier = modifier.size(size)) {
        val placeholder = @Composable {
            PlaceholderAvatar(
                name = name,
                fontSize = fontSize,
                color = generateColorFromHash(name),
                modifier = Modifier.fillMaxSize()
            )
        }

        if (resolvedPath != null) {
            if (isLocal) {
                AsyncImage(
                    model = R.raw.konata,
                    contentDescription = null,
                    modifier = combinedModifier,
                    contentScale = ContentScale.Crop,
                )
            } else {
                val isVideo = remember(resolvedPath) { resolvedPath.endsWith(".mp4", ignoreCase = true) }
                if (isVideo) {
                    key(imageSource?.cacheKey ?: resolvedPath) {
                        AvatarPlayer(
                            path = resolvedPath,
                            modifier = combinedModifier,
                            contentScale = ContentScale.Crop
                        )
                    }
                } else {
                    Box(modifier = combinedModifier) {
                        placeholder()
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(imageSource?.model ?: resolvedPath)
                                .apply {
                                    imageSource?.cacheKey?.let {
                                        memoryCacheKey(it)
                                        diskCacheKey(it)
                                    }
                                }
                                .build(),
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
    fallbackPath: String? = null,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    fontSize: Int = 14,
    isOnline: Boolean = false,
    isLocal: Boolean = false
) {
    val context = LocalContext.current
    val combinedModifier = modifier
        .size(size)
        .clip(CircleShape)

    val resolvedPath = resolveAvatarPath(path, fallbackPath)
    val imageSource = resolvedPath?.let(::resolveAvatarImageSource)

    Box(modifier = modifier.size(size)) {
        val placeholder = @Composable {
            PlaceholderAvatar(
                name = name,
                fontSize = fontSize,
                color = generateColorFromHash(name),
                modifier = Modifier.fillMaxSize()
            )
        }

        if (resolvedPath != null) {
            if (isLocal) {
                AsyncImage(
                    model = R.raw.konata,
                    contentDescription = null,
                    modifier = combinedModifier,
                    contentScale = ContentScale.Crop
                )
            } else {
                val isVideo = remember(resolvedPath) { resolvedPath.endsWith(".mp4", ignoreCase = true) }
                if (isVideo) {
                    key(imageSource?.cacheKey ?: resolvedPath) {
                        AvatarPlayer(
                            path = resolvedPath,
                            modifier = combinedModifier,
                            contentScale = ContentScale.Crop
                        )
                    }
                } else {
                    Box(modifier = combinedModifier) {
                        placeholder()
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(imageSource?.model ?: resolvedPath)
                                .apply {
                                    imageSource?.cacheKey?.let {
                                        memoryCacheKey(it)
                                        diskCacheKey(it)
                                    }
                                }
                                .build(),
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

private data class AvatarImageSource(
    val model: Any,
    val cacheKey: String?
)

private fun resolveAvatarImageSource(path: String): AvatarImageSource {
    return if (path.startsWith("http") || path.startsWith("content:") || path.startsWith("file:")) {
        AvatarImageSource(model = path, cacheKey = path)
    } else {
        val file = File(path)
        if (file.exists()) {
            AvatarImageSource(
                model = file,
                cacheKey = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
            )
        } else {
            AvatarImageSource(model = path, cacheKey = path)
        }
    }
}

private fun resolveAvatarPath(primaryPath: String?, fallbackPath: String?): String? {
    val candidates = listOfNotNull(primaryPath?.takeIf { it.isNotBlank() }, fallbackPath?.takeIf { it.isNotBlank() })
        .distinct()
    if (candidates.isEmpty()) return null

    val existingCandidates = candidates.filter { File(it).exists() }
    val source = existingCandidates.ifEmpty { candidates }

    return source.firstOrNull { it.endsWith(".mp4", ignoreCase = true) } ?: source.firstOrNull()
}
