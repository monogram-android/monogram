package org.monogram.presentation.features.gallery.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.presentation.R
import org.monogram.presentation.features.gallery.BucketFilter
import org.monogram.presentation.features.gallery.GalleryFilter

private data class GalleryTabSpec(
    val filter: GalleryFilter,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
fun GalleryTabs(
    filter: GalleryFilter,
    onFilterChange: (GalleryFilter) -> Unit
) {
    val tabs = listOf(
        GalleryTabSpec(GalleryFilter.All, Icons.Filled.PermMedia),
        GalleryTabSpec(GalleryFilter.Photos, Icons.Filled.Image),
        GalleryTabSpec(GalleryFilter.Videos, Icons.Filled.Videocam)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEach { tab ->
            val selected = filter == tab.filter
            val containerColor by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                animationSpec = tween(200),
                label = "galleryTabContainer"
            )
            val contentColor by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(200),
                label = "galleryTabContent"
            )
            val elevation by animateDpAsState(
                targetValue = if (selected) 2.dp else 0.dp,
                animationSpec = tween(180),
                label = "galleryTabElevation"
            )

            Surface(
                modifier = Modifier.weight(1f),
                onClick = { onFilterChange(tab.filter) },
                shape = RoundedCornerShape(18.dp),
                color = containerColor,
                tonalElevation = elevation
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = tab.filter.label(),
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
fun FolderRow(
    buckets: List<BucketFilter>,
    selectedBucket: BucketFilter,
    onBucketChange: (BucketFilter) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(buckets, key = { it.key }) { bucket ->
            val selected = bucket == selectedBucket
            Surface(
                onClick = { onBucketChange(bucket) },
                shape = RoundedCornerShape(16.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = bucket.label(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryFilter.label(): String {
    return when (this) {
        GalleryFilter.All -> stringResource(R.string.gallery_filter_all)
        GalleryFilter.Photos -> stringResource(R.string.gallery_filter_photos)
        GalleryFilter.Videos -> stringResource(R.string.gallery_filter_videos)
    }
}

@Composable
private fun BucketFilter.label(): String {
    return when (this) {
        BucketFilter.All -> stringResource(R.string.gallery_bucket_all_folders)
        BucketFilter.Camera -> stringResource(R.string.permission_camera_title)
        BucketFilter.Screenshots -> stringResource(R.string.gallery_bucket_screenshots)
        is BucketFilter.Custom -> name
    }
}
