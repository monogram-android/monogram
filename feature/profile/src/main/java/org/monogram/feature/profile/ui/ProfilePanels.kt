package org.monogram.feature.profile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.models.Message
import org.monogram.core.models.ProfileTab
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.loading.MonogramLoadingListSize
import org.monogram.feature.profile.ProfileComponent
import org.monogram.feature.profile.ProfilePanel
import org.monogram.feature.profile.ProfileStore
import org.monogram.feature.profile.R

/** Hide-empty tab strip; every visible tab shows its known result count. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ProfilePanelStrip(state: ProfileStore.State, onSelect: (ProfilePanel) -> Unit) {
    if (state.panels.isEmpty()) return
    val edge = MaterialTheme.colorScheme.surface
    Box(modifier = Modifier.fillMaxWidth()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 4.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
        ) {
            items(
                items = state.panels,
                key = { panel -> panelKey(panel) },
            ) { panel ->
                val selected = panel == state.selectedPanel
                FilterChip(
                    selected = selected,
                    onClick = { onSelect(panel) },
                    label = { Text(profilePanelLabel(panel, state)) },
                    leadingIcon = panelIcon(panel)?.let { icon ->
                        {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    },
                    shapes = FilterChipDefaults.shapes(),
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(24.dp)
                .fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(edge.copy(alpha = 0f), edge))),
        )
    }
}

@Composable
private fun profilePanelLabel(panel: ProfilePanel, state: ProfileStore.State): String = when (panel) {
    ProfilePanel.Members -> {
        val count = state.membersTotal.takeIf { it > 0 }
        if (count != null) {
            "${stringResource(R.string.profile_tab_members)} $count"
        } else {
            stringResource(R.string.profile_tab_members)
        }
    }
    ProfilePanel.CommonGroups -> {
        val count = state.commonChatsCount.takeIf { it > 0 }
        if (count != null) {
            "${stringResource(R.string.profile_tab_common_groups)} $count"
        } else {
            stringResource(R.string.profile_tab_common_groups)
        }
    }
    is ProfilePanel.SharedMedia -> {
        val count = state.tabCounts.known(panel.tab)
        val label = profileTabLabel(panel.tab)
        if (count != null && count > 0) "$label $count" else label
    }
}

@Composable
private fun profileTabLabel(tab: ProfileTab): String = stringResource(
    when (tab) {
        ProfileTab.MEDIA -> R.string.profile_tab_media
        ProfileTab.FILES -> R.string.profile_tab_files
        ProfileTab.LINKS -> R.string.profile_tab_links
        ProfileTab.GIFS -> R.string.profile_tab_gifs
        ProfileTab.VOICE -> R.string.profile_tab_voice
        ProfileTab.MUSIC -> R.string.profile_tab_music
    },
)

private fun panelKey(panel: ProfilePanel): String = when (panel) {
    ProfilePanel.Members -> "members"
    ProfilePanel.CommonGroups -> "common"
    is ProfilePanel.SharedMedia -> panel.tab.wire
}

internal fun panelIcon(panel: ProfilePanel): ImageVector? = when (panel) {
    ProfilePanel.Members -> Icons.Outlined.Groups
    ProfilePanel.CommonGroups -> Icons.AutoMirrored.Outlined.Chat
    is ProfilePanel.SharedMedia -> when (panel.tab) {
        ProfileTab.MEDIA -> Icons.Outlined.Photo
        ProfileTab.FILES -> Icons.AutoMirrored.Outlined.InsertDriveFile
        ProfileTab.LINKS -> Icons.Outlined.Link
        ProfileTab.GIFS -> Icons.Outlined.Gif
        ProfileTab.VOICE -> Icons.Outlined.Mic
        ProfileTab.MUSIC -> Icons.Outlined.MusicNote
    }
}

internal fun LazyListScope.profilePanelContent(
    state: ProfileStore.State,
    component: ProfileComponent,
    onOpenMedia: (Message) -> Unit,
) {
    when (val panel = state.selectedPanel) {
        ProfilePanel.Members -> membersPanel(state, component)
        ProfilePanel.CommonGroups -> commonGroupsPanel(state, component)
        is ProfilePanel.SharedMedia -> sharedMediaPanel(panel.tab, state, component, onOpenMedia)
        null -> Unit
    }
}

@Composable
internal fun PanelLoading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        MonogramLoading(size = MonogramLoadingListSize)
    }
}

@Composable
internal fun PanelEmpty(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun PanelError(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.profile_panel_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.profile_panel_retry))
        }
    }
}

@Composable
internal fun PanelLoadMore(loading: Boolean, end: Boolean, onLoadMore: () -> Unit) {
    if (end) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            MonogramLoading(size = MonogramLoadingListSize)
        } else {
            FilledTonalButton(onClick = onLoadMore) {
                Text(stringResource(R.string.profile_show_more))
            }
        }
    }
}
