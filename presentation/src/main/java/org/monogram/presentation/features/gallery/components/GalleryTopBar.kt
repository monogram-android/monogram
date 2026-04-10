package org.monogram.presentation.features.gallery.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import org.monogram.presentation.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryTopBar(
    onDismiss: () -> Unit,
    onPickFromOtherSources: () -> Unit,
    onCameraClick: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.gallery_title_attachments),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        navigationIcon = {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
            }
        },
        actions = {
            IconButton(onClick = onPickFromOtherSources) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = stringResource(R.string.gallery_action_other_sources))
            }
            IconButton(onClick = onCameraClick) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = stringResource(R.string.permission_camera_title))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background
        )
    )
}
