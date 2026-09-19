package org.monogram.feature.profile.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.monogram.core.common.Outcome
import org.monogram.core.models.Profile
import org.monogram.core.ui.ExpressiveDefaults
import org.monogram.core.ui.components.EmojiStatusMark
import org.monogram.core.ui.components.PeerAvatar
import org.monogram.core.ui.components.SponsorBadge
import org.monogram.core.ui.components.isPeerOnline
import org.monogram.core.ui.components.peerStatusLabel
import org.monogram.core.ui.components.rememberPeerStatusNow
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.rememberCacheGeneration
import org.monogram.core.ui.rememberEnsuredFile
import org.monogram.feature.profile.R
import org.monogram.network.http.MediaRepository

private val ProfileAvatarSize = 112.dp
private val ProfileAvatarLoadingSize = 44.dp

@Composable
internal fun ProfileHeader(
    profile: Profile,
    avatarFile: java.io.File?,
    avatarState: ProfileAvatar,
    avatarGeneration: Int,
    avatarScale: Float,
    mediaRepository: MediaRepository?,
    onOpenAvatar: () -> Unit,
    onRetryAvatar: () -> Unit,
    onMessage: () -> Unit,
    onCopyUsername: (() -> Unit)?,
    snackbar: SnackbarHostState,
    clipboard: Clipboard,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val copied = stringResource(R.string.profile_copied)
    val copiedValue = stringResource(R.string.profile_copied_value)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(ProfileAvatarSize)
                .graphicsLayer {
                    scaleX = avatarScale
                    scaleY = avatarScale
                }
                .clip(CircleShape)
                .clickable { onOpenAvatar() },
            contentAlignment = Alignment.Center,
        ) {
            PeerAvatar(
                title = profile.title,
                size = ProfileAvatarSize,
                imageFile = avatarFile,
            )
            MonogramLoading(
                visible = avatarState is ProfileAvatar.Loading,
                size = ProfileAvatarLoadingSize,
                generation = avatarGeneration,
            )
            if (avatarState is ProfileAvatar.Failed) {
                IconButton(onClick = onRetryAvatar, modifier = Modifier.size(ProfileAvatarLoadingSize)) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.profile_retry_avatar),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = profile.title,
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    textAlign = TextAlign.Center,
                )
                ProfileEmojiStatus(
                    documentId = profile.emojiStatusDocumentId,
                    mediaRepository = mediaRepository,
                )
                if (profile.isVerified) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = stringResource(R.string.profile_verified),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                SponsorBadge(peerId = profile.id.value, size = 20.dp, gap = 0.dp)
            }
            if (profile.isSelf) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.profile_self_badge),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            } else {
                val presenceNow = rememberPeerStatusNow(profile.status, profile.statusAt)
                val subtitle = profileSubtitle(profile, presenceNow)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isPeerOnline(profile.status, profile.statusAt, presenceNow)) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (profile.isScam) {
                Text(
                    text = stringResource(R.string.profile_scam),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        ProfileStats(profile)
        if (!profile.isSelf) {
            ProfileActions(
                profile = profile,
                onMessage = onMessage,
                onCopyUsername = onCopyUsername?.let { copy ->
                    {
                        copy()
                        scope.launch { snackbar.showSnackbar(copied) }
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
        }
        ProfileInfoSection(
            profile = profile,
            onCopy = { value ->
                if (value.isNotBlank()) {
                    scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("text", value))) }
                    scope.launch { snackbar.showSnackbar(copiedValue) }
                }
            },
        )
    }
}

@Composable
private fun profileSubtitle(profile: Profile, nowMillis: Long): String? {
    peerStatusLabel(profile.status, profile.statusAt, nowMillis)?.let { return it }
    if (profile.isBot) return stringResource(R.string.profile_kind_bot)
    return when (profile.kind) {
        "channel" -> profile.membersCount?.let {
            stringResource(R.string.profile_subscribers_count, it)
        } ?: stringResource(R.string.profile_kind_channel)
        "chat", "group" -> profile.membersCount?.let {
            stringResource(R.string.profile_members_count, it)
        } ?: stringResource(R.string.profile_kind_group)
        else -> null
    }
}

@Composable
private fun ProfileStats(profile: Profile) {
    val items = buildList {
        when (profile.kind) {
            "channel" -> profile.membersCount?.let {
                add(it.toString() to stringResource(R.string.profile_subscribers_label))
            }
            "chat", "group" -> {
                profile.membersCount?.let {
                    add(it.toString() to stringResource(R.string.profile_members_label))
                }
                profile.onlineCount?.let {
                    add(it.toString() to stringResource(R.string.profile_online_label))
                }
            }
        }
    }
    if (items.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .animateContentSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (value, label) ->
            Surface(
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 14.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProfileActions(
    profile: Profile,
    onMessage: () -> Unit,
    onCopyUsername: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FilledTonalButton(
            onClick = onMessage,
            modifier = Modifier.weight(1f).height(48.dp),
            shapes = ExpressiveDefaults.largeButtonShapes(),
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Chat,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(
                text = when (profile.kind) {
                    "channel" -> stringResource(R.string.profile_open_channel)
                    "chat", "group" -> stringResource(R.string.profile_open_group)
                    else -> stringResource(R.string.profile_message)
                },
            )
        }
        if (onCopyUsername != null) {
            FilledTonalIconButton(
                onClick = onCopyUsername,
                modifier = Modifier.size(48.dp),
                shapes = ExpressiveDefaults.iconButtonShapes(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.profile_copy_username),
                )
            }
        }
    }
}

@Composable
private fun ProfileEmojiStatus(
    documentId: Long?,
    mediaRepository: MediaRepository?,
) {
    val id = documentId ?: return
    val key = "emoji:$id"
    val cacheGeneration = rememberCacheGeneration(
        remember(mediaRepository, key) { mediaRepository?.cacheGeneration(key) },
    )
    val file = rememberEnsuredFile(
        generation = cacheGeneration,
        identity = id,
        resolve = { mediaRepository?.cachedFile(key) },
        ensure = {
            val repo = mediaRepository ?: return@rememberEnsuredFile null
            when (val result = repo.ensureCustomEmoji(id)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> null
            }
        },
    )
    EmojiStatusMark(file = file, size = 22.dp)
}
