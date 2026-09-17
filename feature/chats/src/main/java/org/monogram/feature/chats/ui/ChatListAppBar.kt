package org.monogram.feature.chats.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import org.monogram.core.ui.menu.AppMenuGroup
import org.monogram.core.ui.menu.AppMenuItem
import org.monogram.core.ui.menu.AppMenuPopup
import org.monogram.core.ui.menu.AppMenuSurface
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import org.monogram.core.ui.components.PeerAvatar
import org.monogram.network.http.MediaRepository
import org.monogram.core.ui.components.SearchField

/**
 * One single-row header. At the top of the list it shows the product name (with the account's
 * status emoji inline, when there is one); once the list scrolls it becomes the current folder and
 * its unread line. Search and the account avatar stay in the same trailing slot, on the same row,
 * in both states.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatsTopBar(
    atTop: Boolean,
    brandTitle: String,
    brandEmojiDocumentId: Long?,
    folderTitle: String,
    folderSubtitle: String?,
    archive: Boolean,
    selfTitle: String,
    selfAvatar: File?,
    selfOnline: Boolean,
    mediaRepository: MediaRepository?,
    searchOpen: Boolean,
    onBack: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onMarkAllRead: () -> Unit,
    searchLabel: String,
    searchCloseLabel: String,
    backLabel: String,
    profileLabel: String,
    settingsLabel: String,
    markAllReadLabel: String,
    markAllReadEnabled: Boolean,
) {
    // Pinned single-row bar: it never moves, only its surface tone and content change.
    val containerColor by animateColorAsState(
        targetValue = if (atTop) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "appBarContainer",
    )
    val subtitleText = if (atTop) null else folderSubtitle
    val subtitleSlot: @Composable () -> Unit = {
        AppBarSubtitle(text = subtitleText)
    }

    TopAppBar(
        title = {
            AppBarTitle(
                brandTitle = brandTitle,
                brandEmojiDocumentId = brandEmojiDocumentId,
                folderTitle = folderTitle,
                atTop = atTop,
                mediaRepository = mediaRepository,
            )
        },
        subtitle = subtitleSlot,
        navigationIcon = {
            if (archive) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = backLabel)
                }
            }
        },
        actions = {
            IconButton(onClick = onToggleSearch) {
                Icon(
                    imageVector = if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                    contentDescription = if (searchOpen) searchCloseLabel else searchLabel,
                )
            }
            AccountAvatar(
                title = selfTitle,
                imageFile = selfAvatar,
                online = selfOnline,
                settingsLabel = settingsLabel,
                profileLabel = profileLabel,
                markAllReadLabel = markAllReadLabel,
                markAllReadEnabled = markAllReadEnabled,
                onOpenSettings = onOpenSettings,
                onOpenProfile = onOpenProfile,
                onMarkAllRead = onMarkAllRead,
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            scrolledContainerColor = containerColor,
        ),
    )
}

/**
 * Fade with a slight scale so a title swap never pops; specs come from the theme motion scheme.
 */
private fun barSwap(
    enterFade: FiniteAnimationSpec<Float>,
    enterScale: FiniteAnimationSpec<Float>,
    exitFade: FiniteAnimationSpec<Float>,
    exitScale: FiniteAnimationSpec<Float>,
): ContentTransform =
    (fadeIn(enterFade) + scaleIn(initialScale = 0.98f, animationSpec = enterScale))
        .togetherWith(
            fadeOut(exitFade) + scaleOut(targetScale = 0.98f, animationSpec = exitScale),
        )

/**
 * Brand title with the account's status emoji inline and small, immediately after the text; the
 * plain folder title once the list scrolls.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppBarTitle(
    brandTitle: String,
    brandEmojiDocumentId: Long?,
    folderTitle: String,
    atTop: Boolean,
    mediaRepository: MediaRepository?,
) {
    val enterFade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val enterScale = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val exitFade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val exitScale = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    AnimatedContent(
        targetState = atTop,
        transitionSpec = { barSwap(enterFade, enterScale, exitFade, exitScale) },
        label = "appBarTitle",
    ) { brand ->
        if (brand) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = brandTitle,
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (brandEmojiDocumentId != null) {
                    ChatEmojiStatus(
                        documentId = brandEmojiDocumentId,
                        mediaRepository = mediaRepository,
                    )
                }
            }
        } else {
            Text(
                text = folderTitle,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Unread line under the folder title; nothing at all while the list is at the top. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppBarSubtitle(text: String?) {
    val enterFade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val enterScale = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val exitFade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val exitScale = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    AnimatedContent(
        targetState = text,
        transitionSpec = { barSwap(enterFade, enterScale, exitFade, exitScale) },
        label = "appBarSubtitle",
    ) { subtitle ->
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

@Composable
private fun AccountAvatar(
    title: String,
    imageFile: File?,
    online: Boolean,
    settingsLabel: String,
    profileLabel: String,
    markAllReadLabel: String,
    markAllReadEnabled: Boolean,
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
    onMarkAllRead: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = onOpenSettings,
                    onLongClick = { menuOpen = true },
                    onLongClickLabel = profileLabel,
                )
                .semantics { contentDescription = settingsLabel },
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.size(32.dp)) {
                PeerAvatar(title = title, size = 32.dp, imageFile = imageFile)
                if (online) {
                    OnlineDot(modifier = Modifier.align(Alignment.BottomEnd))
                }
            }
        }
        AppMenuPopup(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            alignToAnchorEnd = true,
        ) {
            AppMenuSurface {
                AppMenuGroup {
                    AppMenuItem(
                        text = profileLabel,
                        icon = Icons.Outlined.Person,
                        onClick = {
                            menuOpen = false
                            onOpenProfile()
                        },
                    )
                    AppMenuItem(
                        text = markAllReadLabel,
                        icon = Icons.Outlined.MarkChatRead,
                        enabled = markAllReadEnabled,
                        onClick = {
                            menuOpen = false
                            onMarkAllRead()
                        },
                    )
                }
            }
        }
    }
}
