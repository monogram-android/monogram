package org.monogram.presentation.features.profile.components

import org.monogram.presentation.core.util.coRunCatching
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import org.monogram.domain.models.*
import org.monogram.presentation.R
import org.monogram.presentation.features.chats.chatList.components.SectionHeader
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

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
                                stringResource(R.string.statistics_analyzing),
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OverviewSection(period = stats.period)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.statistics_members),
                    current = stats.memberCount.value,
                    previous = stats.memberCount.previousValue,
                    icon = Icons.Rounded.Groups,
                    color = Color(0xFF4285F4)
                )
                stats.messageCount?.let {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.statistics_messages),
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
                        title = stringResource(R.string.statistics_viewers),
                        current = it.value,
                        previous = it.previousValue,
                        icon = Icons.Rounded.Visibility,
                        color = Color(0xFFFBBC04)
                    )
                }
                stats.senderCount?.let {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.statistics_active_senders),
                        current = it.value,
                        previous = it.previousValue,
                        icon = Icons.Rounded.Person,
                        color = Color(0xFFEA4335)
                    )
                }
            }
        }
    }

    stats.memberCountGraph?.let { GraphSection(stringResource(R.string.statistics_member_growth), it, Color(0xFF4285F4), onLoadGraph) }
    stats.joinGraph?.let { GraphSection(stringResource(R.string.statistics_new_members), it, Color(0xFF34A853), onLoadGraph) }
    stats.muteGraph?.let { GraphSection(stringResource(R.string.notifications_title), it, Color(0xFFEA4335), onLoadGraph) }
    stats.messageContentGraph?.let { GraphSection(stringResource(R.string.statistics_message_content), it, Color(0xFF673AB7), onLoadGraph) }
    stats.actionGraph?.let { GraphSection(stringResource(R.string.statistics_actions), it, Color(0xFFFBBC04), onLoadGraph) }
    stats.dayGraph?.let { GraphSection(stringResource(R.string.statistics_activity_day), it, Color(0xFF00BCD4), onLoadGraph) }
    stats.weekGraph?.let { GraphSection(stringResource(R.string.statistics_activity_week), it, Color(0xFF3F51B5), onLoadGraph) }
    stats.topHoursGraph?.let { GraphSection(stringResource(R.string.statistics_top_hours), it, Color(0xFFFF9800), onLoadGraph) }
    stats.viewCountBySourceGraph?.let { GraphSection(stringResource(R.string.statistics_views_source), it, Color(0xFF00BCD4), onLoadGraph) }
    stats.joinBySourceGraph?.let { GraphSection(stringResource(R.string.statistics_new_members_source), it, Color(0xFF3F51B5), onLoadGraph) }
    stats.languageGraph?.let { GraphSection(stringResource(R.string.statistics_languages), it, Color(0xFF673AB7), onLoadGraph) }

    if (stats.topSenders.isNotEmpty()) {
        ExpandableListSection(
            title = stringResource(R.string.statistics_top_senders),
            icon = Icons.Rounded.Leaderboard,
            items = stats.topSenders,
            initialCount = 5
        ) { index, sender ->
            val maxSent = stats.topSenders.maxOfOrNull { it.sentMessageCount }?.toFloat() ?: 1f
            UserBarChartItem(
                rank = index + 1,
                userId = sender.userId.toString(),
                primaryValue = sender.sentMessageCount.toString(),
                primaryLabel = stringResource(R.string.statistics_msgs_label),
                secondaryValue = stringResource(R.string.statistics_avg_chars_format, sender.averageCharacterCount),
                progress = sender.sentMessageCount / maxSent,
                barColor = Color(0xFF34A853)
            )
        }
    }

    if (stats.topAdministrators.isNotEmpty()) {
        ExpandableListSection(
            title = stringResource(R.string.statistics_top_admins),
            icon = Icons.Rounded.AdminPanelSettings,
            items = stats.topAdministrators,
            initialCount = 5
        ) { index, admin ->
            val maxActions =
                stats.topAdministrators.maxOfOrNull { it.deletedMessageCount + it.bannedUserCount }?.toFloat() ?: 1f
            val totalActions = admin.deletedMessageCount + admin.bannedUserCount
            UserBarChartItem(
                rank = index + 1,
                userId = admin.userId.toString(),
                primaryValue = totalActions.toString(),
                primaryLabel = stringResource(R.string.statistics_actions_label),
                secondaryValue = stringResource(R.string.statistics_admin_actions_format, admin.deletedMessageCount, admin.bannedUserCount),
                progress = totalActions / maxActions,
                barColor = Color(0xFFEA4335)
            )
        }
    }

    if (stats.topInviters.isNotEmpty()) {
        ExpandableListSection(
            title = stringResource(R.string.statistics_top_inviters),
            icon = Icons.Rounded.PersonAdd,
            items = stats.topInviters,
            initialCount = 5
        ) { index, inviter ->
            val maxInvites = stats.topInviters.maxOfOrNull { it.addedMemberCount }?.toFloat() ?: 1f
            UserBarChartItem(
                rank = index + 1,
                userId = inviter.userId.toString(),
                primaryValue = inviter.addedMemberCount.toString(),
                primaryLabel = stringResource(R.string.statistics_invites_label),
                secondaryValue = stringResource(R.string.statistics_added_members_label),
                progress = inviter.addedMemberCount / maxInvites,
                barColor = Color(0xFFFBBC04)
            )
        }
    }
}

