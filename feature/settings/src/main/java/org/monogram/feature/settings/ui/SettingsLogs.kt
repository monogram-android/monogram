package org.monogram.feature.settings.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.monogram.core.common.DebugLog
import org.monogram.core.common.DebugLogKind
import org.monogram.core.ui.ExpressiveDefaults
import org.monogram.core.ui.components.SearchField
import org.monogram.feature.settings.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun shareDebugLog(context: Context, text: String): Boolean = runCatching {
    val dir = File(context.cacheDir, "open").apply { mkdirs() }
    val file = File(dir, "monogram-logs.txt")
    file.writeText(text)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Monogram logs")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, null).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}.isSuccess

@Composable
internal fun LogsTopActions(
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    IconButton(onClick = onShare) {
        Icon(
            imageVector = Icons.Outlined.Share,
            contentDescription = stringResource(R.string.settings_logs_export),
        )
    }
    IconButton(onClick = onClear) {
        Icon(
            imageVector = Icons.Outlined.DeleteSweep,
            contentDescription = stringResource(R.string.settings_logs_clear),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsLogs(
    query: String,
    kind: DebugLogKind?,
    shareFailed: Boolean,
    onQuery: (String) -> Unit,
    onKind: (DebugLogKind?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val revision by DebugLog.changes.collectAsStateWithLifecycle()
    val events = remember(revision, kind, query) { DebugLog.query(kind, query) }
    val bufferEmpty = remember(revision) { DebugLog.query(null, "").isEmpty() }
    val listState = rememberLazyListState()
    var stickToTop by remember { mutableStateOf(true) }
    var anchorId by remember { mutableLongStateOf(-1L) }
    var filterKey by remember { mutableStateOf(kind to query) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    val clock = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> stickToTop = index == 0 && offset == 0 }
    }
    LaunchedEffect(events, kind, query) {
        val head = events.firstOrNull()?.id ?: -1L
        val filterChanged = filterKey != (kind to query)
        if (filterChanged) {
            listState.scrollToItem(0)
        } else if (!stickToTop && anchorId >= 0L && head != anchorId) {
            val shift = events.indexOfFirst { it.id == anchorId }
            if (shift > 0) {
                listState.scrollToItem(
                    listState.firstVisibleItemIndex + shift,
                    listState.firstVisibleItemScrollOffset,
                )
            }
        }
        anchorId = head
        filterKey = kind to query
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            SearchField(
                query = query,
                onQueryChanged = onQuery,
                placeholder = stringResource(R.string.settings_logs_search),
                closeLabel = stringResource(R.string.settings_logs_search_clear),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(logFilters, key = { it.name }) { filter ->
                    FilterChip(
                        selected = kind == filter.kind,
                        onClick = { onKind(filter.kind) },
                        label = { Text(stringResource(filter.label)) },
                        modifier = Modifier.testTag("logs-filter-${filter.name}"),
                        shapes = FilterChipDefaults.shapes(),
                    )
                }
            }
            if (shareFailed) {
                Text(
                    text = stringResource(R.string.settings_logs_export_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (events.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(
                            if (bufferEmpty) {
                                R.string.settings_logs_empty
                            } else {
                                R.string.settings_logs_no_match
                            },
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(events, key = { it.id }) { event ->
                        val expanded = expandedId == event.id
                        val error = event.kind == DebugLogKind.ERROR
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable(
                                    onClickLabel = stringResource(
                                        if (expanded) R.string.settings_logs_collapse else R.string.settings_logs_expand,
                                    ),
                                ) {
                                    expandedId = if (expanded) null else event.id
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = clock.format(Date(event.atEpochMs)),
                                    style = ExpressiveDefaults.tabularLabel(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                if (error) {
                                    Icon(
                                        imageVector = Icons.Outlined.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(start = 8.dp).size(16.dp),
                                    )
                                }
                                Text(
                                    text = stringResource(labelFor(event.kind)),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (error) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.padding(start = 8.dp),
                                    maxLines = 1,
                                )
                            }
                            Text(
                                text = event.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = if (expanded) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            if (expanded && event.detail.isNotBlank() && event.detail != event.summary) {
                                Text(
                                    text = event.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

private data class LogFilter(val name: String, val kind: DebugLogKind?, val label: Int)

private val logFilters = listOf(
    LogFilter("all", null, R.string.settings_logs_filter_all),
    LogFilter("api", DebugLogKind.API, R.string.settings_logs_filter_api),
    LogFilter("recomp", DebugLogKind.RECOMPOSITION, R.string.settings_logs_filter_recomposition),
    LogFilter("media", DebugLogKind.MEDIA, R.string.settings_logs_filter_media),
    LogFilter("connection", DebugLogKind.CONNECTION, R.string.settings_logs_filter_connection),
    LogFilter("error", DebugLogKind.ERROR, R.string.settings_logs_filter_error),
)

internal fun debugLogKind(name: String): DebugLogKind? = when (name) {
    "api" -> DebugLogKind.API
    "recomposition" -> DebugLogKind.RECOMPOSITION
    "media" -> DebugLogKind.MEDIA
    "connection" -> DebugLogKind.CONNECTION
    "error" -> DebugLogKind.ERROR
    else -> null
}

private fun labelFor(kind: DebugLogKind): Int = when (kind) {
    DebugLogKind.API -> R.string.settings_logs_filter_api
    DebugLogKind.RECOMPOSITION -> R.string.settings_logs_filter_recomposition
    DebugLogKind.MEDIA -> R.string.settings_logs_filter_media
    DebugLogKind.CONNECTION -> R.string.settings_logs_filter_connection
    DebugLogKind.ERROR -> R.string.settings_logs_filter_error
}
