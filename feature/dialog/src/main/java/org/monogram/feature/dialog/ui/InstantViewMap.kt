package org.monogram.feature.dialog.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.models.InstantViewBlock
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaRepository

@Composable
internal fun InstantViewMap(
    block: InstantViewBlock.Map,
    scale: Float,
    query: String,
    mediaRepository: MediaRepository?,
    onOpenUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val ratio = if (block.width > 0 && block.height > 0) {
        block.width.toFloat() / block.height
    } else {
        16f / 9f
    }
    val open = {
        val uri = android.net.Uri.parse("geo:${block.latitude},${block.longitude}?z=${block.zoom}")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        Unit
    }
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio.coerceIn(0.8f, 2.2f))
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = open),
            contentAlignment = Alignment.Center,
        ) {
            val mapCache = block.cacheKey
            if (!mapCache.isNullOrBlank()) {
                InstantViewPhoto(
                    cacheKey = mapCache,
                    width = block.width,
                    height = block.height,
                    mediaRepository = mediaRepository,
                    onClick = open,
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Place,
                        contentDescription = stringResource(R.string.dialog_instant_view_map),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "${block.latitude}, ${block.longitude}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Button(
                onClick = open,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            ) {
                Text(stringResource(R.string.dialog_instant_view_map))
            }
        }
        block.caption?.let { InstantViewCaption(it, scale, query, onOpenUrl) }
    }
}
