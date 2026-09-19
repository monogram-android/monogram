package org.monogram.feature.folders.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.PeerId
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.components.AppStatusBanner
import org.monogram.core.ui.components.AppSyncStatus
import org.monogram.core.ui.components.ItemPosition
import org.monogram.core.ui.components.MonogramPlaceholder
import org.monogram.core.ui.components.SearchField
import org.monogram.core.ui.components.SectionHeader
import org.monogram.core.ui.components.SettingsCard
import org.monogram.core.ui.components.SettingsGroupCorner
import org.monogram.core.ui.components.SettingsGroupGap
import org.monogram.core.ui.components.SettingsTile
import org.monogram.core.ui.components.listItemMotion
import org.monogram.feature.folders.FoldersStore
import org.monogram.feature.folders.R
import androidx.compose.foundation.shape.RoundedCornerShape

private val FolderRowIconSize = 40.dp
private val FolderRowHeight = 64.dp

@Composable
fun FoldersListScreen(
    state: FoldersStore.State,
    innerPadding: PaddingValues,
    onMove: (Int, Int) -> Unit,
    onOpen: (Folder) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appearance by AppearanceSettings.state.collectAsState()
    Column(modifier.fillMaxSize()) {
        Spacer(Modifier.height(innerPadding.calculateTopPadding()))
        AppStatusBanner(sync = AppSyncStatus.Hidden, error = state.error, onRetry = onRetry)
        val rows = state.reorderableFolders
        Box(Modifier.weight(1f)) {
            when {
                !state.loaded -> FoldersSkeleton()
                state.folders.isEmpty() && state.error == null -> FoldersEmptyState()
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .wrapContentWidth(Alignment.CenterHorizontally)
                        .widthIn(max = 720.dp),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 8.dp,
                        end = 16.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues()
                            .calculateBottomPadding() + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(SettingsGroupGap),
                ) {
                    item(key = "folders-heading") {
                        SectionHeader(
                            text = stringResource(R.string.folders_list_heading),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item(key = "folders-show-all-toggle") {
                        SettingsCard(position = ItemPosition.STANDALONE) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = FolderRowHeight)
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(FolderRowIconSize)
                                        .background(
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                            shape = CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.folders_show_all_chats),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = stringResource(R.string.folders_show_all_chats_sub),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = appearance.showAllChats,
                                    onCheckedChange = { AppearanceSettings.setShowAllChats(it) },
                                )
                            }
                        }
                    }
                    rows.forEachIndexed { index, folder ->
                        if (folder.id == 0 && !appearance.showAllChats) return@forEachIndexed
                        item(key = "folder-${folder.id}") {
                            FolderRow(
                                folder = folder,
                                count = if (folder.id == 0) state.allChatsCount else state.count(folder),
                                canReorder = rows.size > 1,
                                onMove = { delta -> onMove(index, index + delta) },
                                onOpen = if (folder.id > 1) ({ onOpen(folder) }) else null,
                                modifier = listItemMotion(animateAppearance = false),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    folder: Folder,
    count: Int,
    canReorder: Boolean,
    onMove: (Int) -> Unit,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val dragAccumulator = remember { mutableFloatStateOf(0f) }
    val move = rememberUpdatedState(onMove)
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { FolderRowHeight.toPx() / 2f }
    val title = if (folder.id == 0) {
        stringResource(R.string.folders_all_chats)
    } else {
        folder.label.ifBlank { stringResource(R.string.folders_add) }
    }
    SettingsCard(modifier = modifier, position = ItemPosition.STANDALONE) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FolderRowHeight)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(FolderRowIconSize)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.folders_chat_count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canReorder) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .pointerInput(folder.id, threshold) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                dragAccumulator.value = 0f
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) break
                                    val dy = change.positionChange().y
                                    change.consume()
                                    dragAccumulator.value += dy
                                    if (dragAccumulator.value <= -threshold) {
                                        dragAccumulator.value = 0f
                                        move.value(-1)
                                    } else if (dragAccumulator.value >= threshold) {
                                        dragAccumulator.value = 0f
                                        move.value(1)
                                    }
                                }
                                dragAccumulator.value = 0f
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DragHandle,
                        contentDescription = stringResource(R.string.folders_reorder),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FoldersSkeleton() {
    val description = stringResource(R.string.folders_loading)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = description },
    ) {
        SectionHeader(
            text = stringResource(R.string.folders_list_heading),
            modifier = Modifier.fillMaxWidth(),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(SettingsGroupCorner),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                repeat(4) { index ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .padding(start = 16.dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(FolderRowHeight)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MonogramPlaceholder(
                            modifier = Modifier.size(FolderRowIconSize),
                            shape = CircleShape,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            MonogramPlaceholder(
                                modifier = Modifier.fillMaxWidth(0.44f).height(14.dp),
                                shape = RoundedCornerShape(6.dp),
                            )
                            Spacer(Modifier.height(6.dp))
                            MonogramPlaceholder(
                                modifier = Modifier.fillMaxWidth(0.22f).height(11.dp),
                                shape = RoundedCornerShape(6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoldersEmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.folders_empty),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
fun FoldersEditorScreen(
    folder: Folder,
    chats: List<Chat>,
    innerPadding: PaddingValues,
    onChange: (Folder) -> Unit,
    onDelete: (Folder) -> Unit,
    modifier: Modifier = Modifier,
) {
    var includeQuery by rememberSaveable { mutableStateOf("") }
    var excludeQuery by rememberSaveable { mutableStateOf("") }
    val includeHeading = stringResource(R.string.folders_include_chats)
    val includeEmpty = stringResource(R.string.folders_include_empty)
    val excludeHeading = stringResource(R.string.folders_exclude_chats)
    val excludeEmpty = stringResource(R.string.folders_exclude_empty)
    val includeTypes = listOf(
        FolderTypeChip(
            label = stringResource(R.string.folders_include_contacts),
            icon = Icons.Outlined.Person,
            selected = folder.includeContacts,
            onToggle = { onChange(folder.copy(includeContacts = !folder.includeContacts)) },
        ),
        FolderTypeChip(
            label = stringResource(R.string.folders_include_non_contacts),
            icon = Icons.Outlined.PersonAdd,
            selected = folder.includeNonContacts,
            onToggle = { onChange(folder.copy(includeNonContacts = !folder.includeNonContacts)) },
        ),
        FolderTypeChip(
            label = stringResource(R.string.folders_include_groups),
            icon = Icons.Outlined.Groups,
            selected = folder.includeGroups,
            onToggle = { onChange(folder.copy(includeGroups = !folder.includeGroups)) },
        ),
        FolderTypeChip(
            label = stringResource(R.string.folders_include_channels),
            icon = Icons.Outlined.Campaign,
            selected = folder.includeChannels,
            onToggle = { onChange(folder.copy(includeChannels = !folder.includeChannels)) },
        ),
        FolderTypeChip(
            label = stringResource(R.string.folders_include_bots),
            icon = Icons.Outlined.SmartToy,
            selected = folder.includeBots,
            onToggle = { onChange(folder.copy(includeBots = !folder.includeBots)) },
        ),
    )
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 720.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = innerPadding.calculateTopPadding() + 8.dp,
            end = 16.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item(key = "name-heading") { SectionHeader(stringResource(R.string.folders_name)) }
        item(key = "name-fields") {
            SettingsCard(position = ItemPosition.STANDALONE) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = folder.title,
                        onValueChange = { onChange(folder.copy(title = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.folders_name)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                    )
                    OutlinedTextField(
                        value = folder.emoticon,
                        onValueChange = { onChange(folder.copy(emoticon = it.take(8))) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.folders_emoji)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                    )
                }
            }
        }
        item(key = "types-heading") { SectionHeader(stringResource(R.string.folders_include_types)) }
        item(key = "types") {
            SettingsCard(position = ItemPosition.STANDALONE) {
                FlowRow(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    includeTypes.forEach { chip ->
                        FilterChip(
                            selected = chip.selected,
                            onClick = chip.onToggle,
                            label = { Text(chip.label) },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (chip.selected) Icons.Outlined.Check else chip.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
            }
        }
        item(key = "exclude-heading") { SectionHeader(stringResource(R.string.folders_exclude)) }
        item {
            FolderSwitchRow(
                icon = Icons.Outlined.NotificationsOff,
                title = stringResource(R.string.folders_exclude_muted),
                checked = folder.excludeMuted,
                position = ItemPosition.TOP,
                onToggle = { onChange(folder.copy(excludeMuted = it)) },
            )
        }
        item {
            FolderSwitchRow(
                icon = Icons.Outlined.DoneAll,
                title = stringResource(R.string.folders_exclude_read),
                checked = folder.excludeRead,
                position = ItemPosition.MIDDLE,
                onToggle = { onChange(folder.copy(excludeRead = it)) },
            )
        }
        item {
            FolderSwitchRow(
                icon = Icons.Outlined.Inventory2,
                title = stringResource(R.string.folders_exclude_archived),
                checked = folder.excludeArchived,
                position = ItemPosition.BOTTOM,
                onToggle = { onChange(folder.copy(excludeArchived = it)) },
            )
        }
        folderChatPicker(
            heading = includeHeading,
            emptyHint = includeEmpty,
            query = includeQuery,
            onQueryChange = { includeQuery = it },
            selectedIds = folder.chatIds,
            chats = chats,
            keyPrefix = "inc",
            onToggle = { chat ->
                val included = folder.chatIds.any { it == chat.id }
                onChange(
                    folder.copy(
                        chatIds = if (included) {
                            folder.chatIds.filterNot { it == chat.id }
                        } else {
                            folder.chatIds + chat.id
                        },
                    ),
                )
            },
        )
        folderChatPicker(
            heading = excludeHeading,
            emptyHint = excludeEmpty,
            query = excludeQuery,
            onQueryChange = { excludeQuery = it },
            selectedIds = folder.excludeChatIds,
            chats = chats,
            keyPrefix = "exc",
            onToggle = { chat ->
                val excluded = folder.excludeChatIds.any { it == chat.id }
                onChange(
                    folder.copy(
                        excludeChatIds = if (excluded) {
                            folder.excludeChatIds.filterNot { it == chat.id }
                        } else {
                            folder.excludeChatIds + chat.id
                        },
                    ),
                )
            },
        )
        if (folder.id > 1) {
            item(key = "delete") {
                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = { onDelete(folder) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.folders_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private data class FolderTypeChip(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val onToggle: () -> Unit,
)

@Composable
private fun FolderSwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    position: ItemPosition,
    onToggle: (Boolean) -> Unit,
) {
    SettingsTile(
        icon = icon,
        title = title,
        iconColor = MaterialTheme.colorScheme.secondary,
        position = position,
        onClick = { onToggle(!checked) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onToggle)
        },
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.folderChatPicker(
    heading: String,
    emptyHint: String,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedIds: List<PeerId>,
    chats: List<Chat>,
    keyPrefix: String,
    onToggle: (Chat) -> Unit,
) {
    val selected = selectedIds.toSet()
    val rows = folderChatRows(chats, selected, query)
    item(key = "$keyPrefix-heading") { SectionHeader(heading) }
    item(key = "$keyPrefix-search") {
        SearchField(
            query = query,
            onQueryChanged = onQueryChange,
            placeholder = stringResource(R.string.folders_search_chats),
            closeLabel = stringResource(R.string.folders_clear_search),
        )
    }
    if (rows.isEmpty()) {
        item(key = "$keyPrefix-empty") {
            Text(
                text = emptyHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    } else {
        itemsIndexed(rows, key = { _, chat -> "$keyPrefix-${chat.id.value}" }) { index, chat ->
            val on = chat.id in selected
            SettingsTile(
                icon = Icons.Outlined.Folder,
                title = chat.title,
                iconColor = MaterialTheme.colorScheme.primary,
                position = itemPos(index, rows.size),
                selected = on,
                onClick = { onToggle(chat) },
            )
        }
    }
}

private fun folderChatRows(
    chats: List<Chat>,
    selected: Set<PeerId>,
    query: String,
): List<Chat> {
    val chosen = chats.filter { it.id in selected }
    val needle = query.trim()
    if (needle.isEmpty()) return chosen
    val matches = chats.filter { it.title.contains(needle, ignoreCase = true) }
    return (chosen.filter { it.title.contains(needle, ignoreCase = true) } +
        matches.filter { it.id !in selected }).distinctBy { it.id.value }.take(24)
}

private fun itemPos(index: Int, size: Int): ItemPosition = when {
    size <= 1 -> ItemPosition.STANDALONE
    index == 0 -> ItemPosition.TOP
    index == size - 1 -> ItemPosition.BOTTOM
    else -> ItemPosition.MIDDLE
}
