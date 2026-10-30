package org.monogram.feature.dialog.ui

import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import org.monogram.core.ui.menu.AppMenuItem
import org.monogram.core.ui.menu.AppMenuPopup
import org.monogram.core.ui.menu.AppMenuSurface
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import java.text.NumberFormat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.core.models.ContactCard
import org.monogram.core.models.Dice
import org.monogram.core.models.GeoPlace
import org.monogram.core.models.MediaAnnouncement
import org.monogram.core.models.Message
import org.monogram.core.models.Poll
import org.monogram.core.models.VenueCard
import org.monogram.core.models.announcementCard
import org.monogram.core.models.contactCard
import org.monogram.core.models.dice
import org.monogram.core.models.geoPlace
import org.monogram.core.models.poll
import org.monogram.core.models.pollOptionHex
import org.monogram.core.models.venueCard
import org.monogram.core.ui.loading.MonogramBusyButton
import org.monogram.feature.dialog.R
import org.monogram.network.http.MapTileStore
import org.monogram.network.http.MediaRepository

/**
 * Cards for service media (polls, quizzes, geo/live locations, venues, contacts, dice).
 */
@Composable
internal fun ServiceMediaCard(
    message: Message,
    mediaRepository: MediaRepository?,
    modifier: Modifier = Modifier,
    onPollVote: ((List<ByteArray>) -> Unit)? = null,
    onShowPollVoters: (() -> Unit)? = null,
    onClosePoll: (() -> Unit)? = null,
    pollVoting: Boolean = false,
) {
    val mapTiles = mediaRepository?.mapTiles
    when (message.mediaKind) {
        "poll" -> message.poll?.let {
            PollBubbleCard(
                it,
                onPollVote.takeIf { message.id.id > 0 && !message.pending },
                onShowPollVoters,
                modifier,
                pollVoting,
                onClose = onClosePoll.takeIf { message.id.id > 0 && !message.pending })
        }

        "geo" -> message.geoPlace?.let { LocationBubbleCard(it, mapTiles, modifier) }
        "venue" -> message.venueCard?.let { VenueBubbleCard(it, mapTiles, modifier) }
        "contact" -> message.contactCard?.let { ContactBubbleCard(it, modifier) }
        "dice" -> message.dice?.let { DiceBubbleCard(it, modifier) }
        "giveaway", "giveaway_results", "paid_media", "game", "invoice", "story", "video_stream" ->
            message.announcementCard?.let {
                AnnouncementBubbleCard(
                    message.mediaKind,
                    it,
                    modifier
                )
            }

        else -> Unit
    }
}

@Composable
private fun CardShell(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .width(CardWidthDp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        content = content,
    )
}

