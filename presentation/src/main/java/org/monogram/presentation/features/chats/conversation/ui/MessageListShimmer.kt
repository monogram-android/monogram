package org.monogram.presentation.features.chats.conversation.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun MessageListShimmer(
    isGroup: Boolean = false,
    isChannel: Boolean = false
) {
    val shimmer = rememberMessageShimmerBrush()

    if (isChannel) {
        ChannelMessageListShimmer(brush = shimmer)
        return
    }

    val hasSenderMeta = isGroup && !isChannel

    val items = listOf(
        ShimmerConfig(false, 0.62f, listOf(0.9f, 0.56f), false, true),
        ShimmerConfig(false, 0.4f, listOf(0.7f), true, false),
        ShimmerConfig(true, 0.56f, listOf(0.78f, 0.52f), false, false),
        ShimmerConfig(false, 0.5f, listOf(0.74f), false, false),
        ShimmerConfig(true, 0.44f, listOf(0.66f), false, false),
        ShimmerConfig(false, 0.58f, listOf(0.86f, 0.64f), false, false),
        ShimmerConfig(true, 0.66f, listOf(0.94f, 0.72f, 0.48f), false, false),
        ShimmerConfig(false, 0.54f, listOf(0.8f), false, false),
        ShimmerConfig(true, 0.5f, listOf(0.7f), false, true),
        ShimmerConfig(true, 0.6f, listOf(0.9f, 0.58f), true, false),
        ShimmerConfig(false, 0.66f, listOf(0.9f, 0.68f), false, true),
        ShimmerConfig(false, 0.46f, listOf(0.76f), true, false),
        ShimmerConfig(true, 0.52f, listOf(0.78f, 0.52f), false, false),
        ShimmerConfig(false, 0.6f, listOf(0.84f, 0.6f), false, false)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        items.forEachIndexed { index, config ->
            MessagePlaceholder(
                brush = shimmer,
                isOutgoing = config.isOutgoing,
                bubbleWidth = if (isChannel) {
                    if (config.isOutgoing) 0.92f else 0.94f
                } else {
                    config.bubbleWidth
                },
                lineWidths = config.lineWidths,
                reserveAvatarSpace = hasSenderMeta,
                showAvatar = hasSenderMeta && !config.isOutgoing && !config.isSameSenderAbove,
                showSender = hasSenderMeta && !config.isOutgoing && !config.isSameSenderAbove,
                isSameSenderAbove = config.isSameSenderAbove,
                isSameSenderBelow = config.isSameSenderBelow
            )

            if (index != items.lastIndex) {
                Spacer(modifier = Modifier.height(if (config.isSameSenderBelow) 2.dp else 8.dp))
            }
        }
    }
}

