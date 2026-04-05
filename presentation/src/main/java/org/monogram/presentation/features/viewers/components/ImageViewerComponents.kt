@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.features.viewers.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Forward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.monogram.presentation.R
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow

@Composable
fun ImagePage(
    path: String,
    isDownloading: Boolean,
    downloadProgress: Float,
    zoomState: ZoomState,
    rootState: DismissRootState,
    screenHeightPx: Float,
    dismissDistancePx: Float,
    dismissVelocityThreshold: Float,
    onDismiss: () -> Unit,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    pageIndex: Int,
    pagerIndex: Int
) {
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        val currentScale = zoomState.scale.value
                        val targetScale = if (currentScale > 1.1f) 1f else 3f
                        zoomState.onDoubleTap(scope, offset, targetScale, size)
                    },
                    onTap = { onToggleControls() }
                )
            }
            .pointerInput(pagerIndex) {
                detectZoomAndDismissGestures(
                    zoomState = zoomState,
                    rootState = rootState,
                    screenHeightPx = screenHeightPx,
                    dismissThreshold = dismissDistancePx,
                    dismissVelocityThreshold = dismissVelocityThreshold,
                    onDismiss = onDismiss,
                    scope = scope
                )
            }
    ) {
        ZoomableImage(
            data = path,
            isDownloading = isDownloading,
            downloadProgress = downloadProgress,
            zoomState = zoomState,
            pageIndex = pageIndex,
            pagerIndex = pagerIndex
        )
    }
}

