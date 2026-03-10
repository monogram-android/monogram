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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsSwitchTile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerSavingContent(component: PowerSavingComponent) {
    val state by component.state.subscribeAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Power Saving",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = component::onBackClicked) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
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
                SectionHeader("Battery")
                SettingsSwitchTile(
                    icon = Icons.Rounded.BatteryAlert,
                    title = "Power Saving Mode",
                    subtitle = "Reduces background activity and animations to save battery",
                    checked = state.isPowerSavingModeEnabled,
                    iconColor = Color(0xFFF44336),
                    position = ItemPosition.TOP,
                    onCheckedChange = component::onPowerSavingModeToggled
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.BatterySaver,
                    title = "Optimize Battery Usage",
                    subtitle = "Aggressively limit background work and release wake locks",
                    checked = state.batteryOptimizationEnabled,
                    iconColor = Color(0xFF4CAF50),
                    position = ItemPosition.MIDDLE,
                    onCheckedChange = component::onBatteryOptimizationToggled
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.Power,
                    title = "Wake Lock",
                    subtitle = "Keep CPU awake for background tasks. Disable to save battery",
                    checked = if (state.isPowerSavingModeEnabled || state.batteryOptimizationEnabled) false else state.isWakeLockEnabled,
                    enabled = !state.isPowerSavingModeEnabled && !state.batteryOptimizationEnabled,
                    iconColor = Color(0xFFFF9800),
                    position = ItemPosition.BOTTOM,
                    onCheckedChange = component::onWakeLockToggled
                )
            }

            item {
                SectionHeader("Animations")
                SettingsSwitchTile(
                    icon = Icons.Rounded.Animation,
                    title = "Chat Animations",
                    subtitle = "Disable animations in chat to save battery",
                    checked = if (state.isPowerSavingModeEnabled) false else state.isChatAnimationsEnabled,
                    enabled = !state.isPowerSavingModeEnabled,
                    iconColor = Color(0xFF4285F4),
                    position = ItemPosition.STANDALONE,
                    onCheckedChange = component::onChatAnimationsToggled
                )
            }

            item {
                SectionHeader("Background")
                SettingsSwitchTile(
                    icon = Icons.Rounded.Sync,
                    title = "Keep-Alive Service",
                    subtitle = "Disabling this will reduce power usage but may delay background notifications",
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