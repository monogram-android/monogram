package org.monogram.presentation.features.profile

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.MessageContent
import org.monogram.presentation.R
import org.monogram.presentation.features.profile.components.LocationViewer
import org.monogram.presentation.features.profile.components.StatisticsViewer
import org.monogram.presentation.features.viewers.ImageViewer
import org.monogram.presentation.features.viewers.VideoViewer
import org.monogram.presentation.features.webapp.MiniAppViewer

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProfileViewers(
    state: ProfileComponent.State,
    component: ProfileComponent
) {
    ImagesOverlay(state, component)
    VideoOverlay(state, component)
    MiniAppOverlay(state, component)
    StatisticsOverlay(state, component)
    RevenueStatisticsOverlay(state, component)
    LocationOverlay(state, component)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ImagesOverlay(state: ProfileComponent.State, component: ProfileComponent) {
    val context = LocalContext.current
    val notImplemented = stringResource(R.string.not_implemented)

    AnimatedVisibility(
        visible = state.fullScreenImages != null,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f)
    ) {
        state.fullScreenImages?.let { images ->
            Box(modifier = Modifier.fillMaxSize()) {
                ImageViewer(
                    images = images,
                    startIndex = state.fullScreenStartIndex,
                    onDismiss = component::onDismissViewer,
                    autoDownload = false,
                    downloadUtils = component.downloadUtils,
                    onPageChanged = { index ->
                        if (!state.isViewingProfilePhotos && state.canLoadMoreMedia && !state.isLoadingMoreMedia &&
                            index >= images.size - 5
                        ) {
                            component.onLoadMoreMedia()
                        }

                        if (!state.isViewingProfilePhotos) {
                            val photoMessages = state.mediaMessages.filter { it.content is MessageContent.Photo }
                            val message = photoMessages.getOrNull(index)
                            if (message != null) {
                                component.onDownloadMedia(message)
                                val nextMessage = photoMessages.getOrNull(index + 1)
                                if (nextMessage != null) {
                                    component.onDownloadMedia(nextMessage)
                                }
                            }
                        }
                    },
                    onForward = { Toast.makeText(context, notImplemented, Toast.LENGTH_SHORT).show() },
                    onDelete = { Toast.makeText(context, notImplemented, Toast.LENGTH_SHORT).show() },
                    onCopyLink = { Toast.makeText(context, notImplemented, Toast.LENGTH_SHORT).show() },
                    onCopyText = { Toast.makeText(context, notImplemented, Toast.LENGTH_SHORT).show() },
                    captions = state.fullScreenCaptions.filterNotNull(),
                    showImageNumber = false
                )

                if (state.isViewingProfilePhotos && state.isProfilePhotoHdLoading) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 56.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            LoadingIndicator(modifier = Modifier.size(16.dp))
                            Text(
                                text = "Loading HD",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoOverlay(state: ProfileComponent.State, component: ProfileComponent) {
    val context = LocalContext.current
    val notImplemented = stringResource(R.string.not_implemented)

    AnimatedVisibility(
        visible = state.fullScreenVideoPath != null,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f)
    ) {
        state.fullScreenVideoPath?.let { path ->
            val msg = state.mediaMessages.find {
                when (val content = it.content) {
                    is MessageContent.Video -> content.path == path
                    is MessageContent.Gif -> content.path == path
                    is MessageContent.VideoNote -> content.path == path
                    else -> false
                }
            }
            val videoContent = msg?.content as? MessageContent.Video
            val fileId = videoContent?.fileId ?: (msg?.content as? MessageContent.Gif)?.fileId
            ?: (msg?.content as? MessageContent.VideoNote)?.fileId ?: 0
            val supportsStreaming = videoContent?.supportsStreaming ?: false

            VideoViewer(
                path = path,
                onDismiss = component::onDismissViewer,
                isGesturesEnabled = true,
                isDoubleTapSeekEnabled = true,
                seekDuration = 10,
                isZoomEnabled = true,
                downloadUtils = component.downloadUtils,
                onForward = { Toast.makeText(context, notImplemented, Toast.LENGTH_SHORT).show() },
                onDelete = { Toast.makeText(context, notImplemented, Toast.LENGTH_SHORT).show() },
                onCopyLink = { Toast.makeText(context, notImplemented, Toast.LENGTH_SHORT).show() },
                onCopyText = { Toast.makeText(context, notImplemented, Toast.LENGTH_SHORT).show() },
                caption = state.fullScreenVideoCaption,
                fileId = fileId,
                supportsStreaming = supportsStreaming
            )
        }
    }
}

@Composable
private fun MiniAppOverlay(state: ProfileComponent.State, component: ProfileComponent) {
    AnimatedVisibility(
        visible = state.miniAppUrl != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        if (state.miniAppUrl != null && state.miniAppName != null) {
            val title = state.chat?.title ?: listOfNotNull(state.user?.firstName, state.user?.lastName)
                .joinToString(" ")
                .ifBlank { "Unknown" }
                
            MiniAppViewer(
                baseUrl = state.miniAppUrl,
                botName = title,
                onDismiss = { component.onDismissMiniApp() },
                chatId = state.chatId,
                botUserId = state.user?.id ?: 0L,
                webAppRepository = component.messageRepository,
            )
        }
    }
}

@Composable
private fun StatisticsOverlay(state: ProfileComponent.State, component: ProfileComponent) {
    AnimatedVisibility(
        visible = state.isStatisticsVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        state.statistics?.let { stats ->
            StatisticsViewer(
                title = stringResource(R.string.statistics_title),
                data = stats,
                onDismiss = component::onDismissStatistics,
                onLoadGraph = component::onLoadStatisticsGraph
            )
        }
    }
}

@Composable
private fun RevenueStatisticsOverlay(state: ProfileComponent.State, component: ProfileComponent) {
    AnimatedVisibility(
        visible = state.isRevenueStatisticsVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        state.revenueStatistics?.let { stats ->
            StatisticsViewer(
                title = stringResource(R.string.revenue_title),
                data = stats,
                onDismiss = component::onDismissStatistics,
                onLoadGraph = component::onLoadStatisticsGraph
            )
        }
    }
}

@Composable
private fun LocationOverlay(state: ProfileComponent.State, component: ProfileComponent) {
    state.selectedLocation?.let { location ->
        LocationViewer(
            location = location,
            onDismiss = component::onDismissLocation
        )
    }
}
