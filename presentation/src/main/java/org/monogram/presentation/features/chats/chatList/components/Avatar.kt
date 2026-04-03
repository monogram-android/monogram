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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import org.monogram.presentation.core.util.generateColorFromHash
import org.monogram.presentation.features.chats.currentChat.components.AvatarPlayer
import java.io.File

@Composable
fun AvatarTopAppBar(
    path: String?,
    fallbackPath: String? = null,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    fontSize: Int = 14,
    isOnline: Boolean = false
) {
    val context = LocalContext.current
    val combinedModifier = modifier
        .size(size)
        .clip(CircleShape)

    Box(modifier = modifier.size(size)) {
        val resolvedPath = resolveAvatarPath(path, fallbackPath)
        val avatarFile = resolvedPath?.let { File(it) }
        if (avatarFile != null && avatarFile.exists()) {
            val avatarVersion = "${avatarFile.absolutePath}:${avatarFile.lastModified()}:${avatarFile.length()}"
            if (resolvedPath.endsWith(".mp4", ignoreCase = true)) {
                key(avatarVersion) {
                    AvatarPlayer(
                        path = resolvedPath,
                        modifier = combinedModifier,
                        contentScale = ContentScale.Crop
                    )
                }
            } else {
                Image(
                    painter = rememberAsyncImagePainter(
                        model = ImageRequest.Builder(context)
                            .data(avatarFile)
                            .memoryCacheKey(avatarVersion)
                            .diskCacheKey(avatarVersion)
                            .build()
                    ),
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

private fun resolveAvatarPath(primaryPath: String?, fallbackPath: String?): String? {
    val candidates = listOfNotNull(primaryPath?.takeIf { it.isNotBlank() }, fallbackPath?.takeIf { it.isNotBlank() })
        .distinct()
    if (candidates.isEmpty()) return null

    val existingCandidates = candidates.filter { File(it).exists() }
    val source = if (existingCandidates.isNotEmpty()) existingCandidates else candidates

    return source.firstOrNull { it.endsWith(".mp4", ignoreCase = true) } ?: source.firstOrNull()
}
