package org.monogram.presentation.features.profile.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.*
import org.monogram.presentation.features.chats.chatList.components.SectionHeader
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsViewer(
    title: String,
    data: Any?,
    onDismiss: () -> Unit,
    onLoadGraph: (String) -> Unit = {}
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Crossfade(
                targetState = data,
                animationSpec = tween(500),
                modifier = Modifier.fillMaxWidth(),
                label = "ScreenState"
            ) { stateData ->
                if (stateData == null) {
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                strokeWidth = 4.dp,
                                strokeCap = StrokeCap.Round,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Analyzing Statistics...",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        when (stateData) {
                            is ChatStatisticsModel -> {
                                DateRangeHeader(stateData.period)
                                if (stateData.type == StatisticsType.SUPERGROUP) {
                                    SupergroupStatistics(stateData, onLoadGraph)
                                } else {
                                    ChannelStatistics(stateData, onLoadGraph)
                                }
                            }

                            is ChatRevenueStatisticsModel -> RevenueStatistics(stateData, onLoadGraph)
                            else -> FallbackView(stateData)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SupergroupStatistics(stats: ChatStatisticsModel, onLoadGraph: (String) -> Unit) {
    SectionHeader("Overview")
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
        ExpandableListSection(
            title = "Top Senders",
            icon = Icons.Rounded.Leaderboard,
            items = stats.topSenders,
            initialCount = 5
        ) { index, sender ->
            val maxSent = stats.topSenders.maxOfOrNull { it.sentMessageCount }?.toFloat() ?: 1f
            UserBarChartItem(
                userId = sender.userId.toString(),
                primaryValue = sender.sentMessageCount.toString(),
                primaryLabel = "msgs",
                secondaryValue = "Avg chars: ${sender.averageCharacterCount}",
                progress = sender.sentMessageCount / maxSent,
                barColor = Color(0xFF34A853)
            )
        }
    }

    if (stats.topAdministrators.isNotEmpty()) {
        ExpandableListSection(
            title = "Top Administrators",
            icon = Icons.Rounded.AdminPanelSettings,
            items = stats.topAdministrators,
            initialCount = 5
        ) { index, admin ->
            val maxActions =
                stats.topAdministrators.maxOfOrNull { it.deletedMessageCount + it.bannedUserCount }?.toFloat() ?: 1f
            val totalActions = admin.deletedMessageCount + admin.bannedUserCount
            UserBarChartItem(
                userId = admin.userId.toString(),
                primaryValue = totalActions.toString(),
                primaryLabel = "actions",
                secondaryValue = "Del: ${admin.deletedMessageCount} | Ban: ${admin.bannedUserCount}",
                progress = totalActions / maxActions,
                barColor = Color(0xFFEA4335)
            )
        }
    }

    if (stats.topInviters.isNotEmpty()) {
        ExpandableListSection(
            title = "Top Inviters",
            icon = Icons.Rounded.PersonAdd,
            items = stats.topInviters,
            initialCount = 5
        ) { index, inviter ->
            val maxInvites = stats.topInviters.maxOfOrNull { it.addedMemberCount }?.toFloat() ?: 1f
            UserBarChartItem(
                userId = inviter.userId.toString(),
                primaryValue = inviter.addedMemberCount.toString(),
                primaryLabel = "invites",
                secondaryValue = "Added members",
                progress = inviter.addedMemberCount / maxInvites,
                barColor = Color(0xFFFBBC04)
            )
        }
    }
}

@Composable
fun ChannelStatistics(stats: ChatStatisticsModel, onLoadGraph: (String) -> Unit) {
    SectionHeader("Overview")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard(
            modifier = Modifier.fillMaxWidth(),
            title = "Subscribers",
            current = stats.memberCount.value,
            previous = stats.memberCount.previousValue,
            icon = Icons.Rounded.Groups,
            color = Color(0xFF4285F4)
        )

        stats.enabledNotificationsPercentage?.let {
            StatCard(
                modifier = Modifier.fillMaxWidth(),
                title = "Notifications Enabled",
                current = it,
                previous = it,
                isPercentage = true,
                icon = Icons.Rounded.NotificationsActive,
                color = Color(0xFF673AB7)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            stats.meanViewCount?.let {
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Avg Msg Views",
                    current = it.value,
                    previous = it.previousValue,
                    icon = Icons.Rounded.RemoveRedEye,
                    color = Color(0xFF34A853)
                )
            }
            stats.meanShareCount?.let {
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Avg Msg Shares",
                    current = it.value,
                    previous = it.previousValue,
                    icon = Icons.Rounded.Share,
                    color = Color(0xFFEA4335)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            stats.meanReactionCount?.let {
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Avg Reactions",
                    current = it.value,
                    previous = it.previousValue,
                    icon = Icons.Rounded.EmojiEmotions,
                    color = Color(0xFFFBBC04)
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
    stats.messageContentGraph?.let { GraphSection("Message Interactions", it, Color(0xFF4CAF50), onLoadGraph) }
    stats.actionGraph?.let { GraphSection("Instant View Interactions", it, Color(0xFFFF9800), onLoadGraph) }
    stats.messageReactionGraph?.let { GraphSection("Message Reactions", it, Color(0xFF9C27B0), onLoadGraph) }

    if (stats.recentInteractions.isNotEmpty()) {
        ExpandableListSection(
            title = "Recent Interactions",
            icon = Icons.Rounded.DynamicFeed,
            items = stats.recentInteractions,
            initialCount = 5
        ) { _, interaction ->
            InteractionItem(interaction)
        }
    }
}

@Composable
fun InteractionItem(interaction: ChatInteractionInfoModel) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (interaction.type == ChatInteractionType.MESSAGE) Icons.AutoMirrored.Rounded.Message else Icons.Rounded.AmpStories,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (interaction.type == ChatInteractionType.MESSAGE) "Message" else "Story",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Icon(
                            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        InteractionStat(Icons.Rounded.Visibility, interaction.viewCount)
                        InteractionStat(Icons.Rounded.Share, interaction.forwardCount)
                        InteractionStat(Icons.Rounded.EmojiEmotions, interaction.reactionCount)
                    }
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                "Post ID",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                interaction.objectId.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InteractionStat(icon: ImageVector, count: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun <T> ExpandableListSection(
    title: String,
    icon: ImageVector,
    items: List<T>,
    initialCount: Int,
    itemContent: @Composable (index: Int, item: T) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    val displayItems = if (isExpanded) items else items.take(initialCount)

    Column(modifier = Modifier.animateContentSize(spring(stiffness = Spring.StiffnessLow))) {
        SectionHeader(title)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                displayItems.forEachIndexed { index, item ->
                    itemContent(index, item)
                    if (index < displayItems.size - 1) {
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }

                if (items.size > initialCount) {
                    FilledTonalButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Text(if (isExpanded) "Show Less" else "Show All (${items.size})", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RevenueStatistics(stats: ChatRevenueStatisticsModel, onLoadGraph: (String) -> Unit) {
    SectionHeader("Revenue")

    val animatedBalance by animateFloatAsState(
        targetValue = stats.revenueAmount.availableBalance.toFloat(),
        animationSpec = tween(1500, easing = FastOutSlowInEasing), label = "balance"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 4.dp
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.05f),
                    radius = size.width * 0.6f,
                    center = Offset(size.width, 0f)
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.05f),
                    radius = size.width * 0.4f,
                    center = Offset(0f, size.height)
                )
            }

            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                    shape = CircleShape
                ) {
                    Text(
                        "Available Balance",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format("%.2f", animatedBalance),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stats.revenueAmount.currency,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(bottom = 6.dp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "Total Balance",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.alpha(0.7f)
                        )
                        Text(
                            "${stats.revenueAmount.balance} ${stats.revenueAmount.currency}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "Exchange Rate",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.alpha(0.7f)
                        )
                        Text(
                            "1 USD ≈ ${stats.usdRate}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))
    GraphSection("Revenue Growth", stats.revenueGraph, Color(0xFF34A853), onLoadGraph)
    GraphSection("Hourly Revenue", stats.revenueByHourGraph, Color(0xFFFBBC04), onLoadGraph)
}

@Composable
fun GraphSection(title: String, graph: StatisticsGraphModel, color: Color, onLoadGraph: (String) -> Unit) {
    if (graph is StatisticsGraphModel.Error) return

    Column {
        SectionHeader(title)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            when (graph) {
                is StatisticsGraphModel.Data -> {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Insights Loaded",
                                style = MaterialTheme.typography.labelMedium,
                                color = color,
                                fontWeight = FontWeight.Bold
                            )
                            if (graph.zoomToken != null) {
                                Surface(
                                    color = color.copy(alpha = 0.1f),
                                    shape = CircleShape,
                                    modifier = Modifier.clickable { onLoadGraph(graph.zoomToken.toString()) }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.ZoomIn,
                                            contentDescription = null,
                                            tint = color,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "Zoom In",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = color,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        Box(modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = 16.dp)) {
                            SimpleBezierChartContent(color = color)
                        }
                    }
                }

                is StatisticsGraphModel.Async -> {
                    LaunchedEffect(graph.token) {
                        onLoadGraph(graph.token)
                    }
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                strokeWidth = 4.dp,
                                color = color,
                                strokeCap = StrokeCap.Round
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Rendering Chart...",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                        }
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

    var startAnimation by remember { mutableStateOf(false) }
    LaunchedEffect(current) { startAnimation = true }

    val animatedCurrent by animateFloatAsState(
        targetValue = if (startAnimation) current.toFloat() else 0f,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "stat_count"
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(color.copy(alpha = 0.15f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = color
                    )
                }
                if (!isPercentage && previous != 0.0) {
                    Surface(
                        color = deltaColor.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = deltaIcon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = deltaColor
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${String.format("%.1f", abs(percentageChange))}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = deltaColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))

            val formattedValue =
                if (isPercentage) String.format("%.2f%%", animatedCurrent) else animatedCurrent.toInt().toString()
            Text(
                text = formattedValue,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (!isPercentage && previous != 0.0) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when {
                            diff > 0 -> "+${diff.toInt()}"
                            diff < 0 -> "${diff.toInt()}"
                            else -> "No change"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = deltaColor,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = " vs previous",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
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
    var startAnimation by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { startAnimation = true }

    val animatedProgress by animateFloatAsState(
        targetValue = if (startAnimation) progress.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "progress"
    )

    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(barColor.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        userId.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = barColor
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("User ID: $userId", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
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
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = barColor
                )
                Text(
                    primaryLabel.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(barColor.copy(alpha = 0.6f), barColor)))
            )
        }
    }
}

@Composable
fun SimpleBezierChartContent(color: Color) {
    var isLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isLoaded = true }

    val animationProgress by animateFloatAsState(
        targetValue = if (isLoaded) 1f else 0f,
        animationSpec = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
        label = "chartDraw"
    )

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

        val fillPath = Path().apply {
            addPath(path)
            lineTo(width, height)
            lineTo(0f, height)
            close()
        }

        clipRect(right = width * animationProgress) {
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = 0.4f), Color.Transparent)
                )
            )

            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

@Composable
fun DateRangeHeader(period: DateRangeModel) {
    val days = (period.endDate - period.startDate) / (24 * 60 * 60)
    AssistChip(
        onClick = { },
        label = {
            Text(
                text = "${formatDate(period.startDate)} — ${formatDate(period.endDate)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        },
        leadingIcon = {
            Icon(
                Icons.Rounded.CalendarMonth,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        trailingIcon = {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = CircleShape
            ) {
                Text(
                    text = "$days days",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            labelColor = MaterialTheme.colorScheme.onSurface
        ),
        border = AssistChipDefaults.assistChipBorder(borderColor = Color.Transparent, enabled = true),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
fun FallbackView(data: Any) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Unknown Statistics Type",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Data class: ${data::class.simpleName}", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(16.dp))
            SelectionContainer {
                Text(
                    text = data.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                        .padding(16.dp)
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
