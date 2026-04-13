package org.monogram.presentation.settings.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.NotificationAdd
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.domain.repository.PushProvider
import org.monogram.domain.repository.UnifiedPushDebugStatus
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SectionHeader
import org.monogram.presentation.core.ui.SettingsItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugContent(component: DebugComponent) {
    val state by component.state.subscribeAsState()
    var isSponsorSheetVisible by remember { mutableStateOf(false) }

    if (isSponsorSheetVisible) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { isSponsorSheetVisible = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 48.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = Color(0xFFFF6D66)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.support_monogram_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.sponsor_sheet_description),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = {
                        component.onShowSponsorSheetClicked()
                        isSponsorSheetVisible = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.action_support_boosty))
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = { isSponsorSheetVisible = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_maybe_later))
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.debug_title),
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
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SectionHeader(stringResource(R.string.debug_section_push_diagnostics))
                SettingsItem(
                    icon = Icons.Rounded.Notifications,
                    title = "Push provider",
                    subtitle = when (state.pushProvider) {
                        PushProvider.FCM -> "FCM"
                        PushProvider.UNIFIED_PUSH -> "UnifiedPush"
                        PushProvider.GMS_LESS -> "GMS-less"
                    },
                    iconBackgroundColor = Color(0xFF4CAF50),
                    position = ItemPosition.TOP,
                    onClick = { }
                )
                SettingsItem(
                    icon = Icons.Rounded.Sync,
                    title = "Push service running",
                    subtitle = state.isTdNotificationServiceRunning.toUiToggle(),
                    iconBackgroundColor = Color(0xFF00ACC1),
                    position = ItemPosition.MIDDLE,
                    onClick = { }
                )
                SettingsItem(
                    icon = Icons.Rounded.NotificationAdd,
                    title = "Test Push",
                    subtitle = "Show local notification and trigger sync",
                    iconBackgroundColor = Color(0xFF009688),
                    position = ItemPosition.BOTTOM,
                    onClick = component::onTestPushClicked
                )
            }

            item {
                SectionHeader(stringResource(R.string.debug_section_runtime_flags))
                SettingsItem(
                    icon = Icons.Rounded.Settings,
                    title = "Keep-alive enabled",
                    subtitle = state.backgroundServiceEnabled.toUiToggle(),
                    iconBackgroundColor = Color(0xFF607D8B),
                    position = ItemPosition.TOP,
                    onClick = { }
                )
                SettingsItem(
                    icon = Icons.Rounded.BatteryAlert,
                    title = "Power saving mode",
                    subtitle = state.isPowerSavingMode.toUiToggle(),
                    iconBackgroundColor = Color(0xFFFF9800),
                    position = ItemPosition.MIDDLE,
                    onClick = { }
                )
                SettingsItem(
                    icon = Icons.Rounded.Power,
                    title = "Wake lock",
                    subtitle = state.isWakeLockEnabled.toUiToggle(),
                    iconBackgroundColor = Color(0xFF3F51B5),
                    position = ItemPosition.MIDDLE,
                    onClick = { }
                )
                SettingsItem(
                    icon = Icons.Rounded.BatterySaver,
                    title = "Battery optimization",
                    subtitle = state.batteryOptimizationEnabled.toUiToggle(),
                    iconBackgroundColor = Color(0xFF8BC34A),
                    position = ItemPosition.MIDDLE,
                    onClick = { }
                )
                SettingsItem(
                    icon = Icons.Rounded.VisibilityOff,
                    title = "Hide foreground notification",
                    subtitle = state.hideForegroundNotification.toUiToggle(),
                    iconBackgroundColor = Color(0xFF9E9E9E),
                    position = ItemPosition.BOTTOM,
                    onClick = { }
                )
            }

            item {
                SectionHeader(stringResource(R.string.debug_section_push_environment))
                SettingsItem(
                    icon = Icons.Rounded.CloudDone,
                    title = "GMS available",
                    subtitle = state.isGmsAvailable.toUiToggle(),
                    iconBackgroundColor = Color(0xFF4285F4),
                    position = ItemPosition.TOP,
                    onClick = { }
                )
                SettingsItem(
                    icon = Icons.Rounded.Cloud,
                    title = "FCM available",
                    subtitle = state.isFcmAvailable.toUiToggle(),
                    iconBackgroundColor = Color(0xFF1E88E5),
                    position = ItemPosition.MIDDLE,
                    onClick = { }
                )
                SettingsItem(
                    icon = Icons.Rounded.Hub,
                    title = "UnifiedPush distributor",
                    subtitle = state.isUnifiedPushDistributorAvailable.toUiToggle(),
                    iconBackgroundColor = Color(0xFF7E57C2),
                    position = ItemPosition.BOTTOM,
                    onClick = { }
                )
            }

            item {
                SectionHeader(stringResource(R.string.debug_section_unifiedpush_details))
                SettingsItem(
                    icon = Icons.Rounded.Info,
                    title = "UnifiedPush status",
                    subtitle = state.unifiedPushStatus.toUiText(),
                    iconBackgroundColor = Color(0xFF3949AB),
                    position = ItemPosition.TOP,
                    onClick = { }
                )
                SettingsItem(
                    icon = Icons.Rounded.Link,
                    title = "UnifiedPush endpoint",
                    subtitle = state.unifiedPushEndpoint?.takeIf { it.isNotBlank() }
                        ?: "Not registered",
                    iconBackgroundColor = Color(0xFF5C6BC0),
                    position = ItemPosition.MIDDLE,
                    onClick = { }
                )
                SettingsItem(
                    icon = Icons.Rounded.Bookmark,
                    title = "Saved distributor",
                    subtitle = state.unifiedPushSavedDistributor?.takeIf { it.isNotBlank() }
                        ?: "None",
                    iconBackgroundColor = Color(0xFF6A1B9A),
                    position = ItemPosition.MIDDLE,
                    onClick = { }
                )
                SettingsItem(
                    icon = Icons.Rounded.DoneAll,
                    title = "Ack distributor",
                    subtitle = state.unifiedPushAckDistributor?.takeIf { it.isNotBlank() }
                        ?: "None",
                    iconBackgroundColor = Color(0xFF512DA8),
                    position = ItemPosition.MIDDLE,
                    onClick = { }
                )
                SettingsItem(
                    icon = Icons.Rounded.FilterList,
                    title = "Distributors count",
                    subtitle = state.unifiedPushDistributorsCount.toString(),
                    iconBackgroundColor = Color(0xFF9575CD),
                    position = ItemPosition.BOTTOM,
                    onClick = { }
                )
            }

            item {
                SectionHeader(stringResource(R.string.debug_section_sponsor))
                SettingsItem(
                    icon = Icons.Rounded.Favorite,
                    title = stringResource(R.string.debug_sponsor_sheet_title),
                    subtitle = stringResource(R.string.debug_sponsor_sheet_subtitle),
                    iconBackgroundColor = Color(0xFFFF6D66),
                    position = ItemPosition.TOP,
                    onClick = { isSponsorSheetVisible = true }
                )
                SettingsItem(
                    icon = Icons.Rounded.Sync,
                    title = stringResource(R.string.debug_force_sponsor_sync_title),
                    subtitle = stringResource(R.string.debug_force_sponsor_sync_subtitle),
                    iconBackgroundColor = Color(0xFF00ACC1),
                    position = ItemPosition.BOTTOM,
                    onClick = component::onForceSponsorSyncClicked
                )
            }

            item {
                SectionHeader(stringResource(R.string.debug_section_danger_zone))
                SettingsItem(
                    icon = Icons.Rounded.BugReport,
                    title = "Crash App",
                    subtitle = "Trigger a manual RuntimeException",
                    iconBackgroundColor = Color.Red,
                    position = ItemPosition.TOP,
                    onClick = component::onCrashClicked
                )

                SettingsItem(
                    icon = Icons.Rounded.Storage,
                    title = "Drop Databases",
                    subtitle = "Delete all app databases and tdlib",
                    iconBackgroundColor = Color.Red,
                    position = ItemPosition.MIDDLE,
                    onClick = component::onDropDatabasesClicked
                )

                SettingsItem(
                    icon = Icons.Rounded.Storage,
                    title = "Drop Cache Database",
                    subtitle = "Delete cache database",
                    iconBackgroundColor = Color.Red,
                    position = ItemPosition.MIDDLE,
                    onClick = component::onDropDatabaseCacheClicked
                )

                SettingsItem(
                    icon = Icons.Rounded.DeleteSweep,
                    title = "Drop Cache",
                    subtitle = "Delete all cache",
                    iconBackgroundColor = Color.Red,
                    position = ItemPosition.MIDDLE,
                    onClick = component::onDropCachePrefsClicked
                )

                SettingsItem(
                    icon = Icons.Rounded.Delete,
                    title = "Drop Prefs",
                    subtitle = "Delete all preferences",
                    iconBackgroundColor = Color.Red,
                    position = ItemPosition.BOTTOM,
                    onClick = component::onDropPrefsClicked
                )

                Spacer(modifier = Modifier.height(padding.calculateBottomPadding()))
            }
        }
    }
}

@Composable
private fun Boolean.toUiToggle(): String = if (this) {
    stringResource(R.string.on_label)
} else {
    stringResource(R.string.off_label)
}

private fun UnifiedPushDebugStatus.toUiText(): String = when (this) {
    UnifiedPushDebugStatus.IDLE -> "Idle"
    UnifiedPushDebugStatus.REGISTERING -> "Registering"
    UnifiedPushDebugStatus.REGISTERED -> "Registered"
    UnifiedPushDebugStatus.FAILED -> "Failed"
    UnifiedPushDebugStatus.UNREGISTERED -> "Unregistered"
}
