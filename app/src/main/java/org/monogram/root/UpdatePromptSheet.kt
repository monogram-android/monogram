package org.monogram.root

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.R
import org.monogram.core.models.AppUpdate
import org.monogram.core.models.AppUpdateInfo
import org.monogram.core.models.AppUpdateState
import org.monogram.core.ui.AppUpdateSettings
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.feature.settings.AppUpdateController

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdatePromptSheet(
    controller: AppUpdateController?,
    enabled: Boolean,
) {
    if (controller == null || !enabled) return
    val state by controller.state.collectAsState()
    val skipped by AppUpdateSettings.skippedUpdateKey.collectAsState()
    var hidden by remember { mutableStateOf(false) }
    val info = updateInfo(state)
    val key = info?.let(AppUpdate::promptKey).orEmpty()
    val skippedThis = key.isNotEmpty() && key == skipped
    val keepOpen = state is AppUpdateState.Downloading ||
        state is AppUpdateState.ReadyToInstall ||
        (state is AppUpdateState.Error && info != null)
    if (info == null || (skippedThis && !keepOpen) || (hidden && !keepOpen)) return
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val dismiss = {
        if (state is AppUpdateState.Available || state is AppUpdateState.Error) hidden = true
    }
    if (wide) {
        AlertDialog(
            onDismissRequest = dismiss,
            modifier = Modifier,
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = null,
            text = {
                UpdatePromptBody(
                    state = state,
                    info = info,
                    onSkip = {
                        AppUpdateSettings.skipUpdate(AppUpdate.promptKey(info))
                        hidden = true
                    },
                    onUpdate = controller::downloadUpdate,
                    onHide = { hidden = true },
                    onInstall = controller::installUpdate,
                    onRetry = controller::downloadUpdate,
                    dialog = true,
                )
            },
            confirmButton = {},
        )
    } else {
        AppModalSheet(
            onDismissRequest = dismiss,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
            enterSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        ) {
            UpdatePromptBody(
                state = state,
                info = info,
                onSkip = {
                    AppUpdateSettings.skipUpdate(AppUpdate.promptKey(info))
                    hidden = true
                },
                onUpdate = controller::downloadUpdate,
                onHide = { hidden = true },
                onInstall = controller::installUpdate,
                onRetry = controller::downloadUpdate,
                dialog = false,
            )
        }
    }
}

@Composable
private fun UpdatePromptBody(
    state: AppUpdateState,
    info: AppUpdateInfo,
    onSkip: () -> Unit,
    onUpdate: () -> Unit,
    onHide: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    dialog: Boolean,
) {
    val downloading = state as? AppUpdateState.Downloading
    val ready = state is AppUpdateState.ReadyToInstall
    val error = state as? AppUpdateState.Error
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(if (dialog) 0.dp else 0.dp))
        HeroIcon(ready = ready)
        Spacer(Modifier.height(16.dp))
        Text(
            text = when {
                ready -> stringResource(R.string.update_prompt_ready)
                else -> stringResource(R.string.update_prompt_title, info.version)
            },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                downloading != null -> stringResource(
                    R.string.update_prompt_progress,
                    formatUpdateBytes(downloading.bytes),
                    formatUpdateBytes(info.fileSize),
                )
                ready -> stringResource(R.string.update_prompt_body, formatUpdateBytes(info.fileSize))
                else -> stringResource(R.string.update_prompt_body, formatUpdateBytes(info.fileSize))
            },
            style = if (downloading != null) {
                MaterialTheme.typography.bodySmall
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetaChip("${info.version} (${info.versionCode})")
            if (info.abi.isNotBlank()) MetaChip(info.abi)
            formatUpdateBytes(info.fileSize).takeIf { it.isNotBlank() }?.let { MetaChip(it) }
            info.commit?.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
        }
        val notes = info.changelog.filterNot { line ->
            val commit = info.commit
            commit != null && line.equals(commit, ignoreCase = true)
        }
        if (notes.isNotEmpty() && downloading == null && error == null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = notes.take(4).joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            )
        }
        if (downloading != null) {
            Spacer(Modifier.height(16.dp))
            val total = info.fileSize.takeIf { it > 0L } ?: 1L
            val fraction = (downloading.bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.update_prompt_error),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        when {
            downloading != null -> {
                TextButton(
                    onClick = onHide,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.update_prompt_hide), style = MaterialTheme.typography.labelLarge)
                }
            }
            ready -> {
                Button(
                    onClick = onInstall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text(stringResource(R.string.update_prompt_install), style = MaterialTheme.typography.labelLarge)
                }
            }
            error != null -> {
                ActionColumn(
                    onSkip = onSkip,
                    onPrimary = onRetry,
                    primaryLabel = stringResource(R.string.update_prompt_retry),
                )
            }
            else -> {
                ActionColumn(
                    onSkip = onSkip,
                    onPrimary = onUpdate,
                    primaryLabel = stringResource(R.string.update_prompt_update),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ActionColumn(
    onSkip: () -> Unit,
    onPrimary: () -> Unit,
    primaryLabel: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Text(primaryLabel, style = MaterialTheme.typography.labelLarge)
        }
        TextButton(
            onClick = onSkip,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.update_prompt_skip), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun HeroIcon(ready: Boolean) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (ready) Icons.Outlined.Check else Icons.Outlined.SystemUpdate,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun MetaChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.extraSmall,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            maxLines = 1,
        )
    }
}

private fun updateInfo(state: AppUpdateState): AppUpdateInfo? = when (state) {
    is AppUpdateState.Available -> state.info
    is AppUpdateState.Downloading -> state.info
    is AppUpdateState.ReadyToInstall -> state.info
    is AppUpdateState.Error -> state.info
    else -> null
}

private fun formatUpdateBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) "${((mb * 10).toInt() / 10.0)} MB" else "${bytes / 1024} KB"
}
