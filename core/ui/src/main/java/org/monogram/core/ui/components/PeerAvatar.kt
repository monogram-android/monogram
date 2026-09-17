package org.monogram.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.monogram.core.ui.mp4File
import org.monogram.core.ui.webmFile
import java.io.File

/**
 * Up to two initials for a letter avatar: the first letter of the first two words.
 * Titles whose words carry no letters or digits (emoji-only names) fall back to `?`.
 */
fun peerInitials(title: String): String {
    val letters = title.trim()
        .split(' ', '\t', '\n', '_', '-', ',', '.')
        .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
    return when {
        letters.size >= 2 -> "${letters[0].uppercaseChar()}${letters[1].uppercaseChar()}"
        letters.size == 1 -> letters[0].uppercaseChar().toString()
        else -> "?"
    }
}

/** Circular placeholder for service chats (Saved Messages, archive) instead of initials. */
@Composable
fun ServiceAvatar(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Composable
fun PeerAvatar(
    title: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    imageFile: File? = null,
) {
    val initial = remember(title) { peerInitials(title) }
    val avatarBg = remember(title) { org.monogram.core.ui.ColorUtils.generateColorFromHash(title) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarBg.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = if (size >= 80.dp) 34.sp else (size.value * 0.36f).sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            ),
            color = avatarBg,
        )
        val file = imageFile?.takeIf { it.exists() && it.length() > 0L }
        if (file != null) {
            if (webmFile(file) || mp4File(file)) {
                LoopingVideo(
                    file = file,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    crop = true,
                )
            } else {
                val context = LocalContext.current
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(file)
                        .memoryCacheKey("${file.absolutePath}:${file.length()}")
                        .diskCacheKey("${file.absolutePath}:${file.length()}")
                        .crossfade(false)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}
