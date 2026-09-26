package org.monogram.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.AutoDownloadNetwork
import org.monogram.core.ui.AutoDownloadPreset
import org.monogram.core.ui.DownloadSettings
import org.monogram.core.ui.components.ItemPosition
import org.monogram.core.ui.components.SectionHeader
import org.monogram.core.ui.components.SettingsCard
import org.monogram.core.ui.components.SettingsTile
import org.monogram.feature.settings.R
import java.util.Locale
import kotlin.math.abs

internal fun parseAutoDownloadNetwork(raw: String): AutoDownloadNetwork = when (raw) {
    "mobile" -> AutoDownloadNetwork.Mobile
    "roaming" -> AutoDownloadNetwork.Roaming
    else -> AutoDownloadNetwork.Wifi
}

internal fun autoDownloadNetworkKey(network: AutoDownloadNetwork): String = when (network) {
    AutoDownloadNetwork.Wifi -> "wifi"
    AutoDownloadNetwork.Mobile -> "mobile"
    AutoDownloadNetwork.Roaming -> "roaming"
}

internal fun formatAutoDownloadLimit(bytes: Long): String {
    val gb = 1024L * 1024L * 1024L
    if (bytes >= gb) {
        val value = bytes / gb.toDouble()
        return if (abs(value - value.toLong()) < 0.05) "${value.toLong()} GB"
        else String.format(Locale.US, "%.1f GB", value)
    }
    val mb = 1024L * 1024L
    if (bytes >= mb) {
        val value = bytes / mb.toDouble()
        return if (abs(value - value.toLong()) < 0.05) "${value.toLong()} MB"
        else String.format(Locale.US, "%.1f MB", value)
    }
    return "${(bytes / 1024L).coerceAtLeast(1L)} KB"
}

internal fun autoDownloadSummary(preset: AutoDownloadPreset): String {
    if (!preset.enabled) return ""
    val parts = buildList {
        if (preset.photos) add("photos")
        if (preset.videos) add("videos:${preset.maxVideoBytes}")
        if (preset.gifs) add("gifs:${preset.maxGifBytes}")
        if (preset.files) add("files:${preset.maxFileBytes}")
    }
    return parts.joinToString(",")
}

