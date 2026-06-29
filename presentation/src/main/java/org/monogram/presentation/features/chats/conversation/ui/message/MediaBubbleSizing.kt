package org.monogram.presentation.features.chats.conversation.ui.message

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.MessageContent

private const val DEFAULT_MEDIA_ASPECT_RATIO = 1f
private const val MIN_MEDIA_ASPECT_RATIO = 0.5f
private const val MAX_MEDIA_ASPECT_RATIO = 2f
private const val MEDIA_BUBBLE_WIDTH_FRACTION = 0.92f

internal fun MessageContent.prefersExpandedBubbleWidth(): Boolean =
    this is MessageContent.Photo || this is MessageContent.Video || this is MessageContent.Gif

internal fun resolveMediaBubbleAspectRatio(
    mediaWidth: Int,
    mediaHeight: Int
): Float {
    if (mediaWidth <= 0 || mediaHeight <= 0) return DEFAULT_MEDIA_ASPECT_RATIO
    return (mediaWidth.toFloat() / mediaHeight.toFloat()).coerceIn(
        MIN_MEDIA_ASPECT_RATIO,
        MAX_MEDIA_ASPECT_RATIO
    )
}

internal fun resolveMediaBubbleHeight(
    containerWidth: Dp,
    aspectRatio: Float,
    minHeight: Dp = 160.dp,
    maxHeight: Dp = 320.dp
): Dp {
    val safeAspectRatio = aspectRatio.takeIf { it > 0f } ?: DEFAULT_MEDIA_ASPECT_RATIO
    return (resolveMediaBubbleWidth(containerWidth) / safeAspectRatio).coerceIn(
        minHeight,
        maxHeight
    )
}

internal fun resolveMediaBubbleWidth(containerWidth: Dp): Dp =
    containerWidth * MEDIA_BUBBLE_WIDTH_FRACTION
