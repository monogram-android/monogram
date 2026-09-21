package org.monogram.feature.dialog.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.models.MessageViewer
import org.monogram.core.models.MessageViewers
import org.monogram.core.models.OutboxReadState
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.core.ui.components.PeerAvatar
import org.monogram.core.ui.menu.AppMenuAvatar
import org.monogram.core.ui.menu.AppMenuAvatarStack
import org.monogram.core.ui.menu.AppMenuDivider
import org.monogram.core.ui.menu.AppMenuGroup
import org.monogram.core.ui.menu.AppMenuItem
import org.monogram.core.ui.menu.AppMenuMotion
import org.monogram.core.ui.menu.AppMenuSurface
import org.monogram.feature.dialog.DialogTime
import org.monogram.feature.dialog.R
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun MessageSeenByRow(
    viewers: MessageViewers?,
    viewerAvatar: (MessageViewer) -> File?,
    onOpenProfile: (Long) -> Unit,
    modifier: Modifier = Modifier,
    startExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(startExpanded) }
    if (viewers !is MessageViewers.Ready && viewers != MessageViewers.Loading) return
    AppMenuSurface(modifier = modifier) {
        AnimatedContent(
            targetState = viewers to expanded,
            contentAlignment = Alignment.TopStart,
            contentKey = { (state, open) ->
                when (state) {
                    MessageViewers.Loading -> "loading"
                    is MessageViewers.Ready -> if (open && state.viewers.isNotEmpty()) "people" else "summary"
                    else -> "hidden"
                }
            },
            transitionSpec = {
                fadeIn(AppMenuMotion.ContentEnterSpec) togetherWith fadeOut(AppMenuMotion.ContentExitSpec)
            },
            label = "messageViewers",
        ) { (viewers, open) ->
            when (viewers) {
                null -> Unit
                MessageViewers.Loading -> {
                    AppMenuItem(
                        text = stringResource(R.string.dialog_seen_loading),
                        onClick = {},
                        leadingSpinner = true,
                        enabled = false,
                    )
                }

                is MessageViewers.Ready -> {
                    val list = viewers.viewers
                    if (open && list.isNotEmpty()) {
                        ExpandedViewerList(
                            viewers = list,
                            viewerAvatar = viewerAvatar,
                            onBack = { expanded = false },
                            onOpenProfile = onOpenProfile,
                        )
                    } else {
                        CollapsedViewerRow(
                            viewers = viewers,
                            viewerAvatar = viewerAvatar,
                            onOpenProfile = onOpenProfile,
                            onExpand = { expanded = true },
                        )
                    }
                }

                MessageViewers.Expired, MessageViewers.TooBig, MessageViewers.Unavailable -> Unit
            }
        }
    }
}

