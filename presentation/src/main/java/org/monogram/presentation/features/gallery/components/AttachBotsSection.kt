package org.monogram.presentation.features.gallery.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.monogram.domain.models.AttachMenuBotModel
import org.monogram.presentation.R

private sealed interface GalleryRailItem {
    data object File : GalleryRailItem
    data object Poll : GalleryRailItem
    data class Bot(val bot: AttachMenuBotModel) : GalleryRailItem
}

@Composable
fun AttachBotsSection(
    modifier: Modifier = Modifier,
    bots: List<AttachMenuBotModel>,
    canAttachFiles: Boolean,
    canCreatePoll: Boolean,
    onAttachFileClick: () -> Unit,
    onCreatePollClick: () -> Unit,
    onAttachBotClick: (AttachMenuBotModel) -> Unit
) {
    val items = buildList {
        if (canAttachFiles) add(GalleryRailItem.File)
        if (canCreatePoll) add(GalleryRailItem.Poll)
        addAll(bots.map(GalleryRailItem::Bot))
    }

    if (items.isEmpty()) return

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(vertical = 6.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { item ->
                when (item) {
                    GalleryRailItem.File -> "file"
                    GalleryRailItem.Poll -> "poll"
                    is GalleryRailItem.Bot -> "bot_${item.bot.botUserId}"
                }
            }) { item ->
                when (item) {
                    GalleryRailItem.File -> {
                        RailActionChip(
                            label = stringResource(R.string.action_attach_file),
                            icon = Icons.Filled.Description,
                            onClick = onAttachFileClick
                        )
                    }

                    GalleryRailItem.Poll -> {
                        RailActionChip(
                            label = stringResource(R.string.action_create_poll),
                            icon = Icons.Filled.Poll,
                            onClick = onCreatePollClick
                        )
                    }

                    is GalleryRailItem.Bot -> {
                        RailBotChip(
                            bot = item.bot,
                            onClick = { onAttachBotClick(item.bot) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RailActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    emphasized: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (emphasized) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = if (emphasized) 1.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (emphasized) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (emphasized) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RailBotChip(
    bot: AttachMenuBotModel,
    onClick: () -> Unit
) {
    val colors = botTileColors(bot.name)
    Surface(
        modifier = Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .border(1.dp, colors.second.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val iconPath = bot.icon?.icon?.local?.path
            if (!iconPath.isNullOrBlank()) {
                AsyncImage(
                    model = iconPath,
                    contentDescription = bot.name,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .alpha(0.92f),
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(colors.first),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Extension,
                        contentDescription = null,
                        tint = colors.second,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            Text(
                text = bot.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun botTileColors(seed: String): Pair<Color, Color> {
    val hue = ((seed.hashCode() and 0x7fffffff) % 360).toFloat()
    val accent = Color.hsv(hue, 0.44f, 0.80f)
    val background = accent.copy(alpha = 0.12f)
    return background to accent
}
