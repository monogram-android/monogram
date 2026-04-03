@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.settings.storage

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.domain.models.ChatStorageUsageModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsTile
import java.util.*
import kotlin.math.roundToInt

private val ChartColors = listOf(
    Color(0xFF4285F4), // Blue
    Color(0xFF34A853), // Green
    Color(0xFFF9AB00), // Orange
    Color(0xFFEA4335), // Red
    Color(0xFFAF52DE), // Purple
    Color(0xFF00BFA5), // Teal
    Color(0xFFFBBC04), // Yellow
    Color(0xFFFA7B17), // Deep Orange
    Color(0xFFF06292), // Pink
    Color(0xFF4DB6AC), // Light Teal
    Color(0xFF7986CB), // Indigo
    Color(0xFF9575CD), // Deep Purple
    Color(0xFFAED581), // Light Green
    Color(0xFFFFD54F), // Amber
    Color(0xFFFF8A65), // Coral
    Color(0xFF90A4AE)  // Blue Grey
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageUsageContent(component: StorageUsageComponent) {
    val state by component.state.subscribeAsState()
    var selectedChatForDeletion by remember { mutableStateOf<ChatStorageUsageModel?>(null) }
    var showClearAllConfirmation by remember { mutableStateOf(false) }
    var showCacheLimitDialog by remember { mutableStateOf(false) }
    var showAutoClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.storage_usage_header),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = component::onBackClicked) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (state.isLoading) {
            Box(modifier = Modifier
                .fillMaxSize()
                .padding(padding), contentAlignment = Alignment.Center) {
                ContainedLoadingIndicator()
            }
        } else {
            val usage = state.usage
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp)
            ) {
                if (usage != null && usage.totalSize > 0) {
                    item {
                        SectionHeader(stringResource(R.string.overview_header))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            StorageChartHeader(
                                totalSize = usage.totalSize,
                                totalFileCount = usage.fileCount,
                                chatStats = usage.chatStats
                            )
                        }
                    }

                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Spacer(Modifier.height(24.dp))
                            FilledTonalButton(
                                onClick = { showClearAllConfirmation = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                contentPadding = PaddingValues(vertical = 16.dp),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Icon(Icons.Rounded.Delete, null)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    stringResource(R.string.clear_all_cache_format, formatSize(usage.totalSize)),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }

                            Text(
                                text = stringResource(R.string.clear_cache_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                                    .align(Alignment.CenterHorizontally),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                item {
                    SectionHeader(stringResource(R.string.settings_section_header))
                    SettingsTile(
                        icon = Icons.Rounded.SdStorage,
                        title = stringResource(R.string.cache_limit_title),
                        subtitle = formatCacheLimit(state.cacheLimitSize),
                        iconColor = Color(0xFF4285F4),
                        position = ItemPosition.TOP,
                        onClick = { showCacheLimitDialog = true }
                    )
                    Spacer(Modifier.height(2.dp))
                    SettingsTile(
                        icon = Icons.Rounded.Timer,
                        title = stringResource(R.string.auto_clear_cache_title),
                        subtitle = formatAutoClearTime(state.autoClearCacheTime),
                        iconColor = Color(0xFF34A853),
                        position = ItemPosition.MIDDLE,
                        onClick = { showAutoClearDialog = true }
                    )
                    Spacer(Modifier.height(2.dp))
                    SettingsTile(
                        icon = Icons.Rounded.AutoFixHigh,
                        title = stringResource(R.string.storage_optimizer_title),
                        subtitle = stringResource(R.string.storage_optimizer_subtitle),
                        iconColor = Color(0xFFAF52DE),
                        position = ItemPosition.BOTTOM,
                        onClick = { component.onStorageOptimizerChanged(!state.isStorageOptimizerEnabled) },
                        trailingContent = {
                            Switch(
                                checked = state.isStorageOptimizerEnabled,
                                onCheckedChange = { component.onStorageOptimizerChanged(it) }
                            )
                        }
                    )
                }

                if (usage != null && usage.totalSize > 0) {
                    item {
                        SectionHeader(stringResource(R.string.detailed_usage_header))
                    }
                    val sortedChats = usage.chatStats.sortedByDescending { it.size }
                    val maxChatSize = sortedChats.firstOrNull()?.size?.toFloat() ?: 1f

                    itemsIndexed(sortedChats) { index, chatUsage ->
                        val position = when {
                            sortedChats.size == 1 -> ItemPosition.STANDALONE
                            index == 0 -> ItemPosition.TOP
                            index == sortedChats.size - 1 -> ItemPosition.BOTTOM
                            else -> ItemPosition.MIDDLE
                        }
                        StorageItemRow(
                            chatUsage = chatUsage,
                            maxFileSize = maxChatSize,
                            position = position,
                            onClick = { selectedChatForDeletion = chatUsage }
                        )
                        if (index < sortedChats.size - 1) {
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                } else if (usage == null || usage.totalSize == 0L) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Rounded.Folder,
                                    null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.surfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    stringResource(R.string.storage_clean_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    stringResource(R.string.storage_clean_description),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(padding.calculateBottomPadding()))}
            }
        }
    }

    selectedChatForDeletion?.let { chat ->
        AlertDialog(
            onDismissRequest = { selectedChatForDeletion = null },
            title = { Text(stringResource(R.string.clear_cache_title_dialog)) },
            text = {
                Text(
                    stringResource(R.string.clear_cache_confirmation_format, chat.chatTitle, formatSize(chat.size))
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        component.onClearChatClicked(chat.chatId)
                        selectedChatForDeletion = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedChatForDeletion = null }) {
                    Text(stringResource(R.string.cancel_action))
                }
            }
        )
    }

    if (showClearAllConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirmation = false },
            title = { Text(stringResource(R.string.clear_all_cache_title)) },
            text = { Text(stringResource(R.string.clear_all_cache_confirmation)) },
            confirmButton = {
                Button(
                    onClick = {
                        component.onClearAllClicked()
                        showClearAllConfirmation = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_clear_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirmation = false }) {
                    Text(stringResource(R.string.cancel_action))
                }
            }
        )
    }

    if (showCacheLimitDialog) {
        val steps = 2
        val range = 0f..3f
        var sliderValue by remember {
            mutableFloatStateOf(
                when (state.cacheLimitSize) {
                    5L * 1024 * 1024 * 1024 -> 0f
                    10L * 1024 * 1024 * 1024 -> 1f
                    15L * 1024 * 1024 * 1024 -> 2f
                    else -> 3f
                }
            )
        }

        AlertDialog(
            onDismissRequest = { showCacheLimitDialog = false },
            title = { Text(stringResource(R.string.cache_limit_title)) },
            text = {
                Column {
                    Text(
                        text = when (sliderValue.roundToInt()) {
                            0 -> "5 GB"
                            1 -> "10 GB"
                            2 -> "15 GB"
                            else -> stringResource(R.string.unlimited_label)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        valueRange = range,
                        steps = steps
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val size = when (sliderValue.roundToInt()) {
                            0 -> 5L * 1024 * 1024 * 1024
                            1 -> 10L * 1024 * 1024 * 1024
                            2 -> 15L * 1024 * 1024 * 1024
                            else -> -1L
                        }
                        component.onCacheLimitSizeChanged(size)
                        showCacheLimitDialog = false
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCacheLimitDialog = false }) {
                    Text(stringResource(R.string.cancel_action))
                }
            }
        )
    }

    if (showAutoClearDialog) {
        val steps = 2
        val range = 0f..3f
        var sliderValue by remember {
            mutableFloatStateOf(
                when (state.autoClearCacheTime) {
                    1 -> 0f
                    7 -> 1f
                    30 -> 2f
                    else -> 3f
                }
            )
        }

        AlertDialog(
            onDismissRequest = { showAutoClearDialog = false },
            title = { Text(stringResource(R.string.auto_clear_cache_title)) },
            text = {
                Column {
                    Text(
                        text = when (sliderValue.roundToInt()) {
                            0 -> stringResource(R.string.every_day_label)
                            1 -> stringResource(R.string.every_week_label)
                            2 -> stringResource(R.string.every_month_label)
                            else -> stringResource(R.string.never_label)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        valueRange = range,
                        steps = steps
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val time = when (sliderValue.roundToInt()) {
                            0 -> 1
                            1 -> 7
                            2 -> 30
                            else -> -1
                        }
                        component.onAutoClearCacheTimeChanged(time)
                        showAutoClearDialog = false
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAutoClearDialog = false }) {
                    Text(stringResource(R.string.cancel_action))
                }
            }
        )
    }
}

@Composable
fun StorageChartHeader(
    totalSize: Long,
    totalFileCount: Int,
    chatStats: List<ChatStorageUsageModel>
) {
    val fileTypeStats = remember(chatStats) {
        chatStats.flatMap { it.byFileType }
            .groupBy { it.fileType }
            .map { (type, list) ->
                Triple(type, list.sumOf { it.size }, list.sumOf { it.fileCount })
            }
            .sortedByDescending { it.second }
    }

    val chartData = remember(fileTypeStats) {
        fileTypeStats.mapIndexed { index, (type, size, count) ->
            ChartSegment(size, count, ChartColors[index % ChartColors.size], formatFileType(type))
        }
    }

    val animationProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        animationProgress.animateTo(1f, animationSpec = tween(1000, easing = FastOutSlowInEasing))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
        ) {
            Canvas(modifier = Modifier.size(200.dp)) {
                val strokeWidth = 24.dp.toPx()
                val radius = size.minDimension / 2 - strokeWidth / 2
                var startAngle = -90f

                val total = chartData.sumOf { it.value }.toFloat()

                if (total > 0) {
                    chartData.forEach { segment ->
                        val sweepAngle = (segment.value / total) * 360f * animationProgress.value
                        drawArc(
                            color = segment.color,
                            startAngle = startAngle,
                            sweepAngle = sweepAngle,
                            useCenter = false,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                            size = Size(radius * 2, radius * 2),
                            topLeft = Offset(center.x - radius, center.y - radius)
                        )
                        startAngle += sweepAngle
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.total_used_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatSize(totalSize),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.files_count_label, totalFileCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        if (chartData.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                chartData.forEach { segment ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier
                            .size(12.dp)
                            .background(segment.color, CircleShape))
                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = segment.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.files_count_label, segment.count),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = formatSize(segment.value),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val percentage = if (totalSize > 0) (segment.value.toFloat() / totalSize * 100) else 0f
                            if (percentage >= 0.1f) {
                                Text(
                                    text = "${String.format(Locale.US, "%.1f", percentage)}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StorageItemRow(
    chatUsage: ChatStorageUsageModel,
    maxFileSize: Float,
    position: ItemPosition,
    onClick: () -> Unit
) {
    val relativeProgress = if (maxFileSize > 0) {
        (chatUsage.size / maxFileSize).coerceIn(0f, 1f)
    } else 0f

    val color =
        ChartColors[(chatUsage.chatId.hashCode() % ChartColors.size).let { if (it < 0) it + ChartColors.size else it }]

    val cornerRadius = 24.dp
    val shape = when (position) {
        ItemPosition.TOP -> RoundedCornerShape(
            topStart = cornerRadius,
            topEnd = cornerRadius,
            bottomStart = 4.dp,
            bottomEnd = 4.dp
        )

        ItemPosition.MIDDLE -> RoundedCornerShape(4.dp)
        ItemPosition.BOTTOM -> RoundedCornerShape(
            bottomStart = cornerRadius,
            bottomEnd = cornerRadius,
            topStart = 4.dp,
            topEnd = 4.dp
        )

        ItemPosition.STANDALONE -> RoundedCornerShape(cornerRadius)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chatUsage.chatTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 16.sp,
                    maxLines = 1
                )
                val fileTypesSummary = chatUsage.byFileType.sortedByDescending { it.size }.take(2)
                    .joinToString(", ") { formatFileType(it.fileType) }
                Text(
                    text = if (fileTypesSummary.isNotEmpty()) "$fileTypesSummary • ${
                        stringResource(
                            R.string.files_count_label,
                            chatUsage.fileCount
                        )
                    }" else stringResource(R.string.files_count_label, chatUsage.fileCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearWavyProgressIndicator(
                    progress = { relativeProgress },
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(4.dp)
                        .clip(CircleShape),
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            Text(
                text = formatSize(chatUsage.size),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp, top = 16.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

private data class ChartSegment(val value: Long, val count: Int, val color: Color, val label: String)

private fun formatFileType(type: String): String {
    return type.replace("FileType", "")
        .replace(Regex("(?<!^)([A-Z])"), " $1")
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format(Locale.US, "%.1f %cB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

@Composable
private fun formatCacheLimit(size: Long): String {
    return when (size) {
        -1L -> stringResource(R.string.unlimited_label)
        else -> formatSize(size)
    }
}

@Composable
private fun formatAutoClearTime(days: Int): String {
    return when (days) {
        -1 -> stringResource(R.string.never_label)
        1 -> stringResource(R.string.every_day_label)
        7 -> stringResource(R.string.every_week_label)
        30 -> stringResource(R.string.every_month_label)
        else -> stringResource(R.string.repeat_hours_format, days / 24)
    }
}
