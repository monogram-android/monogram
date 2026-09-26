package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.monogram.core.models.WebpagePreview
import org.monogram.feature.dialog.R
import java.io.File

@Composable
internal fun WebpagePreviewCard(
    preview: WebpagePreview?,
    thumb: File?,
    image: File?,
    loading: Boolean = false,
    onInstantView: ((WebpagePreview) -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val url = preview?.url
    val openInstantView = preview?.offersInstantView == true && onInstantView != null
    val onOpen = {
        val ivPreview = preview
        if (openInstantView && ivPreview != null && onInstantView != null) {
            onInstantView(ivPreview)
        } else if (!url.isNullOrBlank()) {
            runCatching { uriHandler.openUri(url) }
        }
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(
                if (openInstantView || !url.isNullOrBlank()) {
                    MediaTapModifier(onTap = onOpen, onLongPress = onLongPress)
                } else {
                    Modifier
                },
            ),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
            Column(Modifier.weight(1f)) {
                preview?.siteName?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                val stillThumb = thumb?.takeIf { stillImageFile(it) }
                val sharpStill = image?.takeIf { stillImageFile(it) }
                if (stillThumb != null || sharpStill != null || loading) {
                    Box(
                        modifier = Modifier
                            .padding(start = 10.dp, end = 12.dp, top = 8.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .height(148.dp),
                    ) {
                        ProgressiveStill(
                            thumb = stillThumb,
                            image = sharpStill,
                            contentDescription = preview?.title ?: url,
                            contentScale = ContentScale.Crop,
                            failed = false,
                            failedText = "",
                            loading = loading && preview?.type != "video",
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (preview?.type == "video") {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(44.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(R.string.dialog_reply_video),
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                    }
                }
                Column(Modifier.padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
                    preview?.title?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 3,
                        )
                    }
                    preview?.description?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                        )
                    }
                    if (
                        preview?.siteName.isNullOrBlank() &&
                        preview?.title.isNullOrBlank() &&
                        preview?.description.isNullOrBlank() &&
                        !url.isNullOrBlank()
                    ) {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
                if (openInstantView) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.dialog_instant_view_open),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
