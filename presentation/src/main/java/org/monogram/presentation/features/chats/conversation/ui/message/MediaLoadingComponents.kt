package org.monogram.presentation.features.chats.conversation.ui.message

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import org.monogram.presentation.R
import org.monogram.presentation.core.util.namespacedCacheKey

internal enum class StableMediaPainterState {
    Empty,
    Loading,
    Error,
    Success
}

internal enum class StableMediaVisualState {
    Preview,
    FullImageTransitioning,
    FullImage
}

internal data class StableMediaVisualResolution(
    val visualState: StableMediaVisualState,
    val readyModelKey: String?
)

internal fun resolveStableMediaVisualState(
    fullModelKey: String?,
    painterState: StableMediaPainterState,
    readyModelKey: String?,
    animationsEnabled: Boolean
): StableMediaVisualResolution {
    if (fullModelKey == null) {
        return StableMediaVisualResolution(StableMediaVisualState.Preview, null)
    }
    if (painterState != StableMediaPainterState.Success) {
        return StableMediaVisualResolution(StableMediaVisualState.Preview, null)
    }
    val modelWasReady = readyModelKey == fullModelKey
    return StableMediaVisualResolution(
        visualState = if (modelWasReady || !animationsEnabled) {
            StableMediaVisualState.FullImage
        } else {
            StableMediaVisualState.FullImageTransitioning
        },
        readyModelKey = fullModelKey
    )
}

@Composable
internal fun StableMediaImage(
    previewModel: Any?,
    fullResolutionModel: Any?,
    cacheKey: String?,
    contentScale: ContentScale,
    contentDescription: String?,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var readyModel by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(fullResolutionModel) {
        if (readyModel != fullResolutionModel) readyModel = null
    }
    val isReady = fullResolutionModel != null && readyModel == fullResolutionModel
    val fullImageAlpha by animateFloatAsState(
        targetValue = if (isReady) 1f else 0f,
        animationSpec = if (animationsEnabled) tween(durationMillis = 140) else snap(),
        label = "StableMediaFullImageAlpha"
    )

    Box(modifier = modifier) {
        MediaLoadingBackground(
            previewData = previewModel,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize()
        )
        if (fullResolutionModel != null) {
            val request = remember(fullResolutionModel, cacheKey) {
                ImageRequest.Builder(context)
                    .data(fullResolutionModel)
                    .apply {
                        cacheKey?.let {
                            memoryCacheKey(it)
                            diskCacheKey(it)
                        }
                    }
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = fullImageAlpha },
                onState = { state ->
                    if (state is AsyncImagePainter.State.Success) {
                        readyModel = fullResolutionModel
                    } else {
                        readyModel = null
                    }
                }
            )
        }
    }
}

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
                    drawRect(Color.Black.copy(alpha = 0.08f))
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
                    contentDescription = stringResource(R.string.cancel_button),
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
