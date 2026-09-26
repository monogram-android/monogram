package org.monogram.feature.profile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.core.common.Outcome
import org.monogram.core.models.Chat
import org.monogram.core.models.ProfileMember
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.ui.components.PeerAvatar
import org.monogram.core.ui.components.isPeerOnline
import org.monogram.core.ui.components.peerStatusLabel
import org.monogram.core.ui.components.rememberPeerStatusNow
import org.monogram.core.ui.rememberCacheGeneration
import org.monogram.core.ui.rememberEnsuredFile
import org.monogram.feature.profile.ProfileComponent
import org.monogram.feature.profile.ProfilePanel
import org.monogram.feature.profile.ProfileStore
import org.monogram.feature.profile.R
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository

internal fun LazyListScope.membersPanel(
    state: ProfileStore.State,
    component: ProfileComponent,
) {
    if (state.members.isEmpty() && state.membersLoading) {
        item(key = "members-loading") { PanelLoading() }
        return
    }
    if (state.members.isEmpty()) {
        item(key = "members-empty") { PanelEmpty(stringResource(R.string.profile_panel_empty)) }
        return
    }
    items(
        items = state.members,
        key = { member -> "member:${member.id.value}" },
    ) { member ->
        MemberRow(
            member = member,
            repository = component.mediaRepository,
            onClick = { component.onOpenPeer(member.id) },
        )
    }
    item(key = "members-more") {
        PanelLoadMore(
            loading = state.membersLoading,
            end = state.membersEnd,
            onLoadMore = { component.onLoadMore(ProfilePanel.Members) },
        )
    }
}

internal fun LazyListScope.commonGroupsPanel(
    state: ProfileStore.State,
    component: ProfileComponent,
) {
    if (state.commonChats.isEmpty() && state.commonChatsLoading) {
        item(key = "common-loading") { PanelLoading() }
        return
    }
    if (state.commonChats.isEmpty()) {
        item(key = "common-empty") { PanelEmpty(stringResource(R.string.profile_panel_empty)) }
        return
    }
    items(
        items = state.commonChats,
        key = { chat -> "common:${chat.id.value}" },
    ) { chat ->
        CommonGroupRow(
            chat = chat,
            repository = component.mediaRepository,
            onClick = { component.onOpenCommonChat(chat.id) },
        )
    }
}

@Composable
private fun MemberRow(
    member: ProfileMember,
    repository: MediaRepository?,
    onClick: () -> Unit,
) {
    val avatarKey = member.avatarCacheKey?.let { peerAvatarCacheKey(member.id, it) }
    val avatar = rememberEnsuredFile(
        generation = rememberCacheGeneration(
            remember(repository, avatarKey) { avatarKey?.let { repository?.cacheGeneration(it) } },
        ),
        identity = member.id.value to avatarKey,
        resolve = { avatarKey?.let { repository?.cachedFile(it) } },
        ensure = {
            val repo = repository ?: return@rememberEnsuredFile null
            val key = avatarKey ?: return@rememberEnsuredFile null
            when (val result = repo.ensureLocalAvatar(member.id, key, MediaPriority.VISIBLE)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> null
            }
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerAvatar(
            title = member.title,
            size = 44.dp,
            imageFile = avatar,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = member.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val presenceNow = rememberPeerStatusNow(member.status, member.statusAt)
            val subtitle = member.username?.let { "@$it" }
                ?: peerStatusLabel(member.status, member.statusAt, presenceNow)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isPeerOnline(member.status, member.statusAt, presenceNow)) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        memberBadge(member)?.let { badge ->
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** Custom rank wins over the generic role, matching Telegram's member list. */
@Composable
private fun memberBadge(member: ProfileMember): String? {
    if (!member.rank.isNullOrBlank()) return member.rank
    return when (profileMemberBadge(member)) {
        ProfileMemberBadge.Owner -> stringResource(R.string.profile_role_owner)
        ProfileMemberBadge.Admin -> stringResource(R.string.profile_role_admin)
        null -> null
    }
}

internal enum class ProfileMemberBadge { Owner, Admin }

/** Generic badge shown when the member has no custom rank. */
internal fun profileMemberBadge(member: ProfileMember): ProfileMemberBadge? = when {
    member.isOwner -> ProfileMemberBadge.Owner
    member.isAdmin -> ProfileMemberBadge.Admin
    else -> null
}

@Composable
private fun CommonGroupRow(
    chat: Chat,
    repository: MediaRepository?,
    onClick: () -> Unit,
) {
    val avatarKey = chat.photoCacheKey?.let { peerAvatarCacheKey(chat.id, it) }
    val avatar = rememberEnsuredFile(
        generation = rememberCacheGeneration(
            remember(repository, avatarKey) { avatarKey?.let { repository?.cacheGeneration(it) } },
        ),
        identity = chat.id.value to avatarKey,
        resolve = { avatarKey?.let { repository?.cachedFile(it) } },
        ensure = {
            val repo = repository ?: return@rememberEnsuredFile null
            val key = avatarKey ?: return@rememberEnsuredFile null
            when (val result = repo.ensureLocalAvatar(chat.id, key, MediaPriority.VISIBLE)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> null
            }
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerAvatar(title = chat.title, size = 44.dp, imageFile = avatar)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chat.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    if (chat.isChannel) R.string.profile_kind_channel else R.string.profile_kind_group,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
