package org.monogram.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.common.Outcome
import org.monogram.core.models.PeerId
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.ui.AutoDownloadNetwork
import org.monogram.core.ui.AutoDownloadPreset
import org.monogram.core.ui.DownloadSettings
import org.monogram.core.ui.DownloadState
import org.monogram.core.ui.components.ItemPosition
import org.monogram.core.ui.components.PeerAvatar
import org.monogram.core.ui.components.SectionHeader
import org.monogram.core.ui.components.SettingsGroupCorner
import org.monogram.core.ui.components.SettingsTile
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.loading.MonogramLoadingInlineSize
import org.monogram.core.ui.rememberCacheGeneration
import org.monogram.core.ui.rememberEnsuredFile
import org.monogram.feature.settings.R
import org.monogram.feature.settings.SettingsStore
import org.monogram.network.http.FileCache
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository
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
    mediaRepository: MediaRepository?,
    position: ItemPosition,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val peerId = remember(row.chatId) { PeerId(row.chatId) }
    val cacheKey = peerAvatarCacheKey(peerId, row.photoCacheKey)
    val avatarFile = rememberEnsuredFile(
        generation = rememberCacheGeneration(
            remember(mediaRepository, peerId, cacheKey) {
                mediaRepository?.cacheGeneration(cacheKey)
            },
        ),
        identity = row.chatId to cacheKey,
        resolve = {
            mediaRepository?.cachedFile(cacheKey) ?: mediaRepository?.cachedAvatar(peerId)
        },
        ensure = {
            val repo = mediaRepository ?: return@rememberEnsuredFile null
            when (val result = repo.ensureLocalAvatar(peerId, cacheKey, MediaPriority.THUMB)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> repo.cachedAvatar(peerId)
            }
        },
    )
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
            PeerAvatar(title = row.title, size = 40.dp, imageFile = avatarFile)
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
    download: DownloadState,
    onSpeedUpUploads: (Boolean) -> Unit,
    onSpeedUpDownloads: (Boolean) -> Unit,
    onOpenAutoDownload: (AutoDownloadNetwork) -> Unit,
    onClear: () -> Unit,
    onClearChat: (Long) -> Unit,
    onClearKind: (String) -> Unit,
    onOpenDebugStats: (() -> Unit)? = null,
    mediaRepository: MediaRepository? = null,
) {
    item { Spacer(Modifier.height(8.dp)) }
    item { SectionHeader(stringResource(R.string.settings_autodownload)) }
    item {
        SettingsTile(
            icon = Icons.Outlined.Wifi,
            title = stringResource(R.string.settings_autodownload_wifi),
            subtitle = autoDownloadPresetSummary(download.wifi),
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.TOP,
            onClick = { onOpenAutoDownload(AutoDownloadNetwork.Wifi) },
            trailingContent = { DataChevron() },
        )
    }
    item {
        SettingsTile(
            icon = Icons.Outlined.SignalCellularAlt,
            title = stringResource(R.string.settings_autodownload_mobile),
            subtitle = autoDownloadPresetSummary(download.mobile),
            iconColor = MaterialTheme.colorScheme.secondary,
            position = ItemPosition.MIDDLE,
            onClick = { onOpenAutoDownload(AutoDownloadNetwork.Mobile) },
            trailingContent = { DataChevron() },
        )
    }
    item {
        SettingsTile(
            icon = Icons.Outlined.CellTower,
            title = stringResource(R.string.settings_autodownload_roaming),
            subtitle = autoDownloadPresetSummary(download.roaming),
            iconColor = MaterialTheme.colorScheme.tertiary,
            position = ItemPosition.BOTTOM,
            onClick = { onOpenAutoDownload(AutoDownloadNetwork.Roaming) },
            trailingContent = { DataChevron() },
        )
    }
    item { Spacer(Modifier.height(8.dp)) }
    item {
        SettingsTile(
            icon = Icons.Outlined.RestartAlt,
            title = stringResource(R.string.settings_autodownload_reset),
            subtitle = stringResource(R.string.settings_autodownload_reset_sub),
            iconColor = MaterialTheme.colorScheme.error,
            position = ItemPosition.STANDALONE,
            onClick = DownloadSettings::resetAutoDownload,
        )
    }
    if (onOpenDebugStats != null) {
        item { Spacer(Modifier.height(8.dp)) }
        item {
            SettingsTile(
                icon = Icons.Outlined.BugReport,
                title = stringResource(R.string.settings_debug_stats),
                subtitle = stringResource(R.string.settings_debug_stats_sub),
                iconColor = MaterialTheme.colorScheme.tertiary,
                position = ItemPosition.STANDALONE,
                onClick = onOpenDebugStats,
                trailingContent = { DataChevron() },
            )
        }
        item {
            val network = download.activeNetwork
            SettingsTile(
                icon = Icons.Outlined.CellTower,
                title = stringResource(R.string.settings_debug_simulate_network),
                subtitle = stringResource(
                    when (network) {
                        AutoDownloadNetwork.Wifi -> R.string.settings_debug_simulate_wifi
                        AutoDownloadNetwork.Mobile -> R.string.settings_debug_simulate_mobile
                        AutoDownloadNetwork.Roaming -> R.string.settings_debug_simulate_roaming
                    },
                ),
                iconColor = MaterialTheme.colorScheme.secondary,
                position = ItemPosition.STANDALONE,
                onClick = org.monogram.core.ui.DownloadSettings::cycleDebugNetwork,
            )
        }
    }
    item { Spacer(Modifier.height(8.dp)) }
    item { SectionHeader(stringResource(R.string.settings_autoplay)) }
    item {
        SettingsTile(
            icon = Icons.Outlined.Gif,
            title = stringResource(R.string.settings_autoplay_gifs),
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.TOP,
            onClick = { DownloadSettings.setAutoplayGifs(!download.autoplayGifs) },
            trailingContent = {
                Switch(
                    checked = download.autoplayGifs,
                    onCheckedChange = DownloadSettings::setAutoplayGifs,
                )
            },
        )
    }
    item {
        SettingsTile(
            icon = Icons.Outlined.Videocam,
            title = stringResource(R.string.settings_autoplay_videos),
            subtitle = stringResource(R.string.settings_autoplay_videos_sub),
            iconColor = MaterialTheme.colorScheme.secondary,
            position = ItemPosition.BOTTOM,
            onClick = { DownloadSettings.setAutoplayVideos(!download.autoplayVideos) },
            trailingContent = {
                Switch(
                    checked = download.autoplayVideos,
                    onCheckedChange = DownloadSettings::setAutoplayVideos,
                )
            },
        )
    }
    item { Spacer(Modifier.height(8.dp)) }
    item { SectionHeader(stringResource(R.string.settings_data_transfers)) }
    item {
        SettingsTile(
            icon = Icons.Outlined.CloudDownload,
            title = stringResource(R.string.settings_speed_up_downloads),
            subtitle = stringResource(R.string.settings_speed_up_downloads_sub),
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.TOP,
            onClick = { onSpeedUpDownloads(!download.speedUpDownloads) },
            trailingContent = {
                Switch(
                    checked = download.speedUpDownloads,
                    onCheckedChange = onSpeedUpDownloads,
                )
            },
        )
    }
    item {
        SettingsTile(
            icon = Icons.Outlined.CloudUpload,
            title = stringResource(R.string.settings_speed_up_uploads),
            subtitle = stringResource(R.string.settings_speed_up_uploads_sub),
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.BOTTOM,
            onClick = { onSpeedUpUploads(!download.speedUpUploads) },
            trailingContent = {
                Switch(
                    checked = download.speedUpUploads,
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
                    mediaRepository = mediaRepository,
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

@Composable
internal fun autoDownloadPresetSummary(preset: AutoDownloadPreset): String {
    if (!preset.enabled) return stringResource(R.string.settings_autodownload_off)
    val parts = buildList {
        if (preset.photos) add(stringResource(R.string.settings_autodownload_summary_photos))
        if (preset.videos) {
            add(
                stringResource(
                    R.string.settings_autodownload_summary_videos,
                    formatAutoDownloadLimit(preset.maxVideoBytes),
                ),
            )
        }
        if (preset.gifs) {
            add(
                stringResource(
                    R.string.settings_autodownload_summary_gifs,
                    formatAutoDownloadLimit(preset.maxGifBytes),
                ),
            )
        }
        if (preset.files) {
            add(
                stringResource(
                    R.string.settings_autodownload_summary_files,
                    formatAutoDownloadLimit(preset.maxFileBytes),
                ),
            )
        }
    }
    return parts.joinToString(", ").ifEmpty { stringResource(R.string.settings_autodownload_off) }
}

@Composable
private fun DataChevron() {
    Icon(
        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
