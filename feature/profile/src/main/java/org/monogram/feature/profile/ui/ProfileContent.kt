package org.monogram.feature.profile.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.core.common.Outcome
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.ui.components.AppStatusBanner
import org.monogram.core.ui.components.AppSyncStatus
import org.monogram.core.ui.components.MediaPreviewViewer
import org.monogram.core.ui.components.ProfileSkeleton
import org.monogram.core.ui.loading.MonogramRefreshBox
import org.monogram.core.ui.rememberCacheGeneration
import org.monogram.feature.profile.ProfileComponent
import org.monogram.feature.profile.R
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository

private val ProfileAtTopTolerance = 12
private const val ProfileAvatarCollapsedScale = 0.82f
private const val ProfilePullRefreshGraceMillis = 700L

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProfileContent(component: ProfileComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val profile = state.profile
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var showAvatar by rememberSaveable { mutableStateOf(false) }
    var selectedMedia by remember { mutableStateOf<Message?>(null) }
    var avatarAttempt by remember(profile?.id?.value) { mutableIntStateOf(0) }
    val avatarCacheKey = profile?.avatarCacheKey?.let { cacheKey ->
        profile.id.let { peerAvatarCacheKey(it, cacheKey) }
    }
    val avatarCacheGeneration = rememberCacheGeneration(
        remember(component.mediaRepository, avatarCacheKey) {
            avatarCacheKey?.let { component.mediaRepository?.cacheGeneration(it) }
        },
    )
    val avatar = rememberProfileAvatar(
        repository = component.mediaRepository,
        peer = profile?.id,
        cacheKey = profile?.avatarCacheKey,
        cacheGeneration = avatarCacheGeneration,
        attempt = avatarAttempt,
    )
    val avatarFile = (avatar as? ProfileAvatar.Ready)?.file

    var pullRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(pullRefreshing) {
        if (!pullRefreshing) return@LaunchedEffect
        val started = withTimeoutOrNull(ProfilePullRefreshGraceMillis) {
            snapshotFlow { state.loading }.first { it }
        } != null
        if (started) snapshotFlow { state.loading }.first { !it }
        pullRefreshing = false
    }

    val listState = rememberLazyListState()
    val atTop by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= ProfileAtTopTolerance
        }
    }
    val avatarScale by animateFloatAsState(
        targetValue = if (atTop) 1f else ProfileAvatarCollapsedScale,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "profileAvatarScale",
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ProfileTopBar(
                atTop = atTop,
                title = profile?.title,
                onBack = component::onBack,
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = inner.calculateTopPadding()),
        ) {
            AppStatusBanner(
                sync = AppSyncStatus.Hidden,
                error = state.error,
                onRetry = component::onRefresh,
            )
            MonogramRefreshBox(
                isRefreshing = pullRefreshing,
                onRefresh = {
                    pullRefreshing = true
                    component.onRefresh()
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    profile != null -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 24.dp,
                        ),
                    ) {
                        item(key = "header") {
                            ProfileHeader(
                                profile = profile,
                                avatarFile = avatarFile,
                                avatarState = avatar,
                                avatarGeneration = avatarAttempt,
                                avatarScale = avatarScale,
                                mediaRepository = component.mediaRepository,
                                onOpenAvatar = { showAvatar = true },
                                onRetryAvatar = { avatarAttempt++ },
                                onMessage = component::onMessage,
                                onCopyUsername = profile.username?.takeIf { it.isNotBlank() }?.let { handle ->
                                    { scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("text", "@$handle"))) } }
                                },
                                snackbar = snackbar,
                                clipboard = clipboard,
                                scope = scope,
                            )
                        }
                        item(key = "panels") {
                            ProfilePanelStrip(
                                state = state,
                                onSelect = component::onSelectPanel,
                            )
                        }
                        profilePanelContent(
                            state = state,
                            component = component,
                            onOpenMedia = { selectedMedia = it },
                        )
                    }
                    state.loading -> ProfileSkeleton()
                    else -> Unit
                }
            }
        }
    }
    if (showAvatar) {
        MediaPreviewViewer(
            file = avatarFile,
            stateKey = "avatar:${profile?.id?.value}",
            fallbackTitle = profile?.title.orEmpty(),
            contentDescription = stringResource(R.string.profile_open_photo),
            loop = true,
            onDismiss = { showAvatar = false },
        )
    }
    selectedMedia?.let { message ->
        ProfileMediaViewer(
            message = message,
            repository = component.mediaRepository,
            onDismiss = { selectedMedia = null },
        )
    }
}

internal sealed interface ProfileAvatar {
    data object Idle : ProfileAvatar
    data object Loading : ProfileAvatar
    data class Ready(val file: java.io.File) : ProfileAvatar
    data object Failed : ProfileAvatar
}

@Composable
private fun rememberProfileAvatar(
    repository: MediaRepository?,
    peer: PeerId?,
    cacheKey: String?,
    cacheGeneration: Long,
    attempt: Int,
): ProfileAvatar {
    if (peer == null || cacheKey == null) return ProfileAvatar.Idle
    var state by remember(peer, cacheKey, attempt) { mutableStateOf<ProfileAvatar>(ProfileAvatar.Loading) }
    LaunchedEffect(peer, cacheKey, attempt, cacheGeneration, repository) {
        val repo = repository ?: return@LaunchedEffect
        val key = peerAvatarCacheKey(peer, cacheKey)
        repeat(3) { attempt ->
            repo.cachedFile(key)?.let {
                state = ProfileAvatar.Ready(it)
                return@LaunchedEffect
            }
            repo.cachedAvatar(peer)?.let {
                state = ProfileAvatar.Ready(it)
                return@LaunchedEffect
            }
            state = ProfileAvatar.Loading
            when (val result = repo.ensureLocalAvatar(peer, key, MediaPriority.USER)) {
                is Outcome.Ok -> {
                    state = ProfileAvatar.Ready(result.value)
                    return@LaunchedEffect
                }
                is Outcome.Err -> {
                    if (attempt == 2) state = ProfileAvatar.Failed
                    else delay(400L * (attempt + 1))
                }
            }
        }
    }
    return state
}
