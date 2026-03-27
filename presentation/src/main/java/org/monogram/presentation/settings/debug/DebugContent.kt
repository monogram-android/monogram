package org.monogram.presentation.settings.debug

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugContent(component: DebugComponent) {
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
                    icon = Icons.Rounded.Favorite,
                    title = stringResource(R.string.debug_sponsor_sheet_title),
                    subtitle = stringResource(R.string.debug_sponsor_sheet_subtitle),
                    iconBackgroundColor = Color(0xFFFF6D66),
                    position = ItemPosition.TOP,
                    onClick = { isSponsorSheetVisible = true }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.Sync,
                    title = stringResource(R.string.debug_force_sponsor_sync_title),
                    subtitle = stringResource(R.string.debug_force_sponsor_sync_subtitle),
                    iconBackgroundColor = Color(0xFF00ACC1),
                    position = ItemPosition.MIDDLE,
                    onClick = component::onForceSponsorSyncClicked
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.BugReport,
                    title = "Crash App",
                    subtitle = "Trigger a manual RuntimeException",
                    iconBackgroundColor = Color.Red,
                    position = ItemPosition.MIDDLE,
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
