package org.monogram.presentation.features.gallery

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.domain.models.AttachMenuBotModel
import org.monogram.presentation.R
import org.monogram.presentation.features.gallery.components.AttachBotsSection
import org.monogram.presentation.features.gallery.components.FolderRow
import org.monogram.presentation.features.gallery.components.GalleryGrid
import org.monogram.presentation.features.gallery.components.GalleryTabs
import org.monogram.presentation.features.gallery.components.PartialAccessCard
import org.monogram.presentation.features.gallery.components.PermissionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onMediaSelected: (List<Uri>) -> Unit,
    onDismiss: () -> Unit,
    onCameraClick: () -> Unit,
    canSelectMedia: Boolean,
    canUseCamera: Boolean,
    canAttachFiles: Boolean,
    canCreatePoll: Boolean,
    onAttachFileClick: () -> Unit,
    onCreatePollClick: () -> Unit,
    attachBots: List<AttachMenuBotModel>,
    hasMediaAccess: Boolean,
    isPartialAccess: Boolean,
    onPickFromOtherSources: () -> Unit,
    onRequestMediaAccess: () -> Unit,
    onAttachBotClick: (AttachMenuBotModel) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mediaList = remember { mutableStateListOf<GalleryMediaItem>() }
    val selectedMedia = remember { mutableStateListOf<Uri>() }
    val visibleBots = remember(attachBots) {
        attachBots.filter { it.showInAttachMenu && it.name.isNotBlank() }
    }

    var filter by remember { mutableStateOf(GalleryFilter.All) }
    var bucketFilter by remember { mutableStateOf<BucketFilter>(BucketFilter.All) }
    var isLoading by remember { mutableStateOf(false) }
    val showPermissionCard = canSelectMedia && !hasMediaAccess

    LaunchedEffect(hasMediaAccess, canSelectMedia) {
        if (!hasMediaAccess || !canSelectMedia) {
            mediaList.clear()
            selectedMedia.clear()
            return@LaunchedEffect
        }
        isLoading = true
        val loaded = withContext(Dispatchers.IO) {
            val images = queryImages(context)
            val videos = queryVideos(context)
            (images + videos).sortedByDescending { it.dateAdded }
        }
        mediaList.clear()
        mediaList.addAll(loaded)
        isLoading = false
    }

    val buckets by remember { derivedStateOf { buildBuckets(mediaList) } }
    val filteredMedia by remember(mediaList, filter, bucketFilter) {
        derivedStateOf {
            mediaList.filter { item ->
                val byType = when (filter) {
                    GalleryFilter.All -> true
                    GalleryFilter.Photos -> !item.isVideo
                    GalleryFilter.Videos -> item.isVideo
                }
                val byBucket = if (filter == GalleryFilter.Photos) {
                    when (val selectedBucket = bucketFilter) {
                        BucketFilter.All -> true
                        BucketFilter.Camera -> item.isCamera
                        BucketFilter.Screenshots -> item.isScreenshot
                        is BucketFilter.Custom -> item.bucketName.equals(
                            selectedBucket.name,
                            ignoreCase = true
                        )
                    }
                } else {
                    true
                }
                byType && byBucket
            }
        }
    }

    LaunchedEffect(filter, buckets) {
        if (filter != GalleryFilter.Photos) return@LaunchedEffect
        if (bucketFilter !in buckets) {
            bucketFilter = BucketFilter.All
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            GalleryToolbar(
                onDismiss = onDismiss,
                showOtherSourcesAction = canSelectMedia,
                showCameraAction = canUseCamera,
                onPickFromOtherSources = onPickFromOtherSources,
                onCameraClick = onCameraClick
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 8.dp,
                    end = 8.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding()
                )
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (showPermissionCard) {
                    PermissionCard(onRequestMediaAccess = onRequestMediaAccess)
                    return@Column
                }

                AnimatedVisibility(
                    visible = canSelectMedia && isPartialAccess,
                    enter = fadeIn(tween(160)) + slideInVertically(tween(160)) { -it / 4 },
                    exit = fadeOut(tween(120))
                ) {
                    PartialAccessCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        onManageAccessClick = onRequestMediaAccess
                    )
                }

                if (canSelectMedia) {
                    GalleryTabs(
                        filter = filter,
                        onFilterChange = { newFilter -> filter = newFilter }
                    )

                    AnimatedVisibility(
                        visible = filter == GalleryFilter.Photos,
                        enter = fadeIn(tween(160)) + slideInVertically(tween(160)) { -it / 4 },
                        exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 4 }
                    ) {
                        FolderRow(
                            buckets = buckets,
                            selectedBucket = bucketFilter,
                            onBucketChange = { bucketFilter = it }
                        )
                    }

                    val gridStateKey = remember(filter, bucketFilter) {
                        val bucketKey = when (val value = bucketFilter) {
                            BucketFilter.All -> value.key
                            BucketFilter.Camera -> value.key
                            BucketFilter.Screenshots -> value.key
                            is BucketFilter.Custom -> value.name.lowercase()
                        }
                        "${filter.name}_$bucketKey"
                    }

                    AnimatedContent(
                        targetState = gridStateKey,
                        transitionSpec = {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                        },
                        label = "galleryGridTransition",
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            GalleryGrid(
                                media = filteredMedia,
                                selected = selectedMedia,
                                isLoading = isLoading,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                } else {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.large
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.gallery_action_other_sources),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (canAttachFiles || canCreatePoll || visibleBots.isNotEmpty()) {
                AttachBotsSection(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    bots = visibleBots,
                    canAttachFiles = canAttachFiles,
                    canCreatePoll = canCreatePoll,
                    onAttachFileClick = onAttachFileClick,
                    onCreatePollClick = onCreatePollClick,
                    onAttachBotClick = onAttachBotClick
                )
            }

            AnimatedVisibility(
                visible = canSelectMedia && hasMediaAccess && selectedMedia.isNotEmpty(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 62.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
                exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 2 }
            ) {
                ExtendedFloatingActionButton(
                    onClick = { onMediaSelected(selectedMedia.toList()) },
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null
                        )
                    },
                    text = {
                        Text(
                            stringResource(
                                R.string.action_send_items_count,
                                selectedMedia.size
                            )
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                )
            }
        }
    }
}

