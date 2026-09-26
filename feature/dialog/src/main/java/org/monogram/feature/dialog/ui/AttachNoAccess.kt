package org.monogram.feature.dialog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.feature.dialog.R

private val FallbackCellHeight = 80.dp

@Composable
internal fun NoAccessBody(
    denied: Boolean,
    canSendPhotos: Boolean,
    canSendFiles: Boolean,
    onRequestAccess: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickVideo: () -> Unit,
    onPickFile: () -> Unit,
    onSendLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DockInset, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(
                if (denied) {
                    R.string.dialog_attach_choose_device
                } else {
                    R.string.dialog_attach_allow_hint
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!denied) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onRequestAccess,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.dialog_attach_allow))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (canSendPhotos) {
                FallbackCell(
                    icon = Icons.Outlined.Image,
                    label = stringResource(R.string.dialog_attach_photo),
                    onClick = onPickPhoto,
                    modifier = Modifier.weight(1f),
                )
                FallbackCell(
                    icon = Icons.Outlined.Videocam,
                    label = stringResource(R.string.dialog_attach_video),
                    onClick = onPickVideo,
                    modifier = Modifier.weight(1f),
                )
            }
            if (canSendFiles) {
                FallbackCell(
                    icon = Icons.AutoMirrored.Outlined.InsertDriveFile,
                    label = stringResource(R.string.dialog_attach_file),
                    onClick = onPickFile,
                    modifier = Modifier.weight(1f),
                )
            }
            FallbackCell(
                icon = Icons.Outlined.LocationOn,
                label = stringResource(R.string.dialog_location_action),
                onClick = onSendLocation,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FallbackCell(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(FallbackCellHeight),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
