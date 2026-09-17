package org.monogram.feature.dialog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.core.common.Outcome
import org.monogram.core.models.Chat
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.ui.rememberEnsuredFile
import org.monogram.core.models.PeerId
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.core.ui.components.PeerAvatar
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaRepository
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import org.monogram.core.ui.loading.MonogramBusyField
import org.monogram.core.ui.loading.MonogramLoadingHeroSize
import org.monogram.core.ui.loading.MonogramLoadingOverlay

@Composable
fun ForwardPickerSheet(
    targets: List<Chat>,
    query: String,
    forwarding: Boolean,
    mediaRepository: MediaRepository?,
    onQuery: (String) -> Unit,
    onSelect: (PeerId) -> Unit,
    onDismiss: () -> Unit,
) {
    val shown = remember(targets, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            targets
        } else {
            targets.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.lastMessagePreview.orEmpty().contains(q, ignoreCase = true)
            }
        }
    }
    AppModalSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        MonogramLoadingOverlay(
            visible = forwarding,
            modifier = Modifier.fillMaxWidth(),
            size = MonogramLoadingHeroSize,
            status = stringResource(R.string.dialog_forwarding),
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.dialog_forward_to),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            MonogramBusyField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                busy = forwarding,
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                label = stringResource(R.string.dialog_forward_search),
            )
            if (shown.isEmpty()) {
                Text(
                    text = stringResource(R.string.dialog_forward_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(shown, key = { it.id.value }) { chat ->
                        ForwardChatRow(
                            chat = chat,
                            mediaRepository = mediaRepository,
                            enabled = !forwarding,
                            onClick = { onSelect(chat.id) },
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun ForwardChatRow(
    chat: Chat,
    mediaRepository: MediaRepository?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val cacheGeneration = mediaRepository?.cacheGeneration?.collectAsState()?.value ?: 0L
    val key = peerAvatarCacheKey(chat.id, chat.photoCacheKey)
    val avatarFile = rememberEnsuredFile(
        generation = cacheGeneration,
        identity = chat.id.value to key,
        resolve = {
            mediaRepository?.cachedFile(key) ?: mediaRepository?.cachedAvatar(chat.id)
        },
        ensure = {
            val repo = mediaRepository ?: return@rememberEnsuredFile null
            when (val result = repo.ensureLocalAvatar(chat.id, key)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> null
            }
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PeerAvatar(title = chat.title, size = 44.dp, imageFile = avatarFile)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chat.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            chat.lastMessagePreview?.takeIf { it.isNotBlank() }?.let { preview ->
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
