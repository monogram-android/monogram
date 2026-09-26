package org.monogram.feature.settings.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.common.DebugStatAverage
import org.monogram.core.common.DebugStatKind
import org.monogram.core.common.DebugStatRecord
import org.monogram.core.common.DebugStats
import org.monogram.core.ui.components.ItemPosition
import org.monogram.core.ui.components.SectionHeader
import org.monogram.core.ui.components.SettingsTile
import org.monogram.feature.settings.R

private const val PREVIEW_COUNT = 10
private const val FULL_COUNT = 50

internal fun LazyListScope.debugStatsItems(
    exportMessage: String?,
    expanded: Set<String>,
    onToggleSection: (String) -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    val snap = DebugStats.snapshot()
    item { Spacer(Modifier.height(8.dp)) }
    item {
        SettingsTile(
            icon = Icons.Outlined.Share,
            title = stringResource(R.string.settings_debug_stats_export),
            subtitle = when (exportMessage) {
                "ok" -> stringResource(R.string.settings_debug_stats_exported)
                "err" -> stringResource(R.string.settings_debug_stats_export_failed)
                else -> null
            },
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.TOP,
            onClick = onExport,
        )
    }
    item {
        SettingsTile(
            icon = Icons.Outlined.DeleteSweep,
            title = stringResource(R.string.settings_debug_stats_clear),
            iconColor = MaterialTheme.colorScheme.error,
            position = ItemPosition.BOTTOM,
            onClick = onClear,
        )
    }
    statSection("rpc", R.string.settings_debug_stats_slow_rpc, snap.slowest(DebugStatKind.RPC, FULL_COUNT), expanded, onToggleSection)
    statSection("media", R.string.settings_debug_stats_slow_media, snap.slowest(DebugStatKind.MEDIA, FULL_COUNT), expanded, onToggleSection, media = true)
    statSection("connection", R.string.settings_debug_stats_connection, snap.slowest(DebugStatKind.CONNECTION, FULL_COUNT), expanded, onToggleSection)
    averageSection(snap.averages(), expanded, onToggleSection)
    statSection("errors", R.string.settings_debug_stats_errors, snap.recentErrors(FULL_COUNT), expanded, onToggleSection)
}

private fun LazyListScope.statSection(
    key: String,
    title: Int,
    rows: List<DebugStatRecord>,
    expanded: Set<String>,
    onToggleSection: (String) -> Unit,
    media: Boolean = false,
) {
    val open = key in expanded
    val visible = if (open) rows.take(FULL_COUNT) else rows.take(PREVIEW_COUNT)
    item { Spacer(Modifier.height(8.dp)) }
    item { SectionHeader(stringResource(title)) }
    if (rows.isEmpty()) {
        emptyRow()
        return
    }
    visible.forEachIndexed { index, row ->
        item {
            val speed = row.bytesPerSec?.let { " · ${DebugStats.formatSpeed(it)}" }.orEmpty()
            val size = row.bytes?.let { " · ${DebugStats.formatBytes(it)}" }.orEmpty()
            val last = index == visible.lastIndex && rows.size <= visible.size
            SettingsTile(
                icon = Icons.Outlined.BugReport,
                title = stringResource(R.string.settings_debug_stats_row, row.durationMs.toInt(), row.op),
                subtitle = buildString {
                    append(row.result)
                    if (!row.errorKind.isNullOrBlank()) append(" · ").append(row.errorKind)
                    if (media) append(size).append(speed)
                }.ifBlank { null },
                iconColor = MaterialTheme.colorScheme.tertiary,
                position = if (last && rows.size <= PREVIEW_COUNT) positionOf(index, visible.size) else {
                    if (index == 0) ItemPosition.TOP else ItemPosition.MIDDLE
                },
                onClick = {},
            )
        }
    }
    if (rows.size > PREVIEW_COUNT) {
        item {
            SettingsTile(
                icon = if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                title = if (open) {
                    stringResource(R.string.settings_debug_stats_show_less)
                } else {
                    stringResource(R.string.settings_debug_stats_show_more, rows.size.coerceAtMost(FULL_COUNT))
                },
                iconColor = MaterialTheme.colorScheme.primary,
                position = ItemPosition.BOTTOM,
                onClick = { onToggleSection(key) },
            )
        }
    }
}

private fun LazyListScope.averageSection(
    averages: List<DebugStatAverage>,
    expanded: Set<String>,
    onToggleSection: (String) -> Unit,
) {
    val open = "averages" in expanded
    val visible = if (open) averages.take(FULL_COUNT) else averages.take(PREVIEW_COUNT)
    item { Spacer(Modifier.height(8.dp)) }
    item { SectionHeader(stringResource(R.string.settings_debug_stats_averages)) }
    if (averages.isEmpty()) {
        emptyRow()
        return
    }
    visible.forEachIndexed { index, avg ->
        item {
            SettingsTile(
                icon = Icons.Outlined.BugReport,
                title = avg.op,
                subtitle = "n=${avg.count} avg=${avg.avgMs}ms max=${avg.maxMs}ms",
                iconColor = MaterialTheme.colorScheme.secondary,
                position = if (index == 0) ItemPosition.TOP else ItemPosition.MIDDLE,
                onClick = {},
            )
        }
    }
    if (averages.size > PREVIEW_COUNT) {
        item {
            SettingsTile(
                icon = if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                title = if (open) {
                    stringResource(R.string.settings_debug_stats_show_less)
                } else {
                    stringResource(R.string.settings_debug_stats_show_more, averages.size.coerceAtMost(FULL_COUNT))
                },
                iconColor = MaterialTheme.colorScheme.primary,
                position = ItemPosition.BOTTOM,
                onClick = { onToggleSection("averages") },
            )
        }
    }
}

private fun LazyListScope.emptyRow() {
    item {
        Text(
            text = stringResource(R.string.settings_debug_stats_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(40.dp),
        )
    }
}

private fun positionOf(index: Int, size: Int) = when {
    size == 1 -> ItemPosition.STANDALONE
    index == 0 -> ItemPosition.TOP
    index == size - 1 -> ItemPosition.BOTTOM
    else -> ItemPosition.MIDDLE
}
