package org.monogram.presentation.features.gallery

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.domain.models.AttachMenuBotModel

@OptIn(ExperimentalMaterial3Api::class)
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

    val buckets = buildList {
        add(BucketFilter.All)
        add(BucketFilter.Camera)
        add(BucketFilter.Screenshots)

        mediaList
            .map { it.bucketName }
            .filter { it.isNotBlank() }
            .filterNot { it.equals("Camera", ignoreCase = true) || it.equals("Screenshots", ignoreCase = true) }
            .distinct()
            .sorted()
            .mapTo(this) { BucketFilter.Custom(it) }
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
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Attachments",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    IconButton(onClick = onPickFromOtherSources) {
                        Icon(Icons.Filled.Extension, contentDescription = "Other sources")
                    }
                    IconButton(onClick = onCameraClick) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = "Camera")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AttachBotsSection(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                bots = attachBots.filter { it.showInAttachMenu && it.name.isNotBlank() },
                selectedCount = selectedMedia.size,
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
                SectionHeader("Permissions")
                PermissionCard(onRequestMediaAccess)
                return@Column
            }

            SectionHeader("Media")
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
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp, top = 16.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun SelectedCountCard(
    selectedCount: Int,
    modifier: Modifier = Modifier
) {
    val suffix = if (selectedCount == 1) "" else "s"
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "$selectedCount item$suffix selected",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Ready to send",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(onRequestMediaAccess: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
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

private data class GalleryTabSpec(
    val filter: GalleryFilter,
    val icon: ImageVector
)

@Composable
private fun GalleryTabs(
    filter: GalleryFilter,
    onFilterChange: (GalleryFilter) -> Unit
) {
    val tabs = listOf(
        GalleryTabSpec(GalleryFilter.All, Icons.Filled.PermMedia),
        GalleryTabSpec(GalleryFilter.Photos, Icons.Filled.Image),
        GalleryTabSpec(GalleryFilter.Videos, Icons.Filled.Videocam)
    )

    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .fillMaxWidth()
            .height(48.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                CircleShape
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        tabs.forEach { tab ->
            val selected = filter == tab.filter
            val backgroundColor by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                animationSpec = tween(durationMillis = 200),
                label = "tabBackground"
            )
            val contentColor by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(durationMillis = 200),
                label = "tabContent"
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(backgroundColor)
                    .selectable(
                        selected = selected,
                        onClick = { onFilterChange(tab.filter) },
                        role = Role.Tab
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = contentColor
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = tab.filter.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
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
    modifier: Modifier = Modifier,
    bots: List<AttachMenuBotModel>,
    selectedCount: Int,
    onSendSelected: () -> Unit,
    onAttachBotClick: (AttachMenuBotModel) -> Unit
) {
    if (bots.isEmpty() && selectedCount == 0) return

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        if (selectedCount > 0) {
            val suffix = if (selectedCount == 1) "" else "s"
            Button(
                onClick = onSendSelected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = "Send $selectedCount item$suffix",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            return@Surface
        }

        if (bots.isNotEmpty()) {
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