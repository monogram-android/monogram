package org.monogram.core.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.monogram.core.ui.gzipFile
import org.monogram.core.ui.mp4File
import org.monogram.core.ui.webmFile
import java.io.File

/** Custom-emoji status glyph. Missing or non-image files draw nothing. */
@Composable
fun EmojiStatusMark(
    file: File?,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    val local = file?.takeIf { it.exists() && it.length() > 0L } ?: return
    when {
        gzipFile(local) -> {
            val bytes = remember(local) { runCatching { local.readBytes() }.getOrDefault(ByteArray(0)) }
            if (bytes.isNotEmpty()) {
                LottieLoop(bytes = bytes, size = size, modifier = modifier)
            }
        }
        webmFile(local) || mp4File(local) -> {
            LoopingVideo(
                file = local,
                modifier = modifier.size(size),
                crop = false,
            )
        }
        else -> {
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(local)
                    .memoryCacheKey("${local.absolutePath}:${local.length()}")
                    .diskCacheKey("${local.absolutePath}:${local.length()}")
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = modifier.size(size),
            )
        }
    }
}