@Composable
internal fun PollBubbleCard(
    poll: Poll,
    onVote: ((List<ByteArray>) -> Unit)?,
    onShowVoters: (() -> Unit)?,
    modifier: Modifier = Modifier,
    voting: Boolean = false,
    onClose: (() -> Unit)? = null,
) {
    var confirmClose by rememberSaveable(poll) { mutableStateOf(false) }
    val canClose = poll.creator && !poll.closed && onClose != null
    val canRetract =
        poll.isAnswered && !poll.isQuiz && !poll.revotingDisabled && !poll.closed && onVote != null
    var actionsOpen by remember { mutableStateOf(false) }
    if (confirmClose && canClose) AlertDialog(
        onDismissRequest = { confirmClose = false },
        title = { Text(stringResource(R.string.dialog_poll_close)) },
        text = { Text(stringResource(R.string.dialog_poll_close_confirm)) },
        confirmButton = {
            TextButton(onClick = { confirmClose = false; onClose?.invoke() }, enabled = !voting) {
                Text(stringResource(R.string.dialog_poll_close))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                confirmClose = false
            }) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
    var selected by rememberSaveable(poll) { mutableStateOf(listOf<String>()) }
    val canVote = onVote != null && !poll.closed && !poll.isAnswered && !voting
    Surface(modifier.width(CardWidthDp), color = Color.Transparent) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = poll.question,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
            poll.answers.forEach { answer ->
                val key = pollOptionHex(answer.option)
                key(key) {
                    PollAnswerRow(
                        poll = poll, text = answer.text, answer = answer,
                        selected = if (poll.multipleChoice && !poll.isAnswered) key in selected else answer.chosen,
                        onClick = if (canVote && answer.option.isNotEmpty()) {
                            {
                                if (poll.multipleChoice) {
                                    selected =
                                        if (key in selected) selected - key else selected + key
                                } else onVote?.invoke(listOf(answer.option.copyOf()))
                            }
                        } else null)
                }
            }
            if (poll.multipleChoice && !poll.isAnswered && !poll.closed && onVote != null) {
                MonogramBusyButton(
                    onClick = {
                        onVote(poll.answers.filter { pollOptionHex(it.option) in selected }
                            .map { it.option.copyOf() })
                    },
                    busy = voting,
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.dialog_poll_submit)) }
            }
            if (voting) Text(
                stringResource(R.string.dialog_poll_updating),
                style = MaterialTheme.typography.labelSmall
            )
            if (poll.isQuiz && poll.isAnswered && poll.solution.isNotBlank()) {
                Text(
                    text = stringResource(R.string.dialog_poll_solution, poll.solution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .then(
                            if (poll.publicVoters && onShowVoters != null)
                                Modifier.clickable(onClick = onShowVoters) else Modifier
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = pollFooter(poll),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (canClose || canRetract) Box {
                    IconButton(
                        onClick = { actionsOpen = true }, enabled = !voting,
                        shapes = IconButtonDefaults.shapes()
                    ) {
                        Icon(
                            Icons.Outlined.MoreVert, contentDescription = stringResource(
                                org.monogram.core.ui.R.string.media_more_actions
                            )
                        )
                    }
                    AppMenuPopup(
                        actionsOpen,
                        { actionsOpen = false },
                        alignToAnchorEnd = true,
                        scrim = true
                    ) {
                        AppMenuSurface {
                            Column {
                                if (canRetract) AppMenuItem(
                                    text = stringResource(R.string.dialog_poll_retract),
                                    icon = Icons.AutoMirrored.Outlined.Undo,
                                    enabled = !voting,
                                    onClick = { actionsOpen = false; onVote?.invoke(emptyList()) },
                                )
                                if (canClose) AppMenuItem(
                                    text = stringResource(R.string.dialog_poll_close),
                                    icon = Icons.Filled.Close,
                                    destructive = true,
                                    enabled = !voting,
                                    onClick = { actionsOpen = false; confirmClose = true },
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
private fun PollAnswerRow(
    poll: Poll,
    text: String,
    answer: org.monogram.core.models.PollAnswer,
    selected: Boolean,
    onClick: (() -> Unit)?,
) {
    val showResults = (poll.isAnswered || poll.closed) && poll.showsShares
    val fraction = if (poll.totalVoters > 0) (answer.voters.toFloat() / poll.totalVoters).coerceIn(
        0f,
        1f
    ) else 0f
    val share by animateFloatAsState(fraction, label = "pollShare")
    val rowColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "pollOption",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(rowColor)
            .heightIn(min = 48.dp)
            .toggleable(
                value = selected, enabled = onClick != null,
                role = if (poll.multipleChoice) Role.Checkbox else Role.RadioButton,
                onValueChange = { onClick?.invoke() })
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (poll.multipleChoice && !poll.isAnswered && !poll.closed) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    enabled = onClick != null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
            } else if (!poll.isAnswered && !poll.closed && !poll.multipleChoice) {
                RadioButton(
                    selected = selected,
                    onClick = null,
                    enabled = onClick != null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
            } else if (answer.chosen && !poll.isQuiz) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
            }
            if (poll.isQuiz && answer.hasVotes && (answer.correct || answer.chosen)) {
                Icon(
                    imageVector = if (answer.correct) Icons.Filled.Check else Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (answer.correct) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (showResults) Text(
                NumberFormat.getPercentInstance().format(fraction.toDouble()),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (showResults) {
            if (poll.totalVoters > 0 || answer.hasVotes) {
                LinearProgressIndicator(
                    progress = { share },
                    drawStopIndicator = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
            } else {
                // No votes yet: draw the empty track only, otherwise the indicator's rounded cap
                // reads as a stray dot at the end of every answer.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
    }
}

@Composable
private fun pollFooter(poll: Poll): String {
    val votes =
        pluralStringResource(R.plurals.dialog_poll_voters, poll.totalVoters, poll.totalVoters)
    val trait = when {
        poll.closed -> stringResource(R.string.dialog_poll_closed)
        poll.isQuiz -> stringResource(R.string.dialog_poll_quiz)
        poll.publicVoters -> stringResource(R.string.dialog_poll_public)
        else -> stringResource(R.string.dialog_poll_anonymous)
    }
    return "$votes · $trait"
}

@Composable
private fun LocationBubbleCard(
    place: GeoPlace,
    mapTiles: MapTileStore?,
    modifier: Modifier = Modifier,
) {
    var mapsOpen by remember { mutableStateOf(false) }
    if (mapsOpen) {
        LocationActionsSheet(
            coordinates = place.coordinates,
            latitude = place.latitude,
            longitude = place.longitude,
            address = "",
            onDismiss = { mapsOpen = false },
        )
    }
    CardShell(modifier = modifier, onClick = { mapsOpen = true }) {
        Column {
            LocationMap(place = place, mapTiles = mapTiles)
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = place.coordinates,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (place.live) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.dialog_location_live),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun VenueBubbleCard(
    venue: VenueCard,
    mapTiles: MapTileStore?,
    modifier: Modifier = Modifier,
) {
    var mapsOpen by remember { mutableStateOf(false) }
    if (mapsOpen) {
        LocationActionsSheet(
            coordinates = venue.place.coordinates,
            latitude = venue.place.latitude,
            longitude = venue.place.longitude,
            address = venue.address.ifBlank { venue.title },
            onDismiss = { mapsOpen = false },
        )
    }
    CardShell(modifier = modifier, onClick = { mapsOpen = true }) {
        Column {
            LocationMap(place = venue.place, mapTiles = mapTiles)
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = venue.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (venue.address.isNotBlank()) {
                    Text(
                        text = venue.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (venue.provider.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.dialog_venue_via, venue.provider),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = venue.place.coordinates,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ContactBubbleCard(contact: ContactCard, modifier: Modifier = Modifier) {
    CardShell(modifier) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = contact.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = contact.phone,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AnnouncementBubbleCard(
    kind: String?,
    announcement: MediaAnnouncement,
    modifier: Modifier = Modifier,
) {
    val heading = when (kind) {
        "giveaway" -> R.string.dialog_media_giveaway
        "giveaway_results" -> R.string.dialog_media_giveaway_results
        "paid_media" -> R.string.dialog_media_paid_media
        "game" -> R.string.dialog_media_game
        "invoice" -> R.string.dialog_media_invoice
        "story" -> R.string.dialog_media_story
        "video_stream" -> R.string.dialog_media_video_stream
        else -> R.string.dialog_media_document
    }
    val stats = buildList {
        when (kind) {
            "giveaway" -> {
                if (announcement.quantity > 0) {
                    add(
                        stringResource(
                            R.string.dialog_media_giveaway_prizes,
                            announcement.quantity
                        )
                    )
                }
                if (announcement.months > 0) {
                    add(stringResource(R.string.dialog_media_giveaway_months, announcement.months))
                }
                if (announcement.stars > 0) {
                    add(stringResource(R.string.dialog_media_giveaway_stars, announcement.stars))
                }
            }

            "giveaway_results" -> add(
                stringResource(
                    R.string.dialog_media_giveaway_winners,
                    announcement.winners,
                    announcement.unclaimed,
                ),
            )

            "paid_media" -> add(
                stringResource(
                    R.string.dialog_media_paid_media_items,
                    announcement.stars,
                    announcement.items,
                ),
            )

            "story" -> if (announcement.storyId > 0) {
                add(stringResource(R.string.dialog_media_story_number, announcement.storyId))
            }

            "video_stream" -> add(
                stringResource(
                    if (announcement.rtmp) R.string.dialog_media_rtmp_stream
                    else R.string.dialog_media_live_stream,
                ),
            )
        }
    }
    CardShell(modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(heading),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (announcement.title.isNotBlank()) {
                Text(
                    text = announcement.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            stats.filter { it.isNotBlank() }.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (announcement.description.isNotBlank()) {
                Text(
                    text = announcement.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (announcement.detail.isNotBlank()) {
                Text(
                    text = announcement.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DiceBubbleCard(dice: Dice, modifier: Modifier = Modifier) {
    CardShell(modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = dice.emoticon,
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = if (dice.value <= 0) stringResource(R.string.dialog_dice_pending)
                else stringResource(R.string.dialog_dice_result, dice.value),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

