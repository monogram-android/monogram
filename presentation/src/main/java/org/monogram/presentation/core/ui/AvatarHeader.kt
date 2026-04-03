package org.monogram.presentation.core.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Size
import org.monogram.presentation.core.util.generateColorFromHash
import org.monogram.presentation.features.chats.currentChat.components.AvatarPlayer
import java.io.File

@Composable
fun AvatarHeader(
    path: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    fallbackPath: String? = null,
    fontSize: Int = 64,
    isOnline: Boolean = false,
    avatarCornerPercent: Int = 0
) {
    val context = LocalContext.current
    val combinedModifier = modifier
        .size(size)
        .clip(RoundedCornerShape(percent = avatarCornerPercent))

    val resolvedPath = resolveAvatarPath(path, fallbackPath)

    Box(modifier = combinedModifier) {
        val avatarFile = resolvedPath?.let { File(it) }

        if (avatarFile != null && avatarFile.exists()) {
            val isVideo = resolvedPath.endsWith(".mp4", ignoreCase = true)

            when {
                isVideo -> {
                    AvatarPlayer(
                        path = resolvedPath,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                else -> {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(avatarFile)
                            .size(Size.ORIGINAL)
                            .precision(Precision.EXACT)
                            .crossfade(true)
                            .build(),
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

private fun resolveAvatarPath(primaryPath: String?, fallbackPath: String?): String? {
    val candidates = listOfNotNull(primaryPath?.takeIf { it.isNotBlank() }, fallbackPath?.takeIf { it.isNotBlank() })
        .distinct()
    if (candidates.isEmpty()) return null

    val existingCandidates = candidates.filter { File(it).exists() }
    val source = existingCandidates.ifEmpty { candidates }

    return source.firstOrNull { it.endsWith(".mp4", ignoreCase = true) } ?: source.firstOrNull()
}
