package org.monogram.feature.dialog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.core.ui.loading.MonogramLinearProgress
import org.monogram.feature.dialog.R
import org.monogram.network.http.UserDownload

@Composable
internal fun DialogDownloadProgressBar(
    downloads: Map<String, UserDownload>,
    progress: Map<String, Long>,
    modifier: Modifier = Modifier,
) {
    if (downloads.isEmpty()) return
    val received = downloads.keys.sumOf { progress[it] ?: 0L }
    val total = downloads.values.mapNotNull { it.totalBytes }.sum().takeIf { it > 0L }
    val fraction = downloadProgressFraction(received, total)
    MonogramLinearProgress(
        visible = true,
        modifier = modifier.fillMaxWidth(),
        progress = fraction?.let { value -> { value } },
        height = 3.dp,
        status = stringResource(R.string.dialog_downloads),
    )
}

@Composable
internal fun DialogDownloadsSheet(
    downloads: Map<String, UserDownload>,
    progress: Map<String, Long>,
    onCancel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AppModalSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.dialog_downloads),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (downloads.isEmpty()) {
            Text(
                text = stringResource(R.string.dialog_downloads_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        } else {
            Text(
                text = pluralStringResource(
                    R.plurals.dialog_downloads_count,
                    downloads.size,
                    downloads.size,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            downloads.values.forEach { item ->
                val bytes = progress[item.key] ?: 0L
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val total = item.totalBytes
                        Text(
                            text = if (total != null && total > 0L) {
                                stringResource(
                                    R.string.dialog_media_download_progress,
                                    formatFileSize(bytes),
                                    formatFileSize(total),
                                )
                            } else {
                                stringResource(R.string.dialog_media_loading)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val fraction = downloadProgressFraction(bytes, total)
                        MonogramLinearProgress(
                            visible = true,
                            progress = fraction?.let { value -> { value } },
                            height = 3.dp,
                            status = item.name,
                        )
                    }
                    IconButton(onClick = { onCancel(item.key) }) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.dialog_media_cancel),
                        )
                    }
                }
            }
        }
    }
}