@Composable
private fun GalleryToolbar(
    onDismiss: () -> Unit,
    showOtherSourcesAction: Boolean,
    showCameraAction: Boolean,
    onPickFromOtherSources: () -> Unit,
    onCameraClick: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_close)
                )
            }
            Text(
                text = stringResource(R.string.gallery_title_attachments),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (showOtherSourcesAction) {
                IconButton(onClick = onPickFromOtherSources) {
                    Icon(
                        imageVector = Icons.Filled.PhotoLibrary,
                        contentDescription = stringResource(R.string.gallery_action_other_sources)
                    )
                }
            }
            if (showCameraAction) {
                IconButton(onClick = onCameraClick) {
                    Icon(
                        imageVector = Icons.Filled.PhotoCamera,
                        contentDescription = stringResource(R.string.permission_camera_title)
                    )
                }
            }
        }
    }
}

private fun buildBuckets(media: List<GalleryMediaItem>): List<BucketFilter> {
    val seen = mutableSetOf<String>()
    val customBuckets = media
        .asSequence()
        .map { it.bucketName.trim() }
        .filter { it.isNotBlank() }
        .filterNot { it.equals("Camera", ignoreCase = true) || it.equals("Screenshots", ignoreCase = true) }
        .sortedBy { it.lowercase() }
        .filter { seen.add(it.lowercase()) }
        .map { BucketFilter.Custom(it) }
        .toList()

    return buildList {
        add(BucketFilter.All)
        add(BucketFilter.Camera)
        add(BucketFilter.Screenshots)
        addAll(customBuckets)
    }
}