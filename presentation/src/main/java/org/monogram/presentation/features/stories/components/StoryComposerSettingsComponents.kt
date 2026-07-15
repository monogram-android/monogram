package org.monogram.presentation.features.stories.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PeopleAlt
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.stories.StoryPrivacyMode
import org.monogram.domain.models.stories.StoryPrivacySettingsModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsGroup
import org.monogram.presentation.core.ui.SettingsTextField
import org.monogram.presentation.core.ui.SettingsTile
import org.monogram.presentation.features.stories.StoryAudienceFilterMode
import org.monogram.presentation.features.stories.StoryAudiencePickerState
import org.monogram.presentation.features.stories.StoryCapabilityPresentation
import org.monogram.presentation.features.stories.StoryPrivacyUi

@Composable
internal fun StorySettingsCardComponent(
    title: String,
    subtitle: String?,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

@Composable
internal fun StoryCapabilityCardComponent(
    presentation: StoryCapabilityPresentation
) {
    val containerColor = if (presentation.isBlocking) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (presentation.isBlocking) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    val borderColor = if (presentation.isBlocking) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        border = BorderStroke(width = 1.dp, color = borderColor)
    ) {
        Text(
            text = presentation.message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StoryPrivacySectionComponent(
    selected: StoryPrivacyUi,
    onSelect: (StoryPrivacyUi) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 2
    ) {
        StoryPrivacyUi.entries.forEach { option ->
            StoryCompactChoiceButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = when (option) {
                    StoryPrivacyUi.EVERYONE -> stringResource(R.string.story_privacy_everyone)
                    StoryPrivacyUi.CONTACTS -> stringResource(R.string.story_privacy_contacts)
                    StoryPrivacyUi.CLOSE_FRIENDS -> stringResource(R.string.story_privacy_close_friends)
                    StoryPrivacyUi.SELECTED_USERS -> stringResource(R.string.story_privacy_selected_users)
                },
                icon = when (option) {
                    StoryPrivacyUi.EVERYONE -> Icons.Rounded.Public
                    StoryPrivacyUi.CONTACTS -> Icons.Rounded.PeopleAlt
                    StoryPrivacyUi.CLOSE_FRIENDS -> Icons.Rounded.Favorite
                    StoryPrivacyUi.SELECTED_USERS -> Icons.Rounded.Person
                }
            )
        }
    }
}

@Composable
internal fun StoryAudienceFilterRowComponent(
    privacy: StoryPrivacySettingsModel,
    onClick: () -> Unit
) {
    val filterMode = when (privacy.mode) {
        StoryPrivacyMode.SELECTED_USERS -> StoryAudienceFilterMode.SHOW_TO
        StoryPrivacyMode.CLOSE_FRIENDS -> null
        StoryPrivacyMode.EVERYONE,
        StoryPrivacyMode.CONTACTS -> StoryAudienceFilterMode.HIDE_FROM
    } ?: return

    val selectedCount = when (filterMode) {
        StoryAudienceFilterMode.SHOW_TO -> privacy.selectedUserIds.size
        StoryAudienceFilterMode.HIDE_FROM -> privacy.exceptUserIds.size
    }
    val title = stringResource(
        if (filterMode == StoryAudienceFilterMode.SHOW_TO) {
            R.string.story_privacy_show_to
        } else {
            R.string.story_privacy_hide_from
        }
    )
    val subtitle = when (filterMode) {
        StoryAudienceFilterMode.SHOW_TO if selectedCount == 0 -> {
            stringResource(R.string.story_privacy_show_to_empty)
        }

        StoryAudienceFilterMode.HIDE_FROM if selectedCount == 0 -> {
            stringResource(R.string.story_privacy_hide_from_empty)
        }

        StoryAudienceFilterMode.SHOW_TO -> {
            pluralStringResource(
                R.plurals.story_privacy_show_to_count,
                selectedCount,
                selectedCount
            )
        }

        else -> {
            pluralStringResource(
                R.plurals.story_privacy_hide_from_count,
                selectedCount,
                selectedCount
            )
        }
    }

    SettingsTile(
        icon = if (filterMode == StoryAudienceFilterMode.SHOW_TO) {
            Icons.Rounded.PeopleAlt
        } else {
            Icons.Rounded.Shield
        },
        title = title,
        subtitle = subtitle,
        iconColor = MaterialTheme.colorScheme.secondary,
        position = ItemPosition.STANDALONE,
        onClick = onClick
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StoryAudiencePickerContentComponent(
    state: StoryAudiencePickerState,
    onDismiss: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleUserSelection: (Long) -> Unit,
    onClearSelection: () -> Unit
) {
    val users = if (state.searchQuery.isNotBlank()) state.searchResults else state.contacts

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (state.filterMode == StoryAudienceFilterMode.SHOW_TO) {
                            R.string.story_audience_picker_show_to_title
                        } else {
                            R.string.story_audience_picker_hide_from_title
                        }
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(
                        if (state.filterMode == StoryAudienceFilterMode.SHOW_TO) {
                            R.string.story_audience_picker_show_to_subtitle
                        } else {
                            R.string.story_audience_picker_hide_from_subtitle
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel_button))
            }
        }

        SettingsTextField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = stringResource(R.string.story_audience_picker_search),
            icon = Icons.Rounded.Search,
            position = ItemPosition.STANDALONE,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (state.selectedUsers.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.story_audience_selected_count,
                        state.selectedUsers.size,
                        state.selectedUsers.size
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = onClearSelection) {
                    Text(text = stringResource(R.string.story_privacy_clear_selection))
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.selectedUsers.forEach { user ->
                    FilterChip(
                        selected = true,
                        onClick = { onToggleUserSelection(user.id) },
                        label = {
                            Text(text = user.displayTitle())
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 2.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        border = null
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = 420.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator()
                }

                state.isSearching -> {
                    CircularProgressIndicator()
                }

                users.isEmpty() -> {
                    Text(
                        text = stringResource(
                            if (state.searchQuery.isNotBlank()) {
                                R.string.story_audience_picker_no_results
                            } else {
                                R.string.story_audience_picker_empty
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    SettingsGroup {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(users, key = UserModel::id) { user ->
                                StoryAudienceUserRowComponent(
                                    user = user,
                                    isSelected = state.selectedUsers.any { it.id == user.id },
                                    onClick = { onToggleUserSelection(user.id) }
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
private fun StoryAudienceUserRowComponent(
    user: UserModel,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = user.displayTitle(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            user.username
                ?.takeIf { it.isNotBlank() }
                ?.let { username ->
                    Text(
                        text = "@$username",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
        },
        leadingContent = {
            Avatar(
                path = user.avatarPath,
                fallbackPath = user.personalAvatarPath,
                name = user.firstName,
                size = 40.dp
            )
        },
        trailingContent = {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() }
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        )
    )
}

private fun UserModel.displayTitle(): String {
    val fullName = listOfNotNull(firstName, lastName)
        .joinToString(" ")
        .trim()
    return fullName.ifBlank {
        username?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: id.toString()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StoryDurationSectionComponent(
    selectedSeconds: Int,
    onSelect: (Int) -> Unit
) {
    val durations = listOf(
        6 * 60 * 60 to stringResource(R.string.story_duration_6h),
        12 * 60 * 60 to stringResource(R.string.story_duration_12h),
        24 * 60 * 60 to stringResource(R.string.story_duration_24h),
        48 * 60 * 60 to stringResource(R.string.story_duration_48h)
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 2
    ) {
        durations.forEach { (seconds, label) ->
            StoryCompactChoiceButton(
                selected = selectedSeconds == seconds,
                onClick = { onSelect(seconds) },
                label = label,
                icon = Icons.Rounded.Schedule
            )
        }
    }
}

@Composable
private fun StoryCompactChoiceButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val iconColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = containerColor
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}

@Composable
internal fun StorySwitchRowComponent(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (checked) Icons.Rounded.Shield else Icons.Rounded.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
