package org.monogram.feature.dialog.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.monogram.feature.dialog.R

internal val DockInset = 16.dp
internal val DockTopPadding = 8.dp

@Composable
internal fun navBarInset(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

private data class AttachAction(
    val icon: ImageVector,
    val label: Int,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val selected: Boolean = false,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AttachDock(
    current: AttachBody,
    canSendPhotos: Boolean,
    canSendFiles: Boolean,
    onSelectGallery: () -> Unit,
    onPickFile: () -> Unit,
    onSendLocation: () -> Unit,
    canSendLocation: Boolean = true,
    onCreatePoll: (() -> Unit)? = null,
    onCreateChecklist: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val actions = buildList {
        if (canSendPhotos) add(
            AttachAction(
                Icons.Outlined.PhotoLibrary, R.string.dialog_attach_gallery,
                onSelectGallery, selected = current == AttachBody.Gallery
            )
        )
        if (canSendFiles) add(
            AttachAction(
                Icons.AutoMirrored.Outlined.InsertDriveFile,
                R.string.dialog_attach_file, onPickFile
            )
        )
        add(
            AttachAction(
                Icons.Outlined.LocationOn, R.string.dialog_location_action, onSendLocation,
                enabled = canSendLocation
            )
        )
        onCreatePoll?.let {
            add(
                AttachAction(
                    Icons.Outlined.Poll,
                    R.string.dialog_poll_create,
                    it
                )
            )
        }
        onCreateChecklist?.let {
            add(
                AttachAction(
                    Icons.Outlined.Checklist,
                    R.string.dialog_checklist_create,
                    it
                )
            )
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                start = DockInset, end = DockInset, top = DockTopPadding,
                bottom = navBarInset() + 10.dp
            )
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
                RoundedCornerShape(28.dp)
            )
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            FilledTonalIconButton(
                onClick = action.onClick,
                enabled = action.enabled,
                colors = if (action.selected) IconButtonDefaults.filledIconButtonColors()
                else IconButtonDefaults.filledTonalIconButtonColors(),
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier
                    .size(48.dp)
                    .semantics { selected = action.selected },
            ) {
                Icon(
                    action.icon, contentDescription = stringResource(action.label),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
