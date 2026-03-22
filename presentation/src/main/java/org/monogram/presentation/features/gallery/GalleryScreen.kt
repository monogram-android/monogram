package org.monogram.presentation.features.gallery

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.domain.models.AttachMenuBotModel

@Composable
fun GalleryScreen(
    onMediaSelected: (List<Uri>) -> Unit,
    onDismiss: () -> Unit,
    onCameraClick: () -> Unit,
    attachBots: List<AttachMenuBotModel>,
    hasMediaAccess: Boolean,
    onPickFromOtherSources: () -> Unit,
    onRequestMediaAccess: () -> Unit,
    onAttachBotClick: (AttachMenuBotModel) -> Unit
) {
    val context = LocalContext.current
    val mediaList = remember { mutableStateListOf<GalleryMediaItem>() }
    val selectedMedia = remember { mutableStateListOf<Uri>() }
    var filter by remember { mutableStateOf(GalleryFilter.All) }
    var bucketFilter by remember { mutableStateOf<BucketFilter>(BucketFilter.All) }
    var isOpen by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheetProgress by animateFloatAsState(
        targetValue = if (isOpen) 1f else 0f,
        animationSpec = tween(220),
        label = "gallerySheetProgress"
    )

    val dismiss: () -> Unit = {
        if (isOpen) {
            isOpen = false
            scope.launch {
                delay(180)
                onDismiss()
            }
        }
    }

    LaunchedEffect(Unit) { isOpen = true }

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

    val buckets = remember(mediaList) {
        val known = mutableListOf<BucketFilter>(BucketFilter.All, BucketFilter.Camera, BucketFilter.Screenshots)
        val dynamic = mediaList
            .map { it.bucketName }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .map { BucketFilter.Custom(it) }
        known + dynamic
    }

    val filteredMedia = remember(mediaList, filter, bucketFilter) {
        mediaList.filter { item ->
            val byType = when (filter) {
                GalleryFilter.All -> true
                GalleryFilter.Photos -> !item.isVideo
                GalleryFilter.Videos -> item.isVideo
            }
            val byBucket = if (filter == GalleryFilter.Photos) {
                when (bucketFilter) {
                    BucketFilter.All -> true
                    BucketFilter.Camera -> item.isCamera
                    BucketFilter.Screenshots -> item.isScreenshot
                    is BucketFilter.Custom -> item.bucketName.equals((bucketFilter as BucketFilter.Custom).name, ignoreCase = true)
                }
            } else {
                true
            }
            byType && byBucket
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.44f * sheetProgress))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = dismiss
                )
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .graphicsLayer {
                    translationY = (1f - sheetProgress) * 120f
                    alpha = 0.75f + 0.25f * sheetProgress
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Handle()
                Header(
                    selectedCount = selectedMedia.size,
                    onClose = dismiss,
                    onOpenCamera = onCameraClick,
                    onOpenOtherSources = onPickFromOtherSources
                )

                if (!hasMediaAccess) {
                    PermissionCard(onRequestMediaAccess)
                } else {
                    GalleryTabs(
                        filter = filter,
                        onFilterChange = { newFilter ->
                            filter = newFilter
                            if (newFilter != GalleryFilter.Photos) {
                                bucketFilter = BucketFilter.All
                            }
                        }
                    )
                    if (filter == GalleryFilter.Photos) {
                        FolderRow(
                            buckets = buckets,
                            selectedBucket = bucketFilter,
                            onBucketChange = { bucketFilter = it }
                        )
                    }
                    GalleryGrid(
                        media = filteredMedia,
                        selected = selectedMedia,
                        isLoading = isLoading,
                        modifier = Modifier.weight(1f)
                    )
                }

                AttachBotsSection(
                    bots = attachBots.filter { it.showInAttachMenu && it.name.isNotBlank() },
                    selectedCount = selectedMedia.size,
                    onSendSelected = { onMediaSelected(selectedMedia.toList()) },
                    onAttachBotClick = onAttachBotClick
                )
            }
        }
    }
}

@Composable
private fun Handle() {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .padding(top = 10.dp, bottom = 6.dp)
                .size(width = 40.dp, height = 4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        )
    }
}

@Composable
private fun Header(
    selectedCount: Int,
    onClose: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenOtherSources: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Close")
        }
        Text(
            text = "Attachments",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(
                onClick = onOpenOtherSources,
                label = { Text("Other") },
                leadingIcon = { Icon(Icons.Filled.Extension, contentDescription = null) }
            )
            AssistChip(
                onClick = onOpenCamera,
                label = { Text("Camera") },
                leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) }
            )
        }
    }
    AnimatedVisibility(visible = selectedCount > 0) {
        Text(
            text = "$selectedCount selected",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun PermissionCard(onRequestMediaAccess: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.PermMedia,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(42.dp)
                )
                Text("Allow Media Access", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Grant access to photos and videos to attach files in chat.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onRequestMediaAccess) {
                    Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Grant access")
                }
            }
        }
    }
}