internal fun LazyListScope.autoDownloadItems(network: AutoDownloadNetwork, preset: AutoDownloadPreset) {
    item { Spacer(Modifier.height(8.dp)) }
    item { SectionHeader(stringResource(R.string.settings_autodownload)) }
    item {
        SettingsTile(
            icon = Icons.Outlined.PlayCircle,
            title = stringResource(R.string.settings_autodownload_enable),
            subtitle = stringResource(R.string.settings_autodownload_enable_sub),
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.STANDALONE,
            onClick = {
                DownloadSettings.setPreset(network, preset.copy(enabled = !preset.enabled))
            },
            trailingContent = {
                Switch(
                    checked = preset.enabled,
                    onCheckedChange = { enabled ->
                        DownloadSettings.setPreset(network, preset.copy(enabled = enabled))
                    },
                )
            },
        )
    }
    item { Spacer(Modifier.height(8.dp)) }
    mediaToggle(
        icon = Icons.Outlined.Image,
        titleRes = R.string.settings_autodownload_photos,
        checked = preset.photos,
        enabled = preset.enabled,
        position = ItemPosition.TOP,
        onCheckedChange = { DownloadSettings.setPreset(network, preset.copy(photos = it)) },
    )
    mediaToggle(
        icon = Icons.Outlined.Videocam,
        titleRes = R.string.settings_autodownload_videos,
        checked = preset.videos,
        enabled = preset.enabled,
        position = ItemPosition.MIDDLE,
        onCheckedChange = { DownloadSettings.setPreset(network, preset.copy(videos = it)) },
        limitBytes = preset.maxVideoBytes.takeIf { preset.videos },
        onLimitChange = { DownloadSettings.setPreset(network, preset.copy(maxVideoBytes = it)) },
    )
    mediaToggle(
        icon = Icons.Outlined.Gif,
        titleRes = R.string.settings_autodownload_gifs,
        checked = preset.gifs,
        enabled = preset.enabled,
        position = ItemPosition.MIDDLE,
        onCheckedChange = { DownloadSettings.setPreset(network, preset.copy(gifs = it)) },
        limitBytes = preset.maxGifBytes.takeIf { preset.gifs },
        onLimitChange = { DownloadSettings.setPreset(network, preset.copy(maxGifBytes = it)) },
    )
    mediaToggle(
        icon = Icons.AutoMirrored.Outlined.InsertDriveFile,
        titleRes = R.string.settings_autodownload_files,
        checked = preset.files,
        enabled = preset.enabled,
        position = ItemPosition.BOTTOM,
        onCheckedChange = { DownloadSettings.setPreset(network, preset.copy(files = it)) },
        limitBytes = preset.maxFileBytes.takeIf { preset.files },
        onLimitChange = { DownloadSettings.setPreset(network, preset.copy(maxFileBytes = it)) },
    )
    item { Spacer(Modifier.height(8.dp)) }
    item {
        SettingsTile(
            icon = Icons.Outlined.Videocam,
            title = stringResource(R.string.settings_autodownload_preload_video),
            subtitle = stringResource(R.string.settings_autodownload_preload_video_sub),
            iconColor = MaterialTheme.colorScheme.tertiary,
            position = ItemPosition.STANDALONE,
            enabled = preset.enabled && preset.videos,
            onClick = {
                DownloadSettings.setPreset(network, preset.copy(preloadVideo = !preset.preloadVideo))
            },
            trailingContent = {
                Switch(
                    checked = preset.preloadVideo,
                    enabled = preset.enabled && preset.videos,
                    onCheckedChange = { enabled ->
                        DownloadSettings.setPreset(network, preset.copy(preloadVideo = enabled))
                    },
                )
            },
        )
    }
}

private fun LazyListScope.mediaToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    titleRes: Int,
    checked: Boolean,
    enabled: Boolean,
    position: ItemPosition,
    onCheckedChange: (Boolean) -> Unit,
    limitBytes: Long? = null,
    onLimitChange: ((Long) -> Unit)? = null,
) {
    item {
        val title = stringResource(titleRes)
        SettingsTile(
            icon = icon,
            title = title,
            subtitle = limitBytes?.let { stringResource(R.string.settings_autodownload_limit, formatAutoDownloadLimit(it)) },
            iconColor = MaterialTheme.colorScheme.primary,
            position = if (limitBytes != null) ItemPosition.MIDDLE else position,
            enabled = enabled,
            onClick = { onCheckedChange(!checked) },
            trailingContent = {
                Switch(
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = onCheckedChange,
                )
            },
        )
    }
    if (limitBytes != null && onLimitChange != null) {
        item {
            SizeLimitCard(
                titleRes = titleRes,
                bytes = limitBytes,
                enabled = enabled,
                position = if (position == ItemPosition.BOTTOM) ItemPosition.BOTTOM else ItemPosition.MIDDLE,
                onBytesChange = onLimitChange,
            )
        }
    }
}

@Composable
private fun SizeLimitCard(
    titleRes: Int,
    bytes: Long,
    enabled: Boolean,
    position: ItemPosition,
    onBytesChange: (Long) -> Unit,
) {
    val title = stringResource(titleRes)
    val steps = AutoDownloadPreset.SIZE_STEPS
    val index = steps.indexOfFirst { it >= bytes }.let { if (it < 0) steps.lastIndex else it }
    SettingsCard(position = position, enabled = enabled) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Slider(
                value = index.toFloat(),
                onValueChange = { value ->
                    onBytesChange(steps[value.toInt().coerceIn(0, steps.lastIndex)])
                },
                valueRange = 0f..steps.lastIndex.toFloat(),
                steps = (steps.size - 2).coerceAtLeast(0),
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = "$title, ${formatAutoDownloadLimit(bytes)}"
                },
            )
        }
    }
}
