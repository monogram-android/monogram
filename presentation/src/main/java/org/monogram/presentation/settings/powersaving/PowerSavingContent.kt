package org.monogram.presentation.settings.powersaving

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerSavingContent(component: PowerSavingComponent) {
    val state by component.state.subscribeAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.power_saving_header),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = component::onBackClicked) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
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
                SectionHeader(stringResource(R.string.battery_header))
                SettingsSwitchTile(
                    icon = Icons.Rounded.BatteryAlert,
                    title = stringResource(R.string.power_saving_mode_title),
                    subtitle = stringResource(R.string.power_saving_mode_subtitle),
                    checked = state.isPowerSavingModeEnabled,
                    iconColor = Color(0xFFF44336),
                    position = ItemPosition.TOP,
                    onCheckedChange = component::onPowerSavingModeToggled
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.BatterySaver,
                    title = stringResource(R.string.optimize_battery_usage_title),
                    subtitle = stringResource(R.string.optimize_battery_usage_subtitle),
                    checked = state.batteryOptimizationEnabled,
                    iconColor = Color(0xFF4CAF50),
                    position = ItemPosition.MIDDLE,
                    onCheckedChange = component::onBatteryOptimizationToggled
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.Power,
                    title = stringResource(R.string.wake_lock_title),
                    subtitle = stringResource(R.string.wake_lock_subtitle),
                    checked = if (state.isPowerSavingModeEnabled || state.batteryOptimizationEnabled) false else state.isWakeLockEnabled,
                    enabled = !state.isPowerSavingModeEnabled && !state.batteryOptimizationEnabled,
                    iconColor = Color(0xFFFF9800),
                    position = ItemPosition.BOTTOM,
                    onCheckedChange = component::onWakeLockToggled
                )
            }

            item {
                SectionHeader(stringResource(R.string.animations_header))
                SettingsSwitchTile(
                    icon = Icons.Rounded.Animation,
                    title = stringResource(R.string.chat_animations_title),
                    subtitle = stringResource(R.string.chat_animations_subtitle),
                    checked = if (state.isPowerSavingModeEnabled) false else state.isChatAnimationsEnabled,
                    enabled = !state.isPowerSavingModeEnabled,
                    iconColor = Color(0xFF4285F4),
                    position = ItemPosition.STANDALONE,
                    onCheckedChange = component::onChatAnimationsToggled
                )
            }

            item {
                SectionHeader(stringResource(R.string.background_header))
                SettingsSwitchTile(
                    icon = Icons.Rounded.Sync,
                    title = stringResource(R.string.keep_alive_service_title),
                    subtitle = stringResource(R.string.keep_alive_power_saving_subtitle),
                    checked = if (state.isPowerSavingModeEnabled) false else state.backgroundServiceEnabled,
                    enabled = !state.isPowerSavingModeEnabled,
                    iconColor = Color(0xFF607D8B),
                    position = ItemPosition.STANDALONE,
                    onCheckedChange = component::onBackgroundServiceToggled
                )
            }

            item { Spacer(modifier = Modifier.height(padding.calculateBottomPadding())) }
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