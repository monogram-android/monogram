package org.monogram.presentation.settings.dataStorage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsSwitchTile
import org.monogram.presentation.core.ui.SettingsTile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataStorageContent(component: DataStorageComponent) {
    val state by component.state.subscribeAsState()

    val blueColor = Color(0xFF4285F4)
    val greenColor = Color(0xFF34A853)
    val orangeColor = Color(0xFFF9AB00)
    val pinkColor = Color(0xFFFF6D66)
    val redColor = Color(0xFFEA4335)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.data_storage_title_header),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = component::onBackClicked) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SectionHeader(stringResource(R.string.disk_network_usage_header))
                SettingsTile(
                    icon = Icons.Rounded.PieChart,
                    title = stringResource(R.string.storage_usage_title),
                    subtitle = stringResource(R.string.storage_usage_subtitle),
                    iconColor = orangeColor,
                    position = ItemPosition.TOP,
                    onClick = component::onStorageUsageClicked
                )
                SettingsTile(
                    icon = Icons.Rounded.DataUsage,
                    title = stringResource(R.string.network_usage_title),
                    subtitle = stringResource(R.string.network_usage_subtitle),
                    iconColor = blueColor,
                    position = ItemPosition.BOTTOM,
                    onClick = component::onNetworkUsageClicked
                )
            }

            item {
                SectionHeader(stringResource(R.string.database_header))
                SettingsTile(
                    icon = Icons.Rounded.Storage,
                    title = stringResource(R.string.database_size_title),
                    subtitle = state.databaseSize,
                    iconColor = greenColor,
                    position = ItemPosition.TOP,
                    onClick = {}
                )
                SettingsTile(
                    icon = Icons.Rounded.DeleteSweep,
                    title = stringResource(R.string.clear_database_title),
                    subtitle = stringResource(R.string.clear_database_subtitle),
                    iconColor = redColor,
                    position = ItemPosition.BOTTOM,
                    onClick = component::onClearDatabaseClicked
                )
            }

            item {
                SectionHeader(stringResource(R.string.automatic_media_download_header))
                SettingsSwitchTile(
                    icon = Icons.Rounded.SignalCellularAlt,
                    title = stringResource(R.string.when_using_mobile_data_title),
                    subtitle = if (state.autoDownloadMobile) stringResource(R.string.enabled_label) else stringResource(
                        R.string.disabled_label
                    ),
                    checked = state.autoDownloadMobile,
                    iconColor = blueColor,
                    position = ItemPosition.TOP,
                    onCheckedChange = component::onAutoDownloadMobileChanged
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.Wifi,
                    title = stringResource(R.string.when_connected_on_wifi_title),
                    subtitle = if (state.autoDownloadWifi) stringResource(R.string.enabled_label) else stringResource(R.string.disabled_label),
                    checked = state.autoDownloadWifi,
                    iconColor = greenColor,
                    position = ItemPosition.MIDDLE,
                    onCheckedChange = component::onAutoDownloadWifiChanged
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.Public,
                    title = stringResource(R.string.when_roaming_title),
                    subtitle = if (state.autoDownloadRoaming) stringResource(R.string.enabled_label) else stringResource(
                        R.string.disabled_label
                    ),
                    checked = state.autoDownloadRoaming,
                    iconColor = orangeColor,
                    position = ItemPosition.MIDDLE,
                    onCheckedChange = component::onAutoDownloadRoamingChanged
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.FileDownload,
                    title = stringResource(R.string.auto_download_files_title),
                    subtitle = stringResource(R.string.auto_download_files_subtitle),
                    checked = state.autoDownloadFiles,
                    iconColor = blueColor,
                    position = ItemPosition.MIDDLE,
                    onCheckedChange = component::onAutoDownloadFilesChanged
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.StickyNote2,
                    title = stringResource(R.string.auto_download_stickers_title),
                    subtitle = stringResource(R.string.auto_download_stickers_subtitle),
                    checked = state.autoDownloadStickers,
                    iconColor = pinkColor,
                    position = ItemPosition.MIDDLE,
                    onCheckedChange = component::onAutoDownloadStickersChanged
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.VideoFile,
                    title = stringResource(R.string.auto_download_video_notes_title),
                    subtitle = stringResource(R.string.auto_download_video_notes_subtitle),
                    checked = state.autoDownloadVideoNotes,
                    iconColor = blueColor,
                    position = ItemPosition.BOTTOM,
                    onCheckedChange = component::onAutoDownloadVideoNotesChanged
                )
            }

            item {
                SectionHeader(stringResource(R.string.autoplay_media_header))
                SettingsSwitchTile(
                    icon = Icons.Rounded.Gif,
                    title = stringResource(R.string.gifs_title),
                    subtitle = stringResource(R.string.gifs_autoplay_subtitle),
                    checked = state.autoplayGifs,
                    iconColor = pinkColor,
                    position = ItemPosition.TOP,
                    onCheckedChange = component::onAutoplayGifsChanged
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.PlayCircle,
                    title = stringResource(R.string.videos_title),
                    subtitle = stringResource(R.string.videos_autoplay_subtitle),
                    checked = state.autoplayVideos,
                    iconColor = blueColor,
                    position = ItemPosition.BOTTOM,
                    onCheckedChange = component::onAutoplayVideosChanged
                )
            }

            item { Spacer(modifier = Modifier.height(padding.calculateBottomPadding()-16.dp)) }
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