@Composable
private fun ChannelMessageListShimmer(brush: Brush) {
    val posts = listOf(
        listOf(0.95f, 0.88f, 0.92f, 0.8f, 0.72f, 0.45f),
        listOf(0.9f, 0.85f, 0.93f, 0.78f, 0.88f, 0.9f, 0.82f, 0.55f),
        listOf(0.92f, 0.86f, 0.75f, 0.9f, 0.6f),
        listOf(0.88f, 0.8f, 0.5f)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        posts.forEachIndexed { index, lines ->
            ChannelPostPlaceholder(brush = brush, lineWidths = lines)
            if (index != posts.lastIndex) {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun ChannelPostPlaceholder(
    brush: Brush,
    lineWidths: List<Float>
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
            modifier = Modifier.fillMaxWidth(0.94f)
        ) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)
            ) {
                lineWidths.forEachIndexed { index, width ->
                    ShimmerBlock(
                        brush = brush,
                        modifier = Modifier
                            .fillMaxWidth(width)
                            .height(14.dp),
                        shape = RoundedCornerShape(5.dp)
                    )
                    if (index != lineWidths.lastIndex) {
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    ShimmerBlock(
                        brush = brush,
                        modifier = Modifier.size(14.dp),
                        shape = RoundedCornerShape(7.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    ShimmerBlock(
                        brush = brush,
                        modifier = Modifier
                            .width(26.dp)
                            .height(10.dp),
                        shape = RoundedCornerShape(3.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    ShimmerBlock(
                        brush = brush,
                        modifier = Modifier
                            .width(32.dp)
                            .height(10.dp),
                        shape = RoundedCornerShape(3.dp)
                    )
                }
            }
        }
    }
}

private data class ShimmerConfig(
    val isOutgoing: Boolean,
    val bubbleWidth: Float,
    val lineWidths: List<Float>,
    val isSameSenderAbove: Boolean,
    val isSameSenderBelow: Boolean
)

@Composable
private fun MessagePlaceholder(
    brush: Brush,
    isOutgoing: Boolean,
    bubbleWidth: Float,
    lineWidths: List<Float>,
    reserveAvatarSpace: Boolean,
    showAvatar: Boolean,
    showSender: Boolean,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean
) {
    val bubbleShape = messageShape(
        isOutgoing = isOutgoing,
        isSameSenderAbove = isSameSenderAbove,
        isSameSenderBelow = isSameSenderBelow
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isOutgoing && reserveAvatarSpace) {
            if (showAvatar) {
                ShimmerBlock(
                    brush = brush,
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(20.dp)
                )
            } else {
                Spacer(modifier = Modifier.size(40.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        if (isOutgoing) {
            Spacer(modifier = Modifier.weight(1f))
        }

        Surface(
            shape = bubbleShape,
            color = if (isOutgoing) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(bubbleWidth)
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)
            ) {
                if (showSender) {
                    ShimmerBlock(
                        brush = brush,
                        modifier = Modifier
                            .width(78.dp)
                            .height(12.dp),
                        shape = RoundedCornerShape(4.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                lineWidths.forEachIndexed { index, width ->
                    ShimmerBlock(
                        brush = brush,
                        modifier = Modifier
                            .fillMaxWidth(width)
                            .height(14.dp),
                        shape = RoundedCornerShape(5.dp)
                    )
                    if (index != lineWidths.lastIndex) {
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    ShimmerBlock(
                        brush = brush,
                        modifier = Modifier
                            .width(32.dp)
                            .height(10.dp),
                        shape = RoundedCornerShape(3.dp)
                    )
                    if (isOutgoing) {
                        Spacer(modifier = Modifier.width(4.dp))
                        ShimmerBlock(
                            brush = brush,
                            modifier = Modifier
                                .width(13.dp)
                                .height(10.dp),
                            shape = RoundedCornerShape(3.dp)
                        )
                    }
                }
            }
        }

        if (!isOutgoing) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ShimmerBlock(
    brush: Brush,
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(6.dp)
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(brush)
    )
}

private fun messageShape(
    isOutgoing: Boolean,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    radius: Dp = 18.dp,
    small: Dp = 4.5.dp,
    tail: Dp = 2.dp
): RoundedCornerShape {
    return if (isOutgoing) {
        RoundedCornerShape(
            topStart = radius,
            topEnd = if (isSameSenderAbove) small else radius,
            bottomStart = radius,
            bottomEnd = if (isSameSenderBelow) small else tail
        )
    } else {
        RoundedCornerShape(
            topStart = if (isSameSenderAbove) small else radius,
            topEnd = radius,
            bottomStart = if (isSameSenderBelow) small else tail,
            bottomEnd = radius
        )
    }
}

@Composable
private fun rememberMessageShimmerBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val transition = rememberInfiniteTransition(label = "message_shimmer")
    val offset by transition.animateFloat(
        initialValue = -600f,
        targetValue = 1400f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "message_shimmer_offset"
    )

    return Brush.linearGradient(
        colors = listOf(base, base.copy(alpha = 0.15f), base),
        start = Offset(offset, 0f),
        end = Offset(offset + 360f, 0f)
    )
}