@Composable
fun ImageOverlay(
    showControls: Boolean,
    rootState: DismissRootState,
    pagerState: PagerState,
    mediaItems: List<String>,
    captions: List<String?>,
    showImageNumber: Boolean,
    onDismiss: () -> Unit,
    showSettingsMenu: Boolean,
    onToggleSettings: () -> Unit,
    downloadUtils: IDownloadUtils,
    onForward: (String) -> Unit,
    onDelete: ((String) -> Unit)?,
    onCopyLink: ((String) -> Unit)?,
    onCopyText: ((String) -> Unit)?
) {
    val scope = rememberCoroutineScope()

    AnimatedVisibility(
        visible = showControls && rootState.offsetY.value == 0f,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(Modifier.fillMaxSize()) {
            ViewerTopBar(
                onBack = onDismiss,
                onActionClick = onToggleSettings,
                isActionActive = showSettingsMenu,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.4f),
                                Color.Black.copy(alpha = 0.8f)
                            )
                        )
                    )
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val currentCaption = captions.getOrNull(pagerState.currentPage)
                if (!currentCaption.isNullOrBlank()) {
                    ViewerCaption(caption = currentCaption, showGradient = false)
                }

                if (mediaItems.size > 1) {
                    ThumbnailStrip(
                        images = mediaItems,
                        pagerState = pagerState,
                        scope = scope
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    if (showImageNumber) {
                        PageIndicator(
                            current = pagerState.currentPage + 1,
                            total = mediaItems.size
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    AnimatedVisibility(
        visible = showSettingsMenu && showControls,
        enter = fadeIn(tween(150)) + scaleIn(
            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
            initialScale = 0.8f,
            transformOrigin = TransformOrigin(1f, 0f)
        ),
        exit = fadeOut(tween(150)) + scaleOut(
            animationSpec = tween(150),
            targetScale = 0.9f,
            transformOrigin = TransformOrigin(1f, 0f)
        ),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    onToggleSettings()
                }
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 56.dp, end = 16.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            val currentIndex = pagerState.currentPage
            val currentItem = mediaItems.getOrNull(currentIndex)

            if (currentItem != null) {
                val currentCaption = captions.getOrNull(currentIndex)
                ImageSettingsMenu(
                    onDownload = {
                        downloadUtils.saveFileToDownloads(currentItem)
                        onToggleSettings()
                    },
                    onCopyImage = {
                        downloadUtils.copyImageToClipboard(currentItem)
                        onToggleSettings()
                    },
                    onCopyLink = {
                        onCopyLink?.invoke(currentItem)
                        onToggleSettings()
                    },
                    onCopyText = if (!currentCaption.isNullOrBlank()) {
                        {
                            onCopyText?.invoke(currentItem)
                            onToggleSettings()
                        }
                    } else null,
                    onForward = {
                        onForward(currentItem)
                        onToggleSettings()
                    },
                    onDelete = onDelete?.let {
                        {
                            it(currentItem)
                            onToggleSettings()
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun ImageSettingsMenu(
    onDownload: () -> Unit,
    onCopyImage: () -> Unit,
    onCopyLink: (() -> Unit)?,
    onCopyText: (() -> Unit)? = null,
    onForward: () -> Unit,
    onDelete: (() -> Unit)?
) {
    ViewerSettingsDropdown {
        MenuOptionRow(
            icon = Icons.Rounded.Download,
            title = stringResource(R.string.action_download),
            onClick = onDownload
        )
        MenuOptionRow(
            icon = Icons.Rounded.ContentCopy,
            title = stringResource(R.string.action_copy_image),
            onClick = onCopyImage
        )
        if (onCopyText != null) {
            MenuOptionRow(
                icon = Icons.Rounded.ContentCopy,
                title = stringResource(R.string.action_copy_text),
                onClick = onCopyText
            )
        }
        if (onCopyLink != null) {
            MenuOptionRow(
                icon = Icons.Rounded.Link,
                title = stringResource(R.string.action_copy_link),
                onClick = onCopyLink
            )
        }
        MenuOptionRow(
            icon = Icons.AutoMirrored.Rounded.Forward,
            title = stringResource(R.string.action_forward),
            onClick = onForward
        )
        if (onDelete != null) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )
            MenuOptionRow(
                icon = Icons.Rounded.Delete,
                title = stringResource(R.string.action_delete),
                onClick = onDelete,
                iconTint = MaterialTheme.colorScheme.error,
                textColor = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun ThumbnailStrip(
    images: List<Any>,
    pagerState: PagerState,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
    thumbnailSize: Dp = 60.dp,
    thumbnailSpacing: Dp = 8.dp
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val context = LocalContext.current

    LaunchedEffect(pagerState.currentPage) {
        val viewportWidth = listState.layoutInfo.viewportSize.width
        if (viewportWidth > 0) {
            val itemSizePx = with(density) { thumbnailSize.toPx() }
            val centerOffset = (viewportWidth / 2) - (itemSizePx / 2)

            listState.animateScrollToItem(
                index = pagerState.currentPage,
                scrollOffset = -centerOffset.toInt()
            )
        } else {
            listState.animateScrollToItem(pagerState.currentPage)
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier
            .wrapContentWidth()
            .height(thumbnailSize + 24.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(thumbnailSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        itemsIndexed(
            images,
            key = { index, _ -> "thumb_$index" }
        ) { index, image ->
            val isSelected = pagerState.currentPage == index

            val scale by animateFloatAsState(targetValue = if (isSelected) 1.1f else 0.9f, label = "scale")
            val alpha by animateFloatAsState(targetValue = if (isSelected) 1f else 0.5f, label = "alpha")
            val borderColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                label = "border"
            )
            val borderWidth by animateDpAsState(targetValue = if (isSelected) 2.dp else 0.dp, label = "width")

            val request = remember(image) {
                ImageRequest.Builder(context)
                    .data(image)
                    .crossfade(true)
                    .allowHardware(true)
                    .build()
            }

            Box(
                modifier = Modifier
                    .size(thumbnailSize)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Gray.copy(alpha = 0.2f))
                    .clickable {
                        scope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    }
            ) {
                AsyncImage(
                    model = request,
                    contentDescription = stringResource(R.string.viewer_thumbnail_cd, index),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(12.dp))
                    )
                }
            }
        }
    }
}

@Composable
fun PageIndicator(modifier: Modifier = Modifier, current: Int, total: Int) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = CircleShape
    ) {
        Text(
            text = stringResource(R.string.viewer_page_indicator, current, total),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun ZoomableImage(
    data: Any,
    isDownloading: Boolean,
    downloadProgress: Float,
    zoomState: ZoomState,
    pageIndex: Int,
    pagerIndex: Int
) {
    val applyTransforms = pageIndex == pagerIndex
    val context = LocalContext.current

    var isHighResLoading by remember(data) { mutableStateOf(true) }

    val thumbnailRequest = remember(data) {
        ImageRequest.Builder(context)
            .data(data)
            .size(100, 100)
            .crossfade(true)
            .build()
    }

    val fullRequest = remember(data) {
        ImageRequest.Builder(context)
            .data(data)
            .size(Size.ORIGINAL)
            .precision(Precision.EXACT)
            .crossfade(true)
            .build()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = thumbnailRequest,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (applyTransforms) {
                        translationX = zoomState.offsetX.value
                        translationY = zoomState.offsetY.value
                        scaleX = zoomState.scale.value
                        scaleY = zoomState.scale.value
                    }
                }
        )

        AsyncImage(
            model = fullRequest,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (applyTransforms) {
                        translationX = zoomState.offsetX.value
                        translationY = zoomState.offsetY.value
                        scaleX = zoomState.scale.value
                        scaleY = zoomState.scale.value
                    }
                },
            onState = { state ->
                isHighResLoading = state is AsyncImagePainter.State.Loading
            }
        )

        if (isHighResLoading || isDownloading) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = Color.White,
                    progress = {
                        if (isDownloading && downloadProgress in 0f..1f) {
                            downloadProgress
                        } else {
                            0f
                        }
                    }
                )
                Text(
                    text = if (isDownloading) {
                        stringResource(R.string.viewer_loading_original)
                    } else {
                        stringResource(R.string.loading_text)
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