@Composable
fun ChannelStatistics(stats: ChatStatisticsModel, onLoadGraph: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OverviewSection(period = stats.period)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.statistics_subscribers),
                current = stats.memberCount.value,
                previous = stats.memberCount.previousValue,
                icon = Icons.Rounded.Groups,
                color = Color(0xFF4285F4)
            )

            stats.enabledNotificationsPercentage?.let {
                StatCard(
                    modifier = Modifier.fillMaxWidth(),
                    title = stringResource(R.string.statistics_notifications_enabled),
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
                        title = stringResource(R.string.statistics_avg_msg_views),
                        current = it.value,
                        previous = it.previousValue,
                        icon = Icons.Rounded.RemoveRedEye,
                        color = Color(0xFF34A853)
                    )
                }
                stats.meanShareCount?.let {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.statistics_avg_msg_shares),
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
                        title = stringResource(R.string.statistics_avg_reactions),
                        current = it.value,
                        previous = it.previousValue,
                        icon = Icons.Rounded.EmojiEmotions,
                        color = Color(0xFFFBBC04)
                    )
                }
            }
        }
    }

    stats.memberCountGraph?.let { GraphSection(stringResource(R.string.statistics_growth), it, Color(0xFF4285F4), onLoadGraph) }
    stats.joinGraph?.let { GraphSection(stringResource(R.string.statistics_new_subscribers), it, Color(0xFF34A853), onLoadGraph) }
    stats.muteGraph?.let { GraphSection(stringResource(R.string.notifications_title), it, Color(0xFFEA4335), onLoadGraph) }
    stats.viewCountByHourGraph?.let { GraphSection(stringResource(R.string.statistics_views_hour), it, Color(0xFFFBBC04), onLoadGraph) }
    stats.viewCountBySourceGraph?.let { GraphSection(stringResource(R.string.statistics_views_source), it, Color(0xFF00BCD4), onLoadGraph) }
    stats.joinBySourceGraph?.let { GraphSection(stringResource(R.string.statistics_new_members_source), it, Color(0xFF3F51B5), onLoadGraph) }
    stats.languageGraph?.let { GraphSection(stringResource(R.string.statistics_languages), it, Color(0xFF673AB7), onLoadGraph) }
    stats.messageContentGraph?.let { GraphSection(stringResource(R.string.statistics_msg_interactions), it, Color(0xFF4CAF50), onLoadGraph) }
    stats.actionGraph?.let { GraphSection(stringResource(R.string.statistics_iv_interactions), it, Color(0xFFFF9800), onLoadGraph) }
    stats.messageReactionGraph?.let { GraphSection(stringResource(R.string.statistics_msg_reactions), it, Color(0xFF9C27B0), onLoadGraph) }

    if (stats.recentInteractions.isNotEmpty()) {
        ExpandableListSection(
            title = stringResource(R.string.statistics_recent_interactions),
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
    val totalEngagement = interaction.forwardCount + interaction.reactionCount
    val engagementRate = if (interaction.viewCount > 0) {
        (totalEngagement.toFloat() / interaction.viewCount.toFloat()) * 100f
    } else {
        0f
    }
    val shareRate = if (interaction.viewCount > 0) {
        (interaction.forwardCount.toFloat() / interaction.viewCount.toFloat()) * 100f
    } else {
        0f
    }
    val reactionRate = if (interaction.viewCount > 0) {
        (interaction.reactionCount.toFloat() / interaction.viewCount.toFloat()) * 100f
    } else {
        0f
    }
    val dominantMetric =
        maxOf(interaction.viewCount, interaction.forwardCount, interaction.reactionCount).coerceAtLeast(1)

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
                            text = if (interaction.type == ChatInteractionType.MESSAGE) stringResource(R.string.statistics_interaction_message) else stringResource(R.string.statistics_interaction_story),
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

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        InteractionStat(Icons.Rounded.Visibility, interaction.viewCount)
                        InteractionStat(Icons.Rounded.Share, interaction.forwardCount)
                        InteractionStat(Icons.Rounded.EmojiEmotions, interaction.reactionCount)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { (interaction.viewCount.toFloat() / dominantMetric.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        drawStopIndicator = {}
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactInsightChip(
                            label = "Share rate",
                            value = String.format("%.1f%%", shareRate),
                            tint = Color(0xFFEA4335)
                        )
                        CompactInsightChip(
                            label = "Reaction rate",
                            value = String.format("%.1f%%", reactionRate),
                            tint = Color(0xFFFBBC04)
                        )
                    }
                    interaction.previewText?.takeIf { it.isNotBlank() }?.let { preview ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = preview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
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
                                stringResource(R.string.statistics_post_id),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                interaction.objectId.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "ER",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = String.format("%.1f%%", engagementRate),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Total engagement: ${formatStatNumber(totalEngagement)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactInsightChip(label: String, value: String, tint: Color) {
    Surface(
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = tint
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                fontWeight = FontWeight.Bold
            )
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
                        Text(if (isExpanded) stringResource(R.string.statistics_show_less) else stringResource(R.string.statistics_show_all_format, items.size), fontWeight = FontWeight.Bold)
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
    SectionHeader(stringResource(R.string.statistics_revenue_header))

    val available = stats.revenueAmount.availableBalance
    val total = stats.revenueAmount.balance

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.statistics_available_balance),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = formatLongCompact(available),
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stats.revenueAmount.currency,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }

                Surface(
                    color = Color(0xFF34A853).copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Payments,
                            contentDescription = null,
                            tint = Color(0xFF34A853),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(R.string.revenue_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF34A853),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RevenueMetricCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.statistics_total_balance),
                    value = "${formatLongCompact(total)} ${stats.revenueAmount.currency}",
                    tint = Color(0xFF4285F4)
                )
                RevenueMetricCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.statistics_available_balance),
                    value = "${formatLongCompact(available)} ${stats.revenueAmount.currency}",
                    tint = Color(0xFFFF9800)
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.statistics_exchange_rate),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "1 USD = ${
                            String.format(
                                Locale.getDefault(),
                                "%.4f",
                                stats.usdRate
                            )
                        } ${stats.revenueAmount.currency}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    GraphSection(stringResource(R.string.statistics_revenue_growth), stats.revenueGraph, Color(0xFF34A853), onLoadGraph)
    GraphSection(stringResource(R.string.statistics_hourly_revenue), stats.revenueByHourGraph, Color(0xFFFBBC04), onLoadGraph)
}

