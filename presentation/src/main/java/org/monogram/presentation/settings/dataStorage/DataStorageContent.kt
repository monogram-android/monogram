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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
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
                        text = "Data and Storage",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = component::onBackClicked) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
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
                SectionHeader("Disk and network usage")
                SettingsTile(
                    icon = Icons.Rounded.PieChart,
                    title = "Storage Usage",
                    subtitle = "Manage your local cache",
                    iconColor = orangeColor,
                    position = ItemPosition.TOP,
                    onClick = component::onStorageUsageClicked
                )
                SettingsTile(
                    icon = Icons.Rounded.DataUsage,
                    title = "Network Usage",
                    subtitle = "View data sent and received",
                    iconColor = blueColor,
                    position = ItemPosition.BOTTOM,
                    onClick = component::onNetworkUsageClicked
                )
            }

            item {
                SectionHeader("Database")
                SettingsTile(
                    icon = Icons.Rounded.Storage,
                    title = "Database Size",
                    subtitle = state.databaseSize,
                    iconColor = greenColor,
                    position = ItemPosition.TOP,
                    onClick = {}
                )
                SettingsTile(
                    icon = Icons.Rounded.DeleteSweep,
                    title = "Clear Database",
                    subtitle = "Delete all cached chats and messages",
                    iconColor = redColor,
                    position = ItemPosition.BOTTOM,
                    onClick = component::onClearDatabaseClicked
                )
            }

            item {
                SectionHeader("Automatic media download")
                SettingsSwitchTile(
                    icon = Icons.Rounded.SignalCellularAlt,
                    title = "When using mobile data",
                    subtitle = if (state.autoDownloadMobile) "Enabled" else "Disabled",
                    checked = state.autoDownloadMobile,
                    iconColor = blueColor,
                    position = ItemPosition.TOP,
                    onCheckedChange = component::onAutoDownloadMobileChanged
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.Wifi,
                    title = "When connected on Wi-Fi",
                    subtitle = if (state.autoDownloadWifi) "Enabled" else "Disabled",
                    checked = state.autoDownloadWifi,
                    iconColor = greenColor,
                    position = ItemPosition.MIDDLE,
                    onCheckedChange = component::onAutoDownloadWifiChanged
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.Public,
                    title = "When roaming",
                    subtitle = if (state.autoDownloadRoaming) "Enabled" else "Disabled",
                    checked = state.autoDownloadRoaming,
                    iconColor = orangeColor,
                    position = ItemPosition.MIDDLE,
                    onCheckedChange = component::onAutoDownloadRoamingChanged
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.FileDownload,
                    title = "Auto Download files",
                    subtitle = "Automatically download incoming files",
                    checked = state.autoDownloadFiles,
                    iconColor = blueColor,
                    position = ItemPosition.MIDDLE,
                    onCheckedChange = component::onAutoDownloadFilesChanged
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.StickyNote2,
                    title = "Auto Download stickers",
                    subtitle = "Automatically download stickers",
                    checked = state.autoDownloadStickers,
                    iconColor = pinkColor,
                    position = ItemPosition.MIDDLE,
                    onCheckedChange = component::onAutoDownloadStickersChanged
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.VideoFile,
                    title = "Auto Download video notes",
                    subtitle = "Automatically download video notes",
                    checked = state.autoDownloadVideoNotes,
                    iconColor = blueColor,
                    position = ItemPosition.BOTTOM,
                    onCheckedChange = component::onAutoDownloadVideoNotesChanged
                )
            }

            item {
                SectionHeader("Autoplay media")
                SettingsSwitchTile(
                    icon = Icons.Rounded.Gif,
                    title = "GIFs",
                    subtitle = "Autoplay GIFs in chat list and chats",
                    checked = state.autoplayGifs,
                    iconColor = pinkColor,
                    position = ItemPosition.TOP,
                    onCheckedChange = component::onAutoplayGifsChanged
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.PlayCircle,
                    title = "Videos",
                    subtitle = "Autoplay videos in chats",
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
