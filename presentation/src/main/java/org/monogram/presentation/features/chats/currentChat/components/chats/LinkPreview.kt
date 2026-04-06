package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.monogram.domain.models.WebPage
import org.monogram.presentation.core.util.namespacedCacheKey
import org.monogram.presentation.features.viewers.extractYouTubeId

@Composable
fun LinkPreview(
    webPage: WebPage,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier,
    onInstantViewClick: ((String) -> Unit)? = null,
    onYouTubeClick: ((String) -> Unit)? = null
) {
    val hasSiteName = !webPage.siteName.isNullOrEmpty()
    val hasTitle = !webPage.title.isNullOrEmpty()
    val hasDescription = !webPage.description.isNullOrEmpty()

    val isVideo = when (webPage.type) {
        is WebPage.LinkPreviewType.Video,
        is WebPage.LinkPreviewType.ExternalVideo,
        is WebPage.LinkPreviewType.EmbeddedVideo,
        is WebPage.LinkPreviewType.Animation,
        is WebPage.LinkPreviewType.EmbeddedAnimation -> true
        else -> webPage.video != null ||
                webPage.siteName?.contains("YouTube", ignoreCase = true) == true ||
                webPage.url?.contains("youtu", ignoreCase = true) == true
    }

    val hasPhoto = webPage.photo != null
    val hasMedia = hasPhoto || isVideo
    val isInstantView = webPage.type == WebPage.LinkPreviewType.InstantView || webPage.instantViewVersion > 0

    if (!hasSiteName && !hasTitle && !hasDescription && !hasMedia) return

    val linkHandler = LocalLinkHandler.current
    val colorScheme = MaterialTheme.colorScheme

    val borderColor = if (isOutgoing) {
        colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
    } else {
        colorScheme.primary.copy(alpha = 0.4f)
    }

    val isSmallMedia = hasPhoto && !isVideo && (hasSiteName || hasTitle || hasDescription)
    val isYouTube = webPage.siteName?.contains("YouTube", ignoreCase = true) == true ||
            webPage.url?.let { extractYouTubeId(it) != null } == true

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
                .clickable {
                    val url = webPage.url ?: return@clickable
                    when {
                        isYouTube && onYouTubeClick != null -> onYouTubeClick(url)
                        isInstantView && onInstantViewClick != null -> onInstantViewClick(url)
                        else -> linkHandler(url)
                    }
                }
        ) {
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(borderColor)
                )
                Column(modifier = Modifier.padding(8.dp)) {
                    if (isSmallMedia) {
                        Row {
                            Column(modifier = Modifier.weight(1f)) {
                                LinkPreviewTextContent(webPage, isOutgoing, hasSiteName, hasTitle, hasDescription)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            LinkPreviewSmallImage(webPage)
                        }
                    } else {
                        LinkPreviewTextContent(webPage, isOutgoing, hasSiteName, hasTitle, hasDescription)

                        if (hasMedia) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinkPreviewLargeMedia(
                                webPage = webPage,
                                isVideo = isVideo,
                                isYouTube = isYouTube,
                                onPlayYouTube = {
                                    if (onYouTubeClick != null && webPage.url != null) {
                                        onYouTubeClick(webPage.url!!)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        if (isInstantView && onInstantViewClick != null && webPage.url != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = { onInstantViewClick(webPage.url!!) },
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
    webPage: WebPage,
    isOutgoing: Boolean,
    hasSiteName: Boolean,
    hasTitle: Boolean,
    hasDescription: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme

    if (hasSiteName || !webPage.author.isNullOrEmpty()) {
        val siteText = buildString {
            if (hasSiteName) append(webPage.siteName)
            if (!webPage.author.isNullOrEmpty() && webPage.author != webPage.siteName) {
                if (isNotEmpty()) append(" • ")
                append(webPage.author)
            }
        }
        Text(
            text = siteText,
            style = MaterialTheme.typography.labelMedium,
            color = if (isOutgoing) colorScheme.onPrimaryContainer else colorScheme.primary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    if (hasTitle) {
        Text(
            text = webPage.title!!,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (hasSiteName) 2.dp else 0.dp)
        )
    }

    if (hasDescription) {
        Text(
            text = webPage.description!!,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (hasSiteName || hasTitle) 2.dp else 0.dp)
        )
    }
}

@Composable
private fun LinkPreviewSmallImage(webPage: WebPage) {
    val photo = webPage.photo
    val context = LocalContext.current
    val modelData = remember(photo) { photo?.path ?: photo?.minithumbnail }
    val cacheKey = remember(modelData) { namespacedCacheKey("link_preview_small", modelData) }

    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(modelData)
                .apply {
                    cacheKey?.let {
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
    webPage: WebPage,
    isVideo: Boolean,
    isYouTube: Boolean,
    onPlayYouTube: () -> Unit
) {
    val context = LocalContext.current
    val linkHandler = LocalLinkHandler.current
    val photo = webPage.photo
    val video = webPage.video

    val aspectRatio = remember(photo, video) {
        val w = video?.width ?: photo?.width ?: 0
        val h = video?.height ?: photo?.height ?: 0
        if (w > 0 && h > 0) w.toFloat() / h.toFloat() else 1.77f
    }

    val videoId = remember(webPage.url) { extractYouTubeId(webPage.url) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val modelData = remember(photo, videoId, isYouTube) {
            if (isYouTube && videoId != null) {
                "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"
            } else {
                photo?.path ?: photo?.minithumbnail ?: video?.path
            }
        }
        val cacheKey = remember(modelData) { namespacedCacheKey("link_preview_large", modelData) }

        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(modelData)
                .apply {
                    cacheKey?.let {
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

        if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .clickable {
                        if (isYouTube) {
                            onPlayYouTube()
                        } else {
                            webPage.url?.let { linkHandler(it) }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            if (webPage.duration > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = formatDuration(webPage.duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}