@Composable
private fun RevenueMetricCard(modifier: Modifier = Modifier, label: String, value: String, tint: Color) {
    Surface(
        modifier = modifier,
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = tint
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun GraphSection(title: String, graph: StatisticsGraphModel, color: Color, onLoadGraph: (String) -> Unit) {
    if (graph is StatisticsGraphModel.Error) return
    val parsedGraph = remember(graph) {
        if (graph is StatisticsGraphModel.Data) parseStatisticsGraph(graph.jsonData) else null
    }

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
                    Column(modifier = Modifier.padding(20.dp)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            if (parsedGraph != null) {
                                InteractiveStatisticsChart(parsedGraph, color)
                            } else {
                                SimpleBezierChartContent(color = color)
                            }
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
                                stringResource(R.string.statistics_rendering_chart),
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
    isPercentage: Boolean = false,
    showComparison: Boolean = true
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
                if (showComparison && !isPercentage && previous != 0.0) {
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

            if (showComparison && !isPercentage && previous != 0.0) {
                Spacer(modifier = Modifier.height(6.dp))
                if (diff == 0.0) {
                    Text(
                        text = "${stringResource(R.string.statistics_no_change)} ${stringResource(R.string.statistics_vs_previous)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (diff > 0) "+${diff.toInt()}" else "${diff.toInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = deltaColor,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.statistics_vs_previous),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun UserBarChartItem(
    rank: Int,
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            color = barColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "#$rank",
                                style = MaterialTheme.typography.labelSmall,
                                color = barColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = "${stringResource(R.string.label_id)}: $userId",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
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
private fun OverviewSection(period: DateRangeModel) {
    Text(
        text = stringResource(R.string.statistics_overview),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, bottom = 4.dp)
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "${formatDate(period.startDate)} - ${formatDate(period.endDate)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(10.dp)
            ) {
                val days = ((period.endDate - period.startDate) / (24 * 60 * 60)).coerceAtLeast(1)
                Text(
                    text = stringResource(R.string.days_count_format, days),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(2.dp))
}

@Composable
private fun InteractiveStatisticsChart(graph: ParsedStatisticsGraph, accentColor: Color) {
    if (graph.series.isEmpty() || graph.labels.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.statistics_rendering_chart),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    var selectedSeriesIndex by remember(graph) { mutableStateOf(0) }
    var selectedPointIndex by remember(graph) { mutableStateOf(graph.labels.lastIndex.coerceAtLeast(0)) }
    val activeSeries = graph.series[selectedSeriesIndex.coerceIn(0, graph.series.lastIndex)]
    val safePointIndex = selectedPointIndex.coerceIn(0, graph.labels.lastIndex)
    val markerInnerColor = MaterialTheme.colorScheme.surface

    Column(modifier = Modifier.fillMaxSize()) {
        if (graph.series.size > 1) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                graph.series.forEachIndexed { index, series ->
                    FilterChip(
                        selected = index == selectedSeriesIndex,
                        onClick = {
                            selectedSeriesIndex = index
                            selectedPointIndex = graph.labels.lastIndex
                        },
                        label = {
                            Text(
                                text = series.name,
                                maxLines = 1
                            )
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(series.color, CircleShape)
                            )
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = graph.labels[safePointIndex],
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatStatNumber(activeSeries.values[safePointIndex].toInt()),
                    style = MaterialTheme.typography.titleLarge,
                    color = activeSeries.color,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "Avg ${formatStatNumber(activeSeries.values.average().toInt())}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f))
                .pointerInput(graph, selectedSeriesIndex) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            selectedPointIndex = pointIndexForOffset(
                                offset.x,
                                size.width.toFloat(),
                                graph.labels.size
                            )
                        },
                        onDrag = { change, _ ->
                            selectedPointIndex = pointIndexForOffset(
                                change.position.x,
                                size.width.toFloat(),
                                graph.labels.size
                            )
                        }
                    )
                }
                .pointerInput(graph, selectedSeriesIndex) {
                    detectTapGestures { offset ->
                        selectedPointIndex = pointIndexForOffset(
                            offset.x,
                            size.width.toFloat(),
                            graph.labels.size
                        )
                    }
                }
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            drawChartGrid()

            val lineThickness = 3.dp.toPx()
            graph.series.forEachIndexed { index, series ->
                val alpha = if (index == selectedSeriesIndex) 1f else 0.3f
                drawSeriesPath(series = series, strokeWidth = lineThickness, alpha = alpha)
            }

            val xStep = if (graph.labels.size > 1) size.width / (graph.labels.size - 1) else 0f
            val pointX = xStep * safePointIndex
            drawLine(
                color = accentColor.copy(alpha = 0.35f),
                start = Offset(pointX, 0f),
                end = Offset(pointX, size.height),
                strokeWidth = 1.dp.toPx()
            )

            val y = normalizeToCanvasY(activeSeries.values[safePointIndex], activeSeries.values, size.height)
            drawCircle(
                color = activeSeries.color,
                radius = 6.dp.toPx(),
                center = Offset(pointX, y)
            )
            drawCircle(
                color = markerInnerColor,
                radius = 3.dp.toPx(),
                center = Offset(pointX, y)
            )
        }
    }
}

private fun DrawScope.drawSeriesPath(series: ParsedStatisticsSeries, strokeWidth: Float, alpha: Float) {
    if (series.values.isEmpty()) return
    val values = series.values
    val xStep = if (values.size > 1) size.width / (values.size - 1) else 0f

    val linePath = Path().apply {
        values.forEachIndexed { index, value ->
            val point = Offset(
                x = xStep * index,
                y = normalizeToCanvasY(value, values, size.height)
            )
            if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
        }
    }

    val fillPath = Path().apply {
        addPath(linePath)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }

    drawPath(
        path = fillPath,
        brush = Brush.verticalGradient(
            colors = listOf(series.color.copy(alpha = 0.20f * alpha), Color.Transparent)
        )
    )
    drawPath(
        path = linePath,
        color = series.color.copy(alpha = alpha),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

private fun DrawScope.drawChartGrid() {
    val stroke = 1.dp.toPx()
    repeat(4) { index ->
        val y = size.height * (index + 1) / 5f
        drawLine(
            color = Color.Gray.copy(alpha = 0.22f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = stroke
        )
    }
}

private fun normalizeToCanvasY(value: Float, allValues: List<Float>, canvasHeight: Float): Float {
    val minValue = allValues.minOrNull() ?: 0f
    val maxValue = allValues.maxOrNull() ?: 1f
    if (maxValue <= minValue) return canvasHeight * 0.5f
    val normalized = (value - minValue) / (maxValue - minValue)
    return canvasHeight - (normalized * canvasHeight)
}

private fun pointIndexForOffset(x: Float, width: Float, pointCount: Int): Int {
    if (pointCount <= 1 || width <= 0f) return 0
    val safeX = x.coerceIn(0f, width)
    return ((safeX / width) * (pointCount - 1)).roundToInt().coerceIn(0, pointCount - 1)
}

private data class ParsedStatisticsGraph(
    val labels: List<String>,
    val series: List<ParsedStatisticsSeries>
)

private data class ParsedStatisticsSeries(
    val key: String,
    val name: String,
    val color: Color,
    val values: List<Float>
)

private fun parseStatisticsGraph(jsonData: String): ParsedStatisticsGraph? {
    return coRunCatching {
        val root = JSONObject(jsonData)
        val columnsArray = root.optJSONArray("columns") ?: return null
        val typesObject = root.optJSONObject("types") ?: return null
        val namesObject = root.optJSONObject("names") ?: JSONObject()
        val colorsObject = root.optJSONObject("colors") ?: JSONObject()

        val columnValuesByKey = linkedMapOf<String, List<Float>>()
        for (i in 0 until columnsArray.length()) {
            val column = columnsArray.optJSONArray(i) ?: continue
            if (column.length() < 2) continue
            val key = column.optString(0)
            if (key.isBlank()) continue
            val values = mutableListOf<Float>()
            for (j in 1 until column.length()) {
                values.add(column.optDouble(j, 0.0).toFloat())
            }
            columnValuesByKey[key] = values
        }

        val xKey = typesObject.keys().asSequence().firstOrNull { typesObject.optString(it) == "x" }
        val xValues = xKey?.let { columnValuesByKey[it] }.orEmpty()
        val labelCount = xValues.size.takeIf { it > 0 }
            ?: columnValuesByKey.values.maxOfOrNull { it.size }
            ?: return null

        val labels = (0 until labelCount).map { index ->
            val rawX = xValues.getOrNull(index)?.toLong() ?: index.toLong()
            formatChartXAxisLabel(rawX, labelCount)
        }

        val series = mutableListOf<ParsedStatisticsSeries>()
        val typeKeys = typesObject.keys().asSequence().toList()
        typeKeys.forEach { key ->
            if (typesObject.optString(key) == "x") return@forEach
            val values = columnValuesByKey[key].orEmpty()
            if (values.isEmpty()) return@forEach
            val paddedValues = if (values.size < labelCount) {
                values + List(labelCount - values.size) { values.last() }
            } else {
                values.take(labelCount)
            }
            val colorString = colorsObject.optString(key)
            series += ParsedStatisticsSeries(
                key = key,
                name = namesObject.optString(key).ifBlank { key },
                color = colorString.toComposeColorOrNull() ?: Color(0xFF4285F4),
                values = paddedValues
            )
        }

        if (series.isEmpty()) null else ParsedStatisticsGraph(labels = labels, series = series)
    }.getOrNull()
}

private fun formatChartXAxisLabel(rawValue: Long, pointCount: Int): String {
    return if (rawValue > 10_000_000_000L) {
        val date = Date(rawValue)
        if (pointCount <= 24) SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        else SimpleDateFormat("dd MMM", Locale.getDefault()).format(date)
    } else if (rawValue > 1_000_000_000L) {
        val date = Date(rawValue * 1000L)
        if (pointCount <= 24) SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        else SimpleDateFormat("dd MMM", Locale.getDefault()).format(date)
    } else {
        rawValue.toString()
    }
}

private fun String.toComposeColorOrNull(): Color? {
    if (!startsWith("#")) return null
    return coRunCatching { Color(android.graphics.Color.parseColor(this)) }.getOrNull()
}

private fun formatStatNumber(value: Int): String {
    val absValue = abs(value)
    return when {
        absValue >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000f)
        absValue >= 1_000 -> String.format(Locale.getDefault(), "%.1fK", value / 1_000f)
        else -> value.toString()
    }
}

private fun formatLongCompact(value: Long): String {
    val absValue = abs(value)
    return when {
        absValue >= 1_000_000_000L -> String.format(Locale.getDefault(), "%.1fB", value / 1_000_000_000f)
        absValue >= 1_000_000L -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000f)
        absValue >= 1_000L -> String.format(Locale.getDefault(), "%.1fK", value / 1_000f)
        else -> value.toString()
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
                    text = stringResource(R.string.days_count_format, days),
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
                    text = stringResource(R.string.statistics_unknown_type),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = stringResource(R.string.statistics_data_class_format, data::class.simpleName ?: "Unknown"), style = MaterialTheme.typography.bodyMedium)
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
