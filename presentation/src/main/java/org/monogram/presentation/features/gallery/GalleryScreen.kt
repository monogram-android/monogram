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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.domain.models.AttachMenuBotModel
import org.monogram.presentation.R
import org.monogram.presentation.features.gallery.components.AttachBotsSection
import org.monogram.presentation.features.gallery.components.FolderRow
import org.monogram.presentation.features.gallery.components.GalleryGrid
import org.monogram.presentation.features.gallery.components.GalleryTabs
import org.monogram.presentation.features.gallery.components.GalleryTopBar
import org.monogram.presentation.features.gallery.components.PartialAccessCard
import org.monogram.presentation.features.gallery.components.PermissionCard
import org.monogram.presentation.features.gallery.components.SectionHeader
import org.monogram.presentation.features.gallery.components.SelectedCountCard

@Composable
fun GalleryScreen(
    onMediaSelected: (List<Uri>) -> Unit,
    onDismiss: () -> Unit,
    onCameraClick: () -> Unit,
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
    var filter by remember { mutableStateOf(GalleryFilter.All) }
    var bucketFilter by remember { mutableStateOf<BucketFilter>(BucketFilter.All) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(hasMediaAccess) {
        if (!hasMediaAccess) {
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

    LaunchedEffect(filter, buckets) {
        if (filter != GalleryFilter.Photos) return@LaunchedEffect
        if (bucketFilter !in buckets) {
            bucketFilter = BucketFilter.All
        }
    }

    val filteredMedia = mediaList.filter { item ->
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
                is BucketFilter.Custom -> item.bucketName.equals(selectedBucket.name, ignoreCase = true)
            }
        } else {
            true
        }
        byType && byBucket
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            GalleryTopBar(
                onDismiss = onDismiss,
                onPickFromOtherSources = onPickFromOtherSources,
                onCameraClick = onCameraClick
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AttachBotsSection(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                bots = attachBots.filter { it.showInAttachMenu && it.name.isNotBlank() },
                selectedCount = selectedMedia.size,
                canCreatePoll = canCreatePoll,
                onAttachFileClick = onAttachFileClick,
                onCreatePollClick = onCreatePollClick,
                onSendSelected = { onMediaSelected(selectedMedia.toList()) },
                onAttachBotClick = onAttachBotClick
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding()
                )
        ) {
            AnimatedVisibility(visible = selectedMedia.isNotEmpty()) {
                SelectedCountCard(
                    selectedCount = selectedMedia.size,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 10.dp)
                )
            }

            if (!hasMediaAccess) {
                SectionHeader(stringResource(R.string.permissions))
                PermissionCard(onRequestMediaAccess)
                return@Column
            }

            if (isPartialAccess) {
                PartialAccessCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 10.dp),
                    onManageAccessClick = onRequestMediaAccess
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    GalleryTabs(
                        filter = filter,
                        onFilterChange = { newFilter ->
                            filter = newFilter
                        }
                    )
                    AnimatedVisibility(
                        visible = filter == GalleryFilter.Photos,
                        enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { -it / 3 },
                        exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { -it / 4 }
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
                            fadeIn(tween(200)) togetherWith fadeOut(tween(130))
                        },
                        label = "galleryGridTransition",
                        modifier = Modifier.weight(1f)
                    ) {
                        GalleryGrid(
                            media = filteredMedia,
                            selected = selectedMedia,
                            isLoading = isLoading,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
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