package org.monogram.presentation.features.viewers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.domain.repository.FileRepository
import org.monogram.presentation.features.viewers.components.ImageOverlay
import org.monogram.presentation.features.viewers.components.ImagePage

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewer(
    images: List<FullscreenImageItem>,
    startIndex: Int = 0,
    onDismiss: () -> Unit,
    onPageChanged: ((Int) -> Unit)? = null,
    onForward: ((FullscreenImageItem) -> Unit)? = null,
    onDelete: ((FullscreenImageItem) -> Unit)? = null,
    onCopyLink: ((FullscreenImageItem) -> Unit)? = null,
    onCopyText: ((FullscreenImageItem) -> Unit)? = null,
    onRetry: ((String) -> Unit)? = null,
    onOriginalDecodeError: ((String) -> Unit)? = null,
    downloadUtils: IDownloadUtils,
    showImageNumber: Boolean = true
) {
    require(images.isNotEmpty()) { "images can't be empty" }

    val resolvedIndex = startIndex.coerceIn(0, images.lastIndex.coerceAtLeast(0))
    val pagerState = rememberPagerState(
        initialPage = resolvedIndex,
        pageCount = { images.size }
    )
    val scope = rememberCoroutineScope()
    val hostState = rememberFullscreenViewerHostState()

    var showControls by remember { mutableStateOf(true) }
    var showSettingsMenu by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged?.invoke(pagerState.currentPage)
        hostState.zoomState.resetInstant(scope)
        hostState.rootState.resetInstant(scope)
        showSettingsMenu = false
    }

    FullscreenViewerHost(
        onDismiss = onDismiss,
        showControls = showControls,
        showSettingsMenu = showSettingsMenu,
        onCloseSettingsMenu = { showSettingsMenu = false },
        hostState = hostState
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                key = { page ->
                    images.getOrNull(page)?.id ?: "image_page_$page"
                },
                pageSize = PageSize.Fill,
                beyondViewportPageCount = 0,
                userScrollEnabled = zoomState.scale.value == 1f && rootState.offsetY.value == 0f
            ) { page ->
                val item = images.getOrNull(page) ?: return@HorizontalPager

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                ) {
                    ImagePage(
                        item = item,
                        zoomState = zoomState,
                        rootState = rootState,
                        screenHeightPx = screenHeightPx,
                        dismissDistancePx = dismissDistancePx,
                        dismissVelocityThreshold = dismissVelocityThreshold,
                        onDismiss = onDismiss,
                        showControls = showControls,
                        onToggleControls = { showControls = !showControls },
                        onRetry = { onRetry?.invoke(item.id) },
                        onOriginalDecodeError = { onOriginalDecodeError?.invoke(item.id) },
                        pageIndex = page,
                        pagerIndex = pagerState.currentPage
                    )
                }
            }

            ImageOverlay(
                showControls = showControls,
                rootState = rootState,
                pagerState = pagerState,
                mediaItems = images,
                showImageNumber = showImageNumber,
                onDismiss = onDismiss,
                showSettingsMenu = showSettingsMenu,
                onToggleSettings = { showSettingsMenu = !showSettingsMenu },
                downloadUtils = downloadUtils,
                onForward = onForward,
                onDelete = onDelete,
                onCopyLink = onCopyLink,
                onCopyText = onCopyText
            )
        }
    }
}

@Composable
fun ManagedImageViewer(
    images: List<FullscreenImageItem>,
    fileRepository: FileRepository,
    startIndex: Int = 0,
    onDismiss: () -> Unit,
    onForward: ((FullscreenImageItem) -> Unit)? = null,
    onDelete: ((FullscreenImageItem) -> Unit)? = null,
    onCopyLink: ((FullscreenImageItem) -> Unit)? = null,
    onCopyText: ((FullscreenImageItem) -> Unit)? = null,
    downloadUtils: IDownloadUtils,
    showImageNumber: Boolean = true,
    onPageChanged: ((Int) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val holder = remember(fileRepository, scope) {
        FullscreenImageStateHolder(images, fileRepository, scope)
    }
    val resolvedImages by holder.items.collectAsState()
    var currentPage by remember { mutableIntStateOf(startIndex) }

    LaunchedEffect(images) {
        holder.updateItems(images)
        holder.requestPage(currentPage)
    }

    ImageViewer(
        images = resolvedImages,
        startIndex = startIndex,
        onDismiss = onDismiss,
        onPageChanged = { index ->
            currentPage = index
            holder.requestPage(index)
            onPageChanged?.invoke(index)
        },
        onForward = onForward,
        onDelete = onDelete,
        onCopyLink = onCopyLink,
        onCopyText = onCopyText,
        onRetry = holder::retry,
        onOriginalDecodeError = holder::markDecodeError,
        downloadUtils = downloadUtils,
        showImageNumber = showImageNumber
    )
}
