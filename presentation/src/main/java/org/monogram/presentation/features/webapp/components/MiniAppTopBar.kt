package org.monogram.presentation.features.webapp.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniAppTopBar(
    headerText: String,
    isBackButtonVisible: Boolean,
    isSettingsButtonVisible: Boolean,
    isInitializing: Boolean,
    topBarColor: Color?,
    topBarTextColor: Color?,
    accentColor: Color?,
    onBackClick: () -> Unit,
    onCloseClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = headerText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = topBarTextColor ?: MaterialTheme.colorScheme.onSurface
            )
        },
        navigationIcon = {
            if (isBackButtonVisible) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = accentColor ?: MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                IconButton(onClick = onCloseClick) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = accentColor ?: MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        actions = {
            if (isSettingsButtonVisible) {
                IconButton(
                    onClick = onSettingsClick,
                    enabled = !isInitializing
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "Settings",
                        tint = accentColor ?: MaterialTheme.colorScheme.primary
                    )
                }
            }

            IconButton(onClick = onMenuClick, enabled = !isInitializing) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "More",
                    tint = accentColor ?: MaterialTheme.colorScheme.primary
                )
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = topBarColor ?: MaterialTheme.colorScheme.surface
        )
    )
}
