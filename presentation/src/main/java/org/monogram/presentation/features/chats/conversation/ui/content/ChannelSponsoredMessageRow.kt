package org.monogram.presentation.features.chats.conversation.ui.content

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.SponsoredMessageModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.shimmerBackground
import org.monogram.presentation.core.util.namespacedCacheKey
import org.monogram.presentation.features.chats.conversation.ui.message.MediaLoadingBackground

@Composable
internal fun ChannelSponsoredMessageRow(
    sponsoredMessage: SponsoredMessageModel,
    onCardClick: () -> Unit,
    onMediaClick: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val maxWidth = if (isLandscape) {
        (screenWidth * 0.7f).coerceAtMost(600.dp)
    } else {
        (screenWidth * 0.94f).coerceAtMost(500.dp)
    }
    val content = sponsoredMessage.content
    val media = rememberSponsoredMedia(content)
    val titleColor = MaterialTheme.colorScheme.onSurface
    val secondaryTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bubbleColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)
    val buttonColor = MaterialTheme.colorScheme.secondaryContainer
    val label = stringResource(
        if (sponsoredMessage.isRecommended) {
            R.string.chat_recommended_label
        } else {
            R.string.chat_sponsored_label
        }
    )
    val contentText = sponsoredContentText(content)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(18.dp)
                        ),
                    color = bubbleColor,
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 0.dp,
                    onClick = onCardClick
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (sponsoredMessage.sponsor.photoPath != null) {
                                AsyncImage(
                                    model = sponsoredMessage.sponsor.photoPath,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            CircleShape
                                        ),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Campaign,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                sponsoredMessage.title?.takeIf { it.isNotBlank() }?.let { title ->
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = titleColor,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                sponsoredMessage.sponsor.info?.takeIf { it.isNotBlank() }
                                    ?.let { info ->
                                        Text(
                                            text = info,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = secondaryTextColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                            }
                        }

                        media?.let {
                            SponsoredMedia(
                                messageId = sponsoredMessage.messageId,
                                media = it,
                                context = context,
                                onMediaClick = onMediaClick
                            )
                        }

                        contentText?.let { text ->
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = titleColor,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        sponsoredMessage.additionalInfo?.takeIf { it.isNotBlank() }?.let { info ->
                            Text(
                                text = info,
                                style = MaterialTheme.typography.bodySmall,
                                color = secondaryTextColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        sponsoredMessage.buttonText?.takeIf { it.isNotBlank() }?.let { buttonText ->
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            )
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onCardClick,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = buttonColor,
                                    contentColor = titleColor
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = buttonText,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SponsoredMedia(
    messageId: Long,
    media: SponsoredMediaUiModel,
    context: android.content.Context,
    onMediaClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val model = media.fullPath ?: media.previewPath
    val cacheKey = when {
        !media.fullPath.isNullOrBlank() -> namespacedCacheKey(
            "sponsored_media_full:${media.fileId}",
            media.fullPath
        )

        !media.previewPath.isNullOrBlank() -> namespacedCacheKey(
            "sponsored_media_preview:${media.fileId}",
            media.previewPath
        )

        else -> null
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val mediaRatio = if (media.width > 0 && media.height > 0) {
            (media.width.toFloat() / media.height.toFloat()).coerceIn(0.5f, 2f)
        } else {
            1f
        }
        val mediaHeight = (maxWidth / mediaRatio).coerceIn(160.dp, 320.dp)
            .coerceAtMost(240.dp)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(mediaHeight)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onMediaClick),
            contentAlignment = Alignment.Center
        ) {
            when {
                !media.fullPath.isNullOrBlank() -> {
                    val request = ImageRequest.Builder(context)
                        .data(model)
                        .apply {
                            cacheKey?.let {
                                memoryCacheKey(it)
                                diskCacheKey(it)
                            }
                        }
                        .crossfade(false)
                        .build()
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Crop
                    )
                }

                !media.previewPath.isNullOrBlank() -> {
                    val request = ImageRequest.Builder(context)
                        .data(model)
                        .apply {
                            cacheKey?.let {
                                memoryCacheKey(it)
                                diskCacheKey(it)
                            }
                        }
                        .crossfade(false)
                        .build()
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Crop
                    )
                }

                media.minithumbnail != null -> {
                    MediaLoadingBackground(
                        previewData = media.minithumbnail,
                        contentScale = ContentScale.Crop
                    )
                }

                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(mediaHeight)
                            .shimmerBackground(shape = shape)
                    )
                }
            }
        }
    }
}

internal fun sponsoredContentText(content: MessageContent): String? {
    return when (content) {
        is MessageContent.Text -> content.text.takeIf { it.isNotBlank() }
        is MessageContent.Photo -> content.caption.takeIf { it.isNotBlank() }
        is MessageContent.Video -> content.caption.takeIf { it.isNotBlank() }
        is MessageContent.Gif -> content.caption.takeIf { it.isNotBlank() }
        else -> null
    }
}

private fun rememberSponsoredMedia(content: MessageContent): SponsoredMediaUiModel? {
    return when (content) {
        is MessageContent.Photo -> SponsoredMediaUiModel(
            fileId = content.fileId,
            width = content.width,
            height = content.height,
            fullPath = content.path,
            previewPath = content.thumbnailPath,
            minithumbnail = content.minithumbnail
        )

        is MessageContent.Video -> SponsoredMediaUiModel(
            fileId = content.fileId,
            width = content.width,
            height = content.height,
            fullPath = content.path,
            previewPath = content.thumbnailPath,
            minithumbnail = content.minithumbnail
        )

        is MessageContent.Gif -> SponsoredMediaUiModel(
            fileId = content.fileId,
            width = content.width,
            height = content.height,
            fullPath = content.path,
            previewPath = null,
            minithumbnail = content.minithumbnail
        )

        else -> null
    }
}

private data class SponsoredMediaUiModel(
    val fileId: Int,
    val width: Int,
    val height: Int,
    val fullPath: String?,
    val previewPath: String?,
    val minithumbnail: ByteArray?
) {
    val minithumbnailHash: Int? = minithumbnail?.contentHashCode()
    val logSource: String = when {
        !fullPath.isNullOrBlank() -> "full"
        !previewPath.isNullOrBlank() -> "thumb"
        minithumbnail != null -> "mini"
        else -> "skeleton"
    }
}
