package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.monogram.feature.dialog.R

internal val DockInset = 16.dp
internal val DockTopPadding = 8.dp
private val DockGap = 8.dp
private val DockCornerRadius = 28.dp

/** Height of the attach dock: the Material 3 Expressive medium button container. */
internal val AttachDockHeight = 56.dp

@Composable
internal fun navBarInset(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * Attach dock: the three bodies are a connected single-select toggle group (M3 Expressive), sized to
 * a 56dp medium button. The checked segment morphs to the "full" shape and takes the primary fill,
 * so the selected body reads from shape as well as color. "File" is an action, not a body, so it is a
 * separate tonal icon button instead of a fourth segment.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AttachDock(
    current: AttachBody,
    canSendPhotos: Boolean,
    canSendFiles: Boolean,
    onSelectGallery: () -> Unit,
    onPickFile: () -> Unit,
    onSendLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bodies = buildList {
        if (canSendPhotos) add(AttachBody.Gallery)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = DockInset,
                end = DockInset,
                top = DockTopPadding,
                bottom = navBarInset(),
            )
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
                shape = RoundedCornerShape(DockCornerRadius),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DockGap),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            bodies.forEachIndexed { index, entry ->
                val selected = current == entry
                ToggleButton(
                    checked = selected,
                    onCheckedChange = { if (!selected) entry.select(onSelectGallery) },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        bodies.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(AttachDockHeight)
                        .semantics { role = Role.RadioButton },
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = entry.icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(entry.label),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        FilledTonalIconButton(
            onClick = onSendLocation,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(AttachDockHeight),
        ) {
            Icon(
                imageVector = Icons.Outlined.LocationOn,
                contentDescription = stringResource(R.string.dialog_location_action),
                modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
            )
        }
        if (canSendFiles) {
            FilledTonalIconButton(
                onClick = onPickFile,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(AttachDockHeight),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                    contentDescription = stringResource(R.string.dialog_attach_file),
                    modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
                )
            }
        }
    }
}

private val AttachBody.icon: ImageVector
    get() = when (this) {
        AttachBody.Gallery -> Icons.Outlined.PhotoLibrary
    }

private val AttachBody.label: Int
    get() = when (this) {
        AttachBody.Gallery -> R.string.dialog_attach_gallery
    }

private fun AttachBody.select(onSelectGallery: () -> Unit) = when (this) {
    AttachBody.Gallery -> onSelectGallery()
}