@Composable
private fun GalleryTabs(
    filter: GalleryFilter,
    onFilterChange: (GalleryFilter) -> Unit
) {
    val tabs = GalleryFilter.entries
    val selectedIndex = tabs.indexOf(filter).coerceAtLeast(0)
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 4.dp),
        edgePadding = 12.dp,
        contentColor = MaterialTheme.colorScheme.primary,
        divider = {}
    ) {
        tabs.forEach { item ->
            val selected = item == filter
            Tab(
                selected = selected,
                onClick = { onFilterChange(item) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun FolderRow(
    buckets: List<BucketFilter>,
    selectedBucket: BucketFilter,
    onBucketChange: (BucketFilter) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(buckets, key = { it.key }) { bucket ->
            FilterChip(
                selected = bucket == selectedBucket,
                onClick = { onBucketChange(bucket) },
                label = { Text(bucket.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
        }
    }
}

@Composable
private fun GalleryGrid(
    media: List<GalleryMediaItem>,
    selected: MutableList<Uri>,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    if (isLoading) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (media.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("No media found", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        items(media, key = { it.uri.toString() }) { item ->
            val isSelected = selected.contains(item.uri)
            val scale by animateFloatAsState(
                targetValue = if (isSelected) 0.96f else 1f,
                animationSpec = tween(120),
                label = "mediaScale"
            )
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .scale(scale)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        if (isSelected) selected.remove(item.uri) else selected.add(item.uri)
                    }
                    .border(
                        width = if (isSelected) 2.dp else 0.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(14.dp)
                    )
            ) {
                AsyncImage(
                    model = item.uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (item.isVideo) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                    ) {
                        Text(
                            text = "VIDEO",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                    )
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachBotsSection(
    bots: List<AttachMenuBotModel>,
    selectedCount: Int,
    onSendSelected: () -> Unit,
    onAttachBotClick: (AttachMenuBotModel) -> Unit
) {
    if (bots.isEmpty() && selectedCount == 0) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        if (selectedCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(onClick = onSendSelected) {
                    Icon(Icons.Filled.Send, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Send selected ($selectedCount)")
                }
            }
            return@Card
        }
        if (bots.isNotEmpty()) {
            Text(
                text = "Attach Bots",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(bots, key = { it.botUserId }) { bot ->
                    val colors = botPillColors(bot.name)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.first)
                            .border(1.dp, colors.second.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                            .clickable { onAttachBotClick(bot) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val iconPath = bot.icon?.icon?.local?.path
                        if (!iconPath.isNullOrBlank()) {
                            AsyncImage(
                                model = iconPath,
                                contentDescription = bot.name,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Extension,
                                contentDescription = null,
                                tint = colors.second,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = bot.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.second,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun botPillColors(seed: String): Pair<Color, Color> {
    val hue = ((seed.hashCode() and 0x7fffffff) % 360).toFloat()
    val primary = Color.hsv(hue, 0.45f, 0.92f)
    val background = primary.copy(alpha = 0.14f)
    return background to primary
}

private enum class GalleryFilter(val title: String) {
    All("All"),
    Photos("Photos"),
    Videos("Videos")
}

private sealed class BucketFilter(val key: String, val title: String) {
    data object All : BucketFilter("all", "All folders")
    data object Camera : BucketFilter("camera", "Camera")
    data object Screenshots : BucketFilter("screenshots", "Screenshots")
    data class Custom(val name: String) : BucketFilter("custom_$name", name)
}

private data class GalleryMediaItem(
    val uri: Uri,
    val dateAdded: Long,
    val isVideo: Boolean,
    val bucketName: String,
    val relativePath: String,
    val isCamera: Boolean,
    val isScreenshot: Boolean
)

private fun queryImages(context: android.content.Context): List<GalleryMediaItem> {
    val result = mutableListOf<GalleryMediaItem>()
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.RELATIVE_PATH
    )
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        val bucketColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val relColumn = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
        while (cursor.moveToNext()) {
            val bucket = if (bucketColumn != -1) cursor.getString(bucketColumn).orEmpty() else ""
            val relative = if (relColumn != -1) cursor.getString(relColumn).orEmpty() else ""
            result.add(
                GalleryMediaItem(
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn)),
                    dateAdded = cursor.getLong(dateColumn),
                    isVideo = false,
                    bucketName = bucket,
                    relativePath = relative,
                    isCamera = isCameraBucket(bucket, relative),
                    isScreenshot = isScreenshotsBucket(bucket, relative)
                )
            )
        }
    }
    return result
}

private fun queryVideos(context: android.content.Context): List<GalleryMediaItem> {
    val result = mutableListOf<GalleryMediaItem>()
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Video.Media.RELATIVE_PATH
    )
    context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        "${MediaStore.Video.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        val bucketColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        val relColumn = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
        while (cursor.moveToNext()) {
            val bucket = if (bucketColumn != -1) cursor.getString(bucketColumn).orEmpty() else ""
            val relative = if (relColumn != -1) cursor.getString(relColumn).orEmpty() else ""
            result.add(
                GalleryMediaItem(
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn)),
                    dateAdded = cursor.getLong(dateColumn),
                    isVideo = true,
                    bucketName = bucket,
                    relativePath = relative,
                    isCamera = isCameraBucket(bucket, relative),
                    isScreenshot = isScreenshotsBucket(bucket, relative)
                )
            )
        }
    }
    return result
}

private fun isCameraBucket(bucket: String, relativePath: String): Boolean {
    val b = bucket.lowercase()
    val p = relativePath.lowercase()
    return b.contains("camera") || p.contains("/camera")
}

private fun isScreenshotsBucket(bucket: String, relativePath: String): Boolean {
    val b = bucket.lowercase()
    val p = relativePath.lowercase()
    return b.contains("screenshot") || p.contains("screenshots")
}
