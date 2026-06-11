package org.monogram.presentation.features.chats.conversation.ui.message

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.monogram.domain.models.WebPage
import org.monogram.presentation.R

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LinkPreview(
    webPage: WebPage,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier,
    onAction: (LinkPreviewAction) -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val resolved = remember(webPage) { webPage.resolveLinkPreview() }
    val hasTitle = !resolved.meta.title.isNullOrBlank()
    val hasDescription = !resolved.meta.description.isNullOrBlank()
    val hasKicker = resolved.meta.kicker.isNotBlank()

    if (!hasKicker && !hasTitle && !hasDescription && !resolved.hasMedia) return

    val colorScheme = MaterialTheme.colorScheme

    val borderColor = if (isOutgoing) {
        colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
    } else {
        colorScheme.primary.copy(alpha = 0.4f)
    }

    Column(
        modifier = modifier
            .padding(vertical = 4.dp)
            .widthIn(max = 300.dp)
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isOutgoing) colorScheme.onPrimaryContainer.copy(alpha = 0.05f)
                    else colorScheme.onSurface.copy(alpha = 0.05f)
                )
        ) {
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(borderColor)
                )
                Column(modifier = Modifier.padding(8.dp)) {
                    if (resolved.isSmallMedia) {
                        Row {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .previewTapTarget(
                                        onTap = { onAction(resolved.primaryAction) },
                                        onLongClick = onLongClick
                                    )
                            ) {
                                LinkPreviewTextContent(
                                    meta = resolved.meta,
                                    isOutgoing = isOutgoing
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            LinkPreviewSmallImage(
                                thumbnailData = resolved.thumbnailData,
                                thumbnailCacheKey = resolved.thumbnailCacheKey,
                                onTap = { onAction(resolved.mediaAction) },
                                onLongClick = onLongClick
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.previewTapTarget(
                                onTap = { onAction(resolved.primaryAction) },
                                onLongClick = onLongClick
                            )
                        ) {
                            LinkPreviewTextContent(
                                meta = resolved.meta,
                                isOutgoing = isOutgoing
                            )
                        }

                        if (resolved.hasMedia) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinkPreviewLargeMedia(
                                thumbnailData = resolved.thumbnailData,
                                thumbnailCacheKey = resolved.thumbnailCacheKey,
                                aspectRatio = resolved.aspectRatio,
                                showPlayOverlay = resolved.showPlayOverlay,
                                duration = webPage.duration,
                                onTap = { onAction(resolved.mediaAction) },
                                onLongClick = onLongClick
                            )
                        }
                    }
                }
            }
        }

        if (resolved.showInstantViewButton) {
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = { onAction(resolved.primaryAction) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isOutgoing) colorScheme.onPrimaryContainer.copy(alpha = 0.1f)
                    else colorScheme.primary.copy(alpha = 0.1f),
                    contentColor = if (isOutgoing) colorScheme.onPrimaryContainer else colorScheme.primary
                ),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Notes,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "INSTANT VIEW",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun LinkPreviewTextContent(
    meta: LinkPreviewMeta,
    isOutgoing: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme

    if (meta.kicker.isNotBlank()) {
        Text(
            text = meta.kicker,
            style = MaterialTheme.typography.labelMedium,
            color = if (isOutgoing) colorScheme.onPrimaryContainer else colorScheme.primary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    if (!meta.title.isNullOrBlank()) {
        Text(
            text = meta.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (meta.kicker.isNotBlank()) 2.dp else 0.dp)
        )
    }

    if (!meta.description.isNullOrBlank()) {
        Text(
            text = meta.description,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (meta.kicker.isNotBlank() || !meta.title.isNullOrBlank()) 2.dp else 0.dp)
        )
    }
}

@Composable
private fun LinkPreviewSmallImage(
    thumbnailData: Any?,
    thumbnailCacheKey: String?,
    onTap: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .previewTapTarget(onTap = onTap, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(thumbnailData)
                .apply {
                    thumbnailCacheKey?.let {
                        memoryCacheKey(it)
                        diskCacheKey(it)
                    }
                }
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun LinkPreviewLargeMedia(
    thumbnailData: Any?,
    thumbnailCacheKey: String?,
    aspectRatio: Float,
    showPlayOverlay: Boolean,
    duration: Int,
    onTap: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .previewTapTarget(onTap = onTap, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(thumbnailData)
                .apply {
                    thumbnailCacheKey?.let {
                        memoryCacheKey(it)
                        diskCacheKey(it)
                    }
                }
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        if (showPlayOverlay) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.action_play),
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            if (duration > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = formatDuration(duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

private fun Modifier.previewTapTarget(
    onTap: () -> Unit,
    onLongClick: (() -> Unit)?
): Modifier {
    return combinedClickable(
        onClick = onTap,
        onLongClick = onLongClick
    )
}
