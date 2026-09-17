package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.core.models.ContactCard
import org.monogram.core.models.Dice
import org.monogram.core.models.GeoPlace
import org.monogram.core.models.Message
import org.monogram.core.models.Poll
import org.monogram.core.models.VenueCard
import org.monogram.core.models.contactCard
import org.monogram.core.models.dice
import org.monogram.core.models.geoPlace
import org.monogram.core.models.poll
import org.monogram.core.models.venueCard
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
) {
    val mapTiles = mediaRepository?.mapTiles
    when (message.mediaKind) {
        "poll" -> message.poll?.let { PollBubbleCard(it, onPollVote, onShowPollVoters, modifier) }
        "geo" -> message.geoPlace?.let { LocationBubbleCard(it, mapTiles, modifier) }
        "venue" -> message.venueCard?.let { VenueBubbleCard(it, mapTiles, modifier) }
        "contact" -> message.contactCard?.let { ContactBubbleCard(it, modifier) }
        "dice" -> message.dice?.let { DiceBubbleCard(it, modifier) }
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
private fun PollBubbleCard(
    poll: Poll,
    onVote: ((List<ByteArray>) -> Unit)?,
    onShowVoters: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    CardShell(modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = poll.question,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            poll.answers.forEach { answer ->
                PollAnswerRow(poll = poll, text = answer.text, answer = answer, onVote = onVote)
            }
            if (poll.isQuiz && poll.isAnswered && poll.solution.isNotBlank()) {
                Text(
                    text = stringResource(R.string.dialog_poll_solution, poll.solution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = pollFooter(poll),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .then(
                        if (poll.publicVoters && onShowVoters != null) {
                            Modifier.clickable(onClick = onShowVoters)
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PollAnswerRow(
    poll: Poll,
    text: String,
    answer: org.monogram.core.models.PollAnswer,
    onVote: ((List<ByteArray>) -> Unit)?,
) {
    // One tap votes for that answer; the server state comes back as an update.
    val canVote = onVote != null && !poll.closed && answer.option.isNotEmpty() && !answer.chosen
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canVote) Modifier.clickable { onVote.invoke(listOf(answer.option)) } else Modifier),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (poll.showsShares) {
            if (poll.totalVoters > 0 || answer.hasVotes) {
                LinearProgressIndicator(
                    progress = { poll.share(answer) },
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
    val votes = pluralStringResource(R.plurals.dialog_poll_voters, poll.totalVoters, poll.totalVoters)
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
                text = stringResource(R.string.dialog_dice_result, dice.value),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

