package org.monogram.presentation.features.profile.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsViewer(
    title: String,
    data: Any?,
    onDismiss: () -> Unit,
    onLoadGraph: (String) -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (data == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 3.dp)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    when (data) {
                        is ChatStatisticsModel -> {
                            DateRangeHeader(data.period)
                            if (data.type == StatisticsType.SUPERGROUP) {
                                SupergroupStatistics(data, onLoadGraph)
                            } else {
                                ChannelStatistics(data, onLoadGraph)
                            }
                        }

                        is ChatRevenueStatisticsModel -> RevenueStatistics(data, onLoadGraph)
                        else -> FallbackView(data)
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
fun SupergroupStatistics(stats: ChatStatisticsModel, onLoadGraph: (String) -> Unit) {
    SectionTitle("Overview")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                modifier = Modifier.weight(1f),
                title = "Members",
                current = stats.memberCount.value,
                previous = stats.memberCount.previousValue,
                icon = Icons.Rounded.Groups,
                color = Color(0xFF4285F4)
            )
            stats.messageCount?.let {
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Messages",
                    current = it.value,
                    previous = it.previousValue,
                    icon = Icons.AutoMirrored.Rounded.Chat,
                    color = Color(0xFF34A853)
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            stats.viewerCount?.let {
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Viewers",
                    current = it.value,
                    previous = it.previousValue,
                    icon = Icons.Rounded.Visibility,
                    color = Color(0xFFFBBC04)
                )
            }
            stats.senderCount?.let {
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Active Senders",
                    current = it.value,
                    previous = it.previousValue,
                    icon = Icons.Rounded.Person,
                    color = Color(0xFFEA4335)
                )
            }
        }
    }

    stats.memberCountGraph?.let { GraphSection("Member Growth", it, Color(0xFF4285F4), onLoadGraph) }
    stats.joinGraph?.let { GraphSection("New Members", it, Color(0xFF34A853), onLoadGraph) }
    stats.muteGraph?.let { GraphSection("Notifications", it, Color(0xFFEA4335), onLoadGraph) }
    stats.messageContentGraph?.let { GraphSection("Message Content", it, Color(0xFF673AB7), onLoadGraph) }
    stats.actionGraph?.let { GraphSection("Actions", it, Color(0xFFFBBC04), onLoadGraph) }
    stats.dayGraph?.let { GraphSection("Activity by Day", it, Color(0xFF00BCD4), onLoadGraph) }
    stats.weekGraph?.let { GraphSection("Activity by Week", it, Color(0xFF3F51B5), onLoadGraph) }
    stats.topHoursGraph?.let { GraphSection("Top Hours", it, Color(0xFFFF9800), onLoadGraph) }
    stats.viewCountBySourceGraph?.let { GraphSection("Views by Source", it, Color(0xFF00BCD4), onLoadGraph) }
    stats.joinBySourceGraph?.let { GraphSection("New Members by Source", it, Color(0xFF3F51B5), onLoadGraph) }
    stats.languageGraph?.let { GraphSection("Languages", it, Color(0xFF673AB7), onLoadGraph) }

    if (stats.topSenders.isNotEmpty()) {
        SectionTitle("Top Senders")
        StatisticsListCard {
            val maxSent = stats.topSenders.maxOfOrNull { it.sentMessageCount }?.toFloat() ?: 1f
            stats.topSenders.take(5).forEachIndexed { index, sender ->
                UserBarChartItem(
                    userId = sender.userId.toString(),
                    primaryValue = sender.sentMessageCount.toString(),
                    primaryLabel = "msgs",
                    secondaryValue = "Avg chars: ${sender.averageCharacterCount}",
                    progress = sender.sentMessageCount / maxSent,
                    barColor = Color(0xFF34A853)
                )
                if (index < stats.topSenders.take(5).size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }

    if (stats.topAdministrators.isNotEmpty()) {
        SectionTitle("Top Administrators")
        StatisticsListCard {
            val maxActions =
                stats.topAdministrators.maxOfOrNull { it.deletedMessageCount + it.bannedUserCount }?.toFloat() ?: 1f
            stats.topAdministrators.take(5).forEachIndexed { index, admin ->
                val totalActions = admin.deletedMessageCount + admin.bannedUserCount
                UserBarChartItem(
                    userId = admin.userId.toString(),
                    primaryValue = totalActions.toString(),
                    primaryLabel = "actions",
                    secondaryValue = "Del: ${admin.deletedMessageCount} | Ban: ${admin.bannedUserCount}",
                    progress = totalActions / maxActions,
                    barColor = Color(0xFFEA4335)
                )
                if (index < stats.topAdministrators.take(5).size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }

    if (stats.topInviters.isNotEmpty()) {
        SectionTitle("Top Inviters")
        StatisticsListCard {
            val maxInvites = stats.topInviters.maxOfOrNull { it.addedMemberCount }?.toFloat() ?: 1f
            stats.topInviters.take(5).forEachIndexed { index, inviter ->
                UserBarChartItem(
                    userId = inviter.userId.toString(),
                    primaryValue = inviter.addedMemberCount.toString(),
                    primaryLabel = "invites",
                    secondaryValue = "Added members",
                    progress = inviter.addedMemberCount / maxInvites,
                    barColor = Color(0xFFFBBC04)
                )
                if (index < stats.topInviters.take(5).size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
fun ChannelStatistics(stats: ChatStatisticsModel, onLoadGraph: (String) -> Unit) {
    SectionTitle("Overview")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard(
            modifier = Modifier.fillMaxWidth(),
            title = "Subscribers",
            current = stats.memberCount.value,
            previous = stats.memberCount.previousValue,
            icon = Icons.Rounded.Groups,
            color = Color(0xFF4285F4)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            stats.viewCount?.let {
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Total Views",
                    current = it.value,
                    previous = it.previousValue,
                    icon = Icons.Rounded.Visibility,
                    color = Color(0xFFFBBC04)
                )
            }
            stats.meanViewCount?.let {
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Avg Views",
                    current = it.value,
                    previous = it.previousValue,
                    icon = Icons.Rounded.RemoveRedEye,
                    color = Color(0xFF34A853)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            stats.meanShareCount?.let {
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Avg Shares",
                    current = it.value,
                    previous = it.previousValue,
                    icon = Icons.Rounded.Share,
                    color = Color(0xFFEA4335)
                )
            }
            stats.enabledNotificationsPercentage?.let {
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Notifications",
                    current = it,
                    previous = it,
                    isPercentage = true,
                    icon = Icons.Rounded.NotificationsActive,
                    color = Color(0xFF673AB7)
                )
            }
        }
    }

    stats.memberCountGraph?.let { GraphSection("Growth", it, Color(0xFF4285F4), onLoadGraph) }
    stats.joinGraph?.let { GraphSection("New Subscribers", it, Color(0xFF34A853), onLoadGraph) }
    stats.muteGraph?.let { GraphSection("Notifications", it, Color(0xFFEA4335), onLoadGraph) }
    stats.viewCountByHourGraph?.let { GraphSection("Views by Hour", it, Color(0xFFFBBC04), onLoadGraph) }
    stats.viewCountBySourceGraph?.let { GraphSection("Views by Source", it, Color(0xFF00BCD4), onLoadGraph) }
    stats.joinBySourceGraph?.let { GraphSection("New Subscribers by Source", it, Color(0xFF3F51B5), onLoadGraph) }
    stats.languageGraph?.let { GraphSection("Languages", it, Color(0xFF673AB7), onLoadGraph) }
}

@Composable
fun RevenueStatistics(stats: ChatRevenueStatisticsModel, onLoadGraph: (String) -> Unit) {
    SectionTitle("Revenue")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Available Balance", style = MaterialTheme.typography.labelLarge, modifier = Modifier.alpha(0.8f))
            Text(
                text = "${stats.revenueAmount.availableBalance} ${stats.revenueAmount.currency}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Total Balance", style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(0.7f))
                    Text(
                        "${stats.revenueAmount.balance} ${stats.revenueAmount.currency}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Exchange Rate", style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(0.7f))
                    Text(
                        "1 USD ≈ ${stats.usdRate}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    GraphSection("Revenue Growth", stats.revenueGraph, Color(0xFF34A853), onLoadGraph)
    GraphSection("Hourly Revenue", stats.revenueByHourGraph, Color(0xFFFBBC04), onLoadGraph)
}

@Composable
fun GraphSection(title: String, graph: StatisticsGraphModel, color: Color, onLoadGraph: (String) -> Unit) {
    Column {
        SectionTitle(title)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            when (graph) {
                is StatisticsGraphModel.Data -> {
                    Box(modifier = Modifier.padding(20.dp)) {
                        SimpleBezierChartContent(color = color)
                        Text(
                            "Chart Data Loaded",
                            modifier = Modifier.align(Alignment.TopEnd),
                            style = MaterialTheme.typography.labelSmall,
                            color = color.copy(alpha = 0.7f)
                        )
                    }
                }

                is StatisticsGraphModel.Async -> {
                    LaunchedEffect(graph.token) {
                        onLoadGraph(graph.token)
                    }
                    Box(contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Loading chart...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                is StatisticsGraphModel.Error -> {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(16.dp)) {
                        Text(
                            graph.errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(
    modifier: Modifier = Modifier,
    title: String,
    current: Double,
    previous: Double,
    icon: ImageVector,
    color: Color = MaterialTheme.colorScheme.primary,
    isPercentage: Boolean = false
) {
    val diff = current - previous
    val percentageChange = if (previous != 0.0) (diff / previous) * 100 else 0.0

    val deltaColor = when {
        diff > 0 -> Color(0xFF4CAF50)
        diff < 0 -> Color(0xFFE91E63)
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }

    val deltaIcon = when {
        diff > 0 -> Icons.AutoMirrored.Rounded.TrendingUp
        diff < 0 -> Icons.AutoMirrored.Rounded.TrendingDown
        else -> Icons.Rounded.HorizontalRule
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(color.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = color
                    )
                }
                if (!isPercentage && previous != 0.0) {
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = deltaIcon,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = deltaColor
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "${String.format("%.1f", Math.abs(percentageChange))}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = deltaColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = when {
                                diff > 0 -> "+${diff.toInt()}"
                                diff < 0 -> diff.toInt().toString()
                                else -> "No change"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = deltaColor.copy(alpha = 0.8f),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val formattedValue = if (isPercentage) String.format("%.2f%%", current) else current.toInt().toString()
            Text(
                text = formattedValue,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun StatisticsListCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun UserBarChartItem(
    userId: String,
    primaryValue: String,
    primaryLabel: String,
    secondaryValue: String,
    progress: Float,
    barColor: Color = MaterialTheme.colorScheme.primary
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        userId.take(1).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("ID: $userId", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(
                        secondaryValue,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    primaryValue,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = barColor
                )
                Text(
                    primaryLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(barColor)
            )
        }
    }
}

@Composable
fun SimpleBezierChartContent(color: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        val path = Path().apply {
            moveTo(0f, height * 0.8f)
            cubicTo(
                width * 0.2f, height * 0.9f,
                width * 0.4f, height * 0.2f,
                width * 0.6f, height * 0.5f
            )
            cubicTo(
                width * 0.8f, height * 0.8f,
                width * 0.9f, height * 0.1f,
                width, height * 0.3f
            )
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        val fillPath = Path().apply {
            addPath(path)
            lineTo(width, height)
            lineTo(0f, height)
            close()
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.2f), Color.Transparent)
            )
        )
    }
}

@Composable
fun DateRangeHeader(period: DateRangeModel) {
    val days = (period.endDate - period.startDate) / (24 * 60 * 60)
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.wrapContentSize()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.CalendarToday,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${formatDate(period.startDate)} — ${formatDate(period.endDate)} ($days days)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
}

@Composable
fun FallbackView(data: Any) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Unknown Statistics Type",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Data class: ${data::class.simpleName}", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(12.dp))
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    text = data.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                        .fillMaxWidth()
                )
            }
        }
    }
}

private fun formatDate(timestamp: Int): String {
    val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp * 1000L))
}

private fun Modifier.alpha(alpha: Float) = this.then(Modifier.graphicsLayer(alpha = alpha))