@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.features.webapp.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.features.stickers.ui.view.StickerImage
import org.monogram.presentation.features.webapp.MainButtonState
import org.monogram.presentation.features.webapp.SecondaryButtonState

private fun Modifier.shineEffect(enabled: Boolean, shineOffset: Float): Modifier {
    if (!enabled) return this
    return this
        .clip(RoundedCornerShape(12.dp))
        .drawWithContent {
            drawContent()
            val shineWidth = size.width * 0.42f
            val centerY = size.height / 2f
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.25f),
                        Color.Transparent,
                    ),
                    start = Offset(x = shineOffset * size.width - shineWidth, y = centerY),
                    end = Offset(x = shineOffset * size.width, y = centerY)
                )
            )
        }
}

@Composable
private fun CustomEmojiIcon(
    customEmojiId: String?,
    modifier: Modifier = Modifier,
    stickerRepository: StickerRepository = koinInject()
) {
    val emojiIdLong = customEmojiId?.toLongOrNull() ?: return
    val emojiPath by stickerRepository.getCustomEmojiFile(emojiIdLong)
        .collectAsState(initial = null)

    Box(modifier = modifier) {
        StickerImage(path = emojiPath, modifier = Modifier.matchParentSize(), animate = true)
    }
}

@Composable
fun MainButton(
    state: MainButtonState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    defaultColor: Color = MaterialTheme.colorScheme.primary,
    defaultTextColor: Color = MaterialTheme.colorScheme.onPrimary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shine")
    val shineOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shineOffset"
    )

    Button(
        onClick = onClick,
        modifier = modifier.shineEffect(state.hasShineEffect, shineOffset),
        enabled = state.isActive,
        colors = ButtonDefaults.buttonColors(
            containerColor = state.color ?: defaultColor,
            contentColor = state.textColor ?: defaultTextColor
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        if (state.isProgressVisible) {
            LoadingIndicator(
                modifier = Modifier.size(20.dp),
                color = state.textColor ?: defaultTextColor,
            )
        } else {
            if (state.iconCustomEmojiId?.toLongOrNull() != null) {
                CustomEmojiIcon(
                    customEmojiId = state.iconCustomEmojiId,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(state.text)
        }
    }
}

@Composable
fun SecondaryButton(
    state: SecondaryButtonState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    defaultColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    defaultTextColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shine")
    val shineOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shineOffset"
    )

    Button(
        onClick = onClick,
        modifier = modifier.shineEffect(state.hasShineEffect, shineOffset),
        enabled = state.isActive,
        colors = ButtonDefaults.buttonColors(
            containerColor = state.color ?: defaultColor,
            contentColor = state.textColor ?: defaultTextColor
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        if (state.isProgressVisible) {
            LoadingIndicator(
                modifier = Modifier.size(20.dp),
                color = state.textColor ?: defaultTextColor,
            )
        } else {
            if (state.iconCustomEmojiId?.toLongOrNull() != null) {
                CustomEmojiIcon(
                    customEmojiId = state.iconCustomEmojiId,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(state.text)
        }
    }
}
