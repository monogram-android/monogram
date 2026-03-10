package org.monogram.presentation.settings.debug

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugContent(component: DebugComponent) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = component::onBackClicked) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                SettingsItem(
                    icon = Icons.Rounded.BugReport,
                    title = "Crash App",
                    subtitle = "Trigger a manual RuntimeException",
                    iconBackgroundColor = Color.Red,
                    position = ItemPosition.TOP,
                    onClick = component::onCrashClicked
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.Storage,
                    title = "Drop Databases",
                    subtitle = "Delete all app databases and tdlib",
                    iconBackgroundColor = Color.Red,
                    position = ItemPosition.MIDDLE,
                    onClick = component::onDropDatabasesClicked
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.Storage,
                    title = "Drop Cache Database",
                    subtitle = "Delete cache database",
                    iconBackgroundColor = Color.Red,
                    position = ItemPosition.MIDDLE,
                    onClick = component::onDropDatabaseCacheClicked
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.DeleteSweep,
                    title = "Drop Cache",
                    subtitle = "Delete all cache",
                    iconBackgroundColor = Color.Red,
                    position = ItemPosition.MIDDLE,
                    onClick = component::onDropCachePrefsClicked
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.Delete,
                    title = "Drop Prefs",
                    subtitle = "Delete all preferences",
                    iconBackgroundColor = Color.Red,
                    position = ItemPosition.BOTTOM,
                    onClick = component::onDropPrefsClicked
                )
            }
        }
    }
}
