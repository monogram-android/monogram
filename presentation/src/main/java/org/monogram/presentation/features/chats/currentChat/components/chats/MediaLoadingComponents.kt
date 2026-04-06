package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import org.monogram.presentation.core.util.namespacedCacheKey

@Composable
fun MediaLoadingBackground(
    previewData: Any?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    previewBlur: Dp = 10.dp
) {
    val context = LocalContext.current
    val previewCacheKey = remember(previewData) {
        namespacedCacheKey("media_loading_preview", previewData)
    }
    val pulse = rememberInfiniteTransition(label = "MediaLoadingPulse")
    val pulseAlpha = pulse.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "MediaLoadingPulseAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (previewData != null) {
            Image(
                painter = rememberAsyncImagePainter(
                    model = ImageRequest.Builder(context)
                        .data(previewData)
                        .apply {
                            previewCacheKey?.let {
                                memoryCacheKey(it)
                                diskCacheKey(it)
                            }
                        }
                        .build()
                ),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(previewBlur),
                contentScale = contentScale
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(Color.Black.copy(alpha = pulseAlpha.value))
                }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaLoadingAction(
    isDownloading: Boolean,
    progress: Float,
    idleIcon: ImageVector,
    idleContentDescription: String,
    modifier: Modifier = Modifier,
    showCancelOnDownload: Boolean = true,
    onCancelClick: (() -> Unit)? = null,
    onIdleClick: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (isDownloading) {
            if (progress > 0f && progress < 1f) {
                CircularWavyProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(36.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
            } else {
                LoadingIndicator(
                    modifier = Modifier.size(36.dp),
                    color = Color.White,
                )
            }

            if (showCancelOnDownload) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel",
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(enabled = onCancelClick != null) { onCancelClick?.invoke() },
                    tint = Color.White
                )
            }
        } else {
            Icon(
                imageVector = idleIcon,
                contentDescription = idleContentDescription,
                modifier = Modifier
                    .size(28.dp)
                    .clickable(enabled = onIdleClick != null) { onIdleClick?.invoke() },
                tint = Color.White
            )
        }
    }
}