@Composable
internal fun PeerListSheet(
    kind: String?,
    users: List<MessageViewer>?,
    viewerAvatar: (MessageViewer) -> File?,
    onOpenProfile: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = stringResource(
        if (kind == "poll") R.string.dialog_poll_voters_title else R.string.dialog_reaction_users_title,
    )
    val yesterday = stringResource(R.string.dialog_yesterday)
    AppModalSheet(onDismissRequest = onDismiss) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmallEmphasized,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        when {
            users == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            users.isEmpty() -> {
                Text(
                    text = stringResource(R.string.dialog_peer_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(users, key = { "${it.peerId.value}:${it.date}" }) { viewer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .clickable { onOpenProfile(viewer.peerId.value) }
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            PeerAvatar(
                                title = viewer.title.orEmpty(),
                                size = 40.dp,
                                imageFile = viewerAvatar(viewer),
                            )
                            Text(
                                text = viewer.title ?: stringResource(R.string.dialog_seen_unknown_member),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            viewer.date.takeIf { it > 0 }?.let { date ->
                                Text(
                                    text = formatSeenDate(date, yesterdayLabel = yesterday),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun seenByLabelFor(
    viewers: MessageViewers?,
    nobody: String?,
    seenBy: (Int) -> String,
    playedBy: (Int) -> String,
): String? = when (viewers) {
    null, MessageViewers.Loading -> null
    is MessageViewers.Ready -> {
        val list = viewers.viewers
        val single = list.singleOrNull()
        when {
            list.isEmpty() -> nobody
            single != null && !single.title.isNullOrBlank() -> single.title.orEmpty()
            viewers.played -> playedBy(list.size)
            else -> seenBy(list.size)
        }
    }

    MessageViewers.Expired, MessageViewers.TooBig, MessageViewers.Unavailable -> null
}

@Composable
private fun CollapsedViewerRow(
    viewers: MessageViewers.Ready,
    viewerAvatar: (MessageViewer) -> File?,
    onOpenProfile: (Long) -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = viewers.viewers
    val yesterday = stringResource(R.string.dialog_yesterday)
    val single = list.singleOrNull()
    val resources = LocalContext.current.resources
    val label = seenByLabelFor(
        viewers = viewers,
        nobody = stringResource(R.string.dialog_nobody_viewed),
        seenBy = { count -> resources.getString(R.string.dialog_seen_by_count, count) },
        playedBy = { count -> resources.getString(R.string.dialog_played_by_count, count) },
    ).orEmpty()

    val opensProfile = single != null && single.date <= 0
    AppMenuItem(
        modifier = modifier,
        text = label,
        icon = if (list.isEmpty()) Icons.Outlined.Visibility else null,
        leading = if (list.isEmpty()) {
            null
        } else {
            {
                AppMenuAvatarStack(
                    avatars = list.map { AppMenuAvatar(it.title.orEmpty(), viewerAvatar(it)) },
                )
            }
        },
        trailingText = single?.takeIf { it.date > 0 }?.let { formatSeenDate(it.date, yesterdayLabel = yesterday) },
        enabled = list.isNotEmpty(),
        contentDescription = single?.title?.let {
            stringResource(R.string.dialog_seen_accessibility, it)
        },
        onClick = {
            if (opensProfile) onOpenProfile(single.peerId.value) else onExpand()
        },
    )
}

@Composable
private fun ExpandedViewerList(
    viewers: List<MessageViewer>,
    viewerAvatar: (MessageViewer) -> File?,
    onBack: () -> Unit,
    onOpenProfile: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val yesterday = stringResource(R.string.dialog_yesterday)
    Column(
        modifier = modifier.heightIn(max = 312.dp).verticalScroll(rememberScrollState()),
    ) {
        AppMenuGroup {
            AppMenuItem(
                text = stringResource(R.string.dialog_seen_list_back),
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                onClick = onBack,
            )
        }
        AppMenuDivider()
        AppMenuGroup {
            viewers.forEach { viewer ->
                AppMenuItem(
                    text = viewer.title ?: stringResource(R.string.dialog_seen_unknown_member),
                    leading = {
                        PeerAvatar(
                            title = viewer.title.orEmpty(),
                            size = 40.dp,
                            imageFile = viewerAvatar(viewer),
                        )
                    },
                    trailingText = viewer.date.takeIf { it > 0 }?.let { formatSeenDate(it, yesterdayLabel = yesterday) },
                    onClick = { onOpenProfile(viewer.peerId.value) },
                )
            }
        }
    }
}

@Composable
fun OutboxReadRow(
    state: OutboxReadState?,
    onOpenPrivacy: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val yesterday = stringResource(R.string.dialog_yesterday)
    val label = when (state) {
        null -> return
        OutboxReadState.Loading -> stringResource(R.string.dialog_seen_loading)
        is OutboxReadState.Read ->
            stringResource(R.string.dialog_read_at, formatSeenDate(state.date, yesterdayLabel = yesterday))
        OutboxReadState.Unread -> stringResource(R.string.dialog_read_unread)
        OutboxReadState.Expired -> stringResource(R.string.dialog_read_receipts_expired)
        OutboxReadState.PeerPrivacyHidden -> stringResource(R.string.dialog_read_unknown)

        OutboxReadState.MyPrivacyHidden -> stringResource(R.string.dialog_read_plain)
        OutboxReadState.Unavailable -> return
    }
    val clickable = state is OutboxReadState.MyPrivacyHidden && onOpenPrivacy != null
    AppMenuSurface(modifier = modifier) {
        AppMenuItem(
            text = label,
            icon = Icons.Outlined.Schedule,
            trailingText = if (state is OutboxReadState.MyPrivacyHidden) {
                stringResource(R.string.dialog_read_show_when)
            } else {
                null
            },
            leadingSpinner = state is OutboxReadState.Loading,
            enabled = clickable,
            onClick = { onOpenPrivacy?.invoke() },
        )
    }
}

internal fun formatSeenDate(
    epochSeconds: Long,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    yesterdayLabel: String = "Yesterday",
): String {
    if (epochSeconds <= 0) return ""
    val day = DialogTime.localDate(epochSeconds, zone)
    val today = DialogTime.localDate(nowSeconds, zone)
    val time = DialogTime.formatTime(epochSeconds, zone, locale)
    val dayLabel = day.format(DateTimeFormatter.ofPattern("d MMM", locale))
    return when (day) {
        today -> time
        today.minusDays(1) -> "$yesterdayLabel, $time"
        else -> "$dayLabel, $time"
    }
}
