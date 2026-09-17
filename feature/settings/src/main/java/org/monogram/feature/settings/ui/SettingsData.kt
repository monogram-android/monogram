package org.monogram.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.components.ItemPosition
import org.monogram.core.ui.components.PeerAvatar
import org.monogram.core.ui.components.SectionHeader
import org.monogram.core.ui.components.SettingsGroupCorner
import org.monogram.core.ui.components.SettingsTile
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.loading.MonogramLoadingInlineSize
import org.monogram.feature.settings.R
import org.monogram.feature.settings.SettingsStore
import org.monogram.network.http.FileCache
import java.util.Locale

@Composable
private fun storageCategoryIcon(kind: String): androidx.compose.ui.graphics.vector.ImageVector = when (kind) {
    FileCache.KIND_PHOTOS -> Icons.Outlined.Image
    FileCache.KIND_VIDEOS -> Icons.Outlined.Videocam
    FileCache.KIND_FILES -> Icons.AutoMirrored.Outlined.InsertDriveFile
    FileCache.KIND_STICKERS -> Icons.Outlined.EmojiEmotions
    else -> Icons.Outlined.Storage
}

@Composable
private fun storageCategoryColor(kind: String): androidx.compose.ui.graphics.Color = when (kind) {
    FileCache.KIND_PHOTOS -> MaterialTheme.colorScheme.primary
    FileCache.KIND_VIDEOS -> MaterialTheme.colorScheme.tertiary
    FileCache.KIND_FILES -> MaterialTheme.colorScheme.secondary
    FileCache.KIND_STICKERS -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun ChatStorageRow(
    row: org.monogram.feature.settings.SettingsStore.CacheChatRow,
    position: ItemPosition,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = when (position) {
            ItemPosition.TOP -> androidx.compose.foundation.shape.RoundedCornerShape(
                topStart = org.monogram.core.ui.components.SettingsGroupCorner,
                topEnd = org.monogram.core.ui.components.SettingsGroupCorner,
            )
            ItemPosition.MIDDLE -> androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
            ItemPosition.BOTTOM -> androidx.compose.foundation.shape.RoundedCornerShape(
                bottomStart = org.monogram.core.ui.components.SettingsGroupCorner,
                bottomEnd = org.monogram.core.ui.components.SettingsGroupCorner,
            )
            ItemPosition.STANDALONE ->
                androidx.compose.foundation.shape.RoundedCornerShape(org.monogram.core.ui.components.SettingsGroupCorner)
        },
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeerAvatar(title = row.title, size = 40.dp)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = row.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = formatBytes(row.bytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun LazyListScope.dataItems(
    cacheBytes: Long,
    cacheByKind: Map<String, Long>,
    cacheChats: List<org.monogram.feature.settings.SettingsStore.CacheChatRow>,
    cacheMessage: String?,
    loading: Boolean,
    speedUpUploads: Boolean,
    onSpeedUpUploads: (Boolean) -> Unit,
    onClear: () -> Unit,
    onClearChat: (Long) -> Unit,
    onClearKind: (String) -> Unit,
) {
    item { Spacer(Modifier.height(8.dp)) }
    item { SectionHeader(stringResource(R.string.settings_data_transfers)) }
    item {
        SettingsTile(
            icon = Icons.Outlined.CloudUpload,
            title = stringResource(R.string.settings_speed_up_uploads),
            subtitle = stringResource(R.string.settings_speed_up_uploads_sub),
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.STANDALONE,
            onClick = { onSpeedUpUploads(!speedUpUploads) },
            trailingContent = {
                Switch(
                    checked = speedUpUploads,
                    onCheckedChange = onSpeedUpUploads,
                )
            },
        )
    }
    item { Spacer(Modifier.height(8.dp)) }
    item {
        val photos = cacheByKind[FileCache.KIND_PHOTOS] ?: 0L
        val videos = cacheByKind[FileCache.KIND_VIDEOS] ?: 0L
        val files = cacheByKind[FileCache.KIND_FILES] ?: 0L
        val stickers = cacheByKind[FileCache.KIND_STICKERS] ?: 0L
        val other = cacheByKind[FileCache.KIND_OTHER] ?: 0L
        val slices = listOf(
            storageCategoryColor(FileCache.KIND_PHOTOS) to photos,
            storageCategoryColor(FileCache.KIND_VIDEOS) to videos,
            storageCategoryColor(FileCache.KIND_FILES) to files,
            storageCategoryColor(FileCache.KIND_STICKERS) to stickers,
            storageCategoryColor(FileCache.KIND_OTHER) to other,
        ).filter { it.second > 0L }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StorageRing(slices = slices)
            Spacer(Modifier.height(12.dp))
            Text(
                text = formatBytes(cacheBytes),
                style = MaterialTheme.typography.headlineMediumEmphasized,
            )
            val extra = cacheMessage?.removePrefix("cleared:")?.toLongOrNull()
            if (extra != null) {
                Text(
                    text = stringResource(R.string.settings_cache_cleared, formatBytes(extra)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    item { Spacer(Modifier.height(8.dp)) }
    val kinds = listOf(
        FileCache.KIND_PHOTOS to R.string.settings_storage_photos,
        FileCache.KIND_VIDEOS to R.string.settings_storage_videos,
        FileCache.KIND_FILES to R.string.settings_storage_files,
        FileCache.KIND_STICKERS to R.string.settings_storage_stickers,
        FileCache.KIND_OTHER to R.string.settings_storage_other,
    )
    kinds.forEachIndexed { index, (kind, label) ->
        item {
            val bytes = cacheByKind[kind] ?: 0L
            SettingsTile(
                icon = storageCategoryIcon(kind),
                title = stringResource(label),
                subtitle = formatBytes(bytes),
                iconColor = storageCategoryColor(kind),
                titleColor = if (bytes > 0L) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                position = when (index) {
                    0 -> ItemPosition.TOP
                    kinds.lastIndex -> ItemPosition.BOTTOM
                    else -> ItemPosition.MIDDLE
                },
                enabled = !loading && bytes > 0L,
                onClick = { onClearKind(kind) },
            )
        }
    }
    item { Spacer(Modifier.height(8.dp)) }
    item {
        SettingsTile(
            icon = Icons.Outlined.DeleteSweep,
            title = stringResource(R.string.settings_clear_cache),
            subtitle = stringResource(R.string.settings_cache_size, formatBytes(cacheBytes)),
            iconColor = MaterialTheme.colorScheme.error,
            titleColor = MaterialTheme.colorScheme.error,
            position = ItemPosition.STANDALONE,
            enabled = !loading,
            onClick = onClear,
            trailingContent = {
                MonogramLoading(
                    visible = loading,
                    size = MonogramLoadingInlineSize,
                    color = MaterialTheme.colorScheme.error,
                )
            },
        )
    }
    item { SectionHeader(stringResource(R.string.settings_storage_chats)) }
    if (cacheChats.isEmpty()) {
        item {
            Text(
                text = stringResource(R.string.settings_storage_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    } else {
        val sortedChats = cacheChats.sortedByDescending { it.bytes }
        sortedChats.forEachIndexed { index, row ->
            item {
                ChatStorageRow(
                    row = row,
                    position = when {
                        sortedChats.size == 1 -> ItemPosition.STANDALONE
                        index == 0 -> ItemPosition.TOP
                        index == sortedChats.lastIndex -> ItemPosition.BOTTOM
                        else -> ItemPosition.MIDDLE
                    },
                    enabled = !loading,
                    onClick = { onClearChat(row.chatId) },
                )
            }
        }
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}
