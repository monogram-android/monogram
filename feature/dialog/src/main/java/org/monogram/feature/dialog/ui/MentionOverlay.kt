package org.monogram.feature.dialog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.core.common.Outcome
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.ui.ExpressiveDefaults
import org.monogram.core.ui.components.PeerAvatar
import org.monogram.core.ui.components.listItemMotion
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.loading.MonogramLoadingListSize
import org.monogram.core.ui.rememberCacheGeneration
import org.monogram.core.ui.rememberEnsuredFile
import org.monogram.feature.dialog.MentionCandidate
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MentionOverlayHost(
    visible: Boolean,
    handle: String,
    candidates: List<MentionCandidate>,
    loading: Boolean,
    mediaRepository: MediaRepository?,
    onSelect: (MentionCandidate) -> Unit,
    hasMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxWidth(),
        enter = fadeIn(motion.defaultEffectsSpec()) + expandVertically(
            animationSpec = motion.defaultSpatialSpec(),
            expandFrom = Alignment.Bottom,
        ),
        exit = fadeOut(motion.fastEffectsSpec()) + shrinkVertically(
            animationSpec = motion.fastSpatialSpec(),
            shrinkTowards = Alignment.Bottom,
        ),
    ) {
        MentionOverlay(
            handle = handle,
            candidates = candidates,
            loading = loading,
            mediaRepository = mediaRepository,
            onSelect = onSelect,
            hasMore = hasMore,
            onLoadMore = onLoadMore,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MentionOverlay(
    handle: String,
    candidates: List<MentionCandidate>,
    loading: Boolean,
    mediaRepository: MediaRepository?,
    onSelect: (MentionCandidate) -> Unit,
    hasMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp, max = composerPanelHeight()),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
        ) {
            Text(
                text = if (handle.isBlank()) {
                    stringResource(R.string.dialog_mentions)
                } else {
                    "@$handle"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            when {
                loading && candidates.isEmpty() -> MonogramLoading(
                    modifier = Modifier
                        .padding(20.dp)
                        .align(Alignment.CenterHorizontally),
                    size = MonogramLoadingListSize,
                )
                candidates.isEmpty() -> Text(
                    text = stringResource(R.string.dialog_mention_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    val listState = rememberLazyListState()
                    val shouldLoadMore by remember(hasMore, loading, candidates.size) {
                        derivedStateOf {
                            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                            hasMore && !loading && candidates.isNotEmpty() &&
                                last >= candidates.size - 6
                        }
                    }
                    LaunchedEffect(shouldLoadMore) {
                        if (shouldLoadMore) onLoadMore()
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = composerPanelHeight() - 48.dp),
                    ) {
                        items(candidates, key = { it.peerId.value }) { candidate ->
                            MentionRow(
                                candidate = candidate,
                                mediaRepository = mediaRepository,
                                onSelect = onSelect,
                                modifier = listItemMotion(),
                            )
                        }
                        if (loading) {
                            item(key = "mention-loading") {
                                MonogramLoading(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    size = MonogramLoadingListSize,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MentionRow(
    candidate: MentionCandidate,
    mediaRepository: MediaRepository?,
    onSelect: (MentionCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState().value
    val corner = animateDpAsState(
        targetValue = if (pressed) ExpressiveDefaults.PressRadius else 24.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "mentionPress",
    )
    val label = stringResource(R.string.dialog_mention_user, candidate.title)
    val generation = rememberCacheGeneration(mediaRepository?.cacheGeneration)
    val key = peerAvatarCacheKey(candidate.peerId, candidate.avatarCacheKey)
    val avatarFile = rememberEnsuredFile(
        generation = generation,
        identity = candidate.peerId.value to key,
        resolve = {
            mediaRepository?.cachedFile(key) ?: mediaRepository?.cachedAvatar(candidate.peerId)
        },
        ensure = {
            val repo = mediaRepository ?: return@rememberEnsuredFile null
            when (
                val result = repo.ensureLocalAvatar(
                    candidate.peerId,
                    key,
                    MediaPriority.VISIBLE,
                )
            ) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> repo.cachedAvatar(candidate.peerId)
            }
        },
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(corner.value))
            .background(
                if (pressed) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                onClickLabel = label,
                onClick = { onSelect(candidate) },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(44.dp)) {
            PeerAvatar(
                title = candidate.title,
                size = 44.dp,
                imageFile = avatarFile,
            )
            if (candidate.isBot) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = candidate.username?.let { "@$it" }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
