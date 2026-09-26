package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.feature.dialog.R
import java.io.File

@Composable
internal fun VideoThumb(
    thumb: File?,
    image: File?,
    durationSeconds: Int?,
    stripped: ByteArray? = null,
    failed: Boolean,
    loading: Boolean,
    previewLoading: Boolean = false,
    downloadedBytes: Long = 0L,
    fileSize: Long? = null,
    onPlay: () -> Unit,
    compact: Boolean = false,
    /** Caption-less media overlays the clock bottom-right, so move the duration aside. */
    durationAtStart: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val playSize = if (compact) 36.dp else 48.dp
    val iconSize = if (compact) 22.dp else 32.dp
    val stillThumb = thumb?.takeIf { stillImageFile(it) }
    val sharpStill = image?.takeIf { stillImageFile(it) }
    Box(
        modifier = modifier.then(MediaTapModifier(onTap = onPlay, onLongPress = onLongPress)),
        contentAlignment = Alignment.Center,
    ) {
        ProgressiveStill(
            thumb = stillThumb,
            image = sharpStill,
            stripped = stripped,
            contentDescription = stringResource(R.string.dialog_media_video),
            contentScale = ContentScale.Crop,
            failed = failed && stillThumb == null && sharpStill == null,
            failedText = stringResource(R.string.dialog_media_failed),
            loading = previewLoading && !loading,
            modifier = Modifier.fillMaxSize(),
        )
        when {
            loading -> DownloadCancelBadge(
                bytes = downloadedBytes,
                total = fileSize,
                modifier = Modifier.size(playSize),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
                scrim = Color.Black.copy(alpha = 0.28f),
            )
            !previewLoading -> Box(
                modifier = Modifier
                    .size(playSize)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.dialog_media_play),
                    tint = Color.White,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        if (durationSeconds != null && durationSeconds > 0) {
            Text(
                text = formatMediaDuration(durationSeconds),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(if (durationAtStart) Alignment.BottomStart else Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
