package org.monogram.presentation.features.gallery.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.monogram.domain.models.AttachMenuBotModel
import org.monogram.presentation.R

@Composable
fun AttachBotsSection(
    modifier: Modifier = Modifier,
    bots: List<AttachMenuBotModel>,
    selectedCount: Int,
    canCreatePoll: Boolean,
    onAttachFileClick: () -> Unit,
    onCreatePollClick: () -> Unit,
    onSendSelected: () -> Unit,
    onAttachBotClick: (AttachMenuBotModel) -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(durationMillis = 220))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AnimatedVisibility(
                visible = selectedCount > 0,
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 },
                exit = fadeOut(tween(130)) + slideOutVertically(tween(130)) { it / 4 }
            ) {
                SendSelectedButton(
                    selectedCount = selectedCount,
                    onSendSelected = onSendSelected
                )
            }

            AnimatedVisibility(
                visible = selectedCount == 0,
                enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 2 },
                exit = fadeOut(tween(140))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickAttachActions(
                        canCreatePoll = canCreatePoll,
                        onAttachFileClick = onAttachFileClick,
                        onCreatePollClick = onCreatePollClick
                    )
                    if (bots.isNotEmpty()) {
                        LazyRow(
                            contentPadding = PaddingValues(bottom = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(bots, key = { it.botUserId }) { bot ->
                                AttachBotTile(
                                    bot = bot,
                                    onClick = { onAttachBotClick(bot) }
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
private fun QuickAttachActions(
    canCreatePoll: Boolean,
    onAttachFileClick: () -> Unit,
    onCreatePollClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val fileButtonModifier = if (canCreatePoll) Modifier.weight(1f) else Modifier.fillMaxWidth()
        OutlinedButton(
            onClick = onAttachFileClick,
            modifier = fileButtonModifier
        ) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.action_attach_file))
        }

        if (canCreatePoll) {
            OutlinedButton(
                onClick = onCreatePollClick,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.Poll,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.action_create_poll))
            }
        }
    }
}

@Composable
private fun SendSelectedButton(
    selectedCount: Int,
    onSendSelected: () -> Unit
) {
    Button(
        onClick = onSendSelected,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary
        )
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.action_send_items_count, selectedCount),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun AttachBotTile(
    bot: AttachMenuBotModel,
    onClick: () -> Unit
) {
    val colors = botTileColors(bot.name)
    Row(
        modifier = Modifier
            .size(width = 118.dp, height = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.first)
            .border(1.dp, colors.second.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val iconPath = bot.icon?.icon?.local?.path
        if (!iconPath.isNullOrBlank()) {
            AsyncImage(
                model = iconPath,
                contentDescription = bot.name,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(colors.second.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Extension,
                    contentDescription = null,
                    tint = colors.second,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        Text(
            text = bot.name,
            style = MaterialTheme.typography.labelLarge,
            color = colors.second,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun botTileColors(seed: String): Pair<Color, Color> {
    val hue = ((seed.hashCode() and 0x7fffffff) % 360).toFloat()
    val accent = Color.hsv(hue, 0.44f, 0.80f)
    val background = accent.copy(alpha = 0.12f)
    return background to accent
}
