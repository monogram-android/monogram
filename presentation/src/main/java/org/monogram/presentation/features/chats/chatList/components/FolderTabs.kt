package org.monogram.presentation.features.chats.chatList.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.FolderModel
import org.monogram.presentation.R
import org.monogram.presentation.settings.folders.getFolderIcon

@Composable
fun FolderTabs(
    folders: List<FolderModel>,
    pagerState: PagerState,
    onTabClick: (Int) -> Unit,
    onEditClick: () -> Unit,
    onEditFolderClick: (FolderModel) -> Unit,
    onDeleteFolderClick: (FolderModel) -> Unit,
    onReorderFoldersClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lazyListState = rememberLazyListState()
    var contextMenuExpanded by remember { mutableStateOf(false) }
    var contextMenuFolderIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(pagerState.currentPage) {
        lazyListState.animateScrollToItem(pagerState.currentPage)
    }

    LazyRow(
        state = lazyListState,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(folders.size) { index ->
            val folder = folders[index]
            val isSelected = pagerState.currentPage == index

            val backgroundColor by animateColorAsState(
                targetValue = if (isSelected)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                label = "TabBg"
            )
            val contentColor by animateColorAsState(
                targetValue = if (isSelected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                label = "TabContent"
            )

            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(backgroundColor)
                        .combinedClickable(
                            onClick = { onTabClick(index) },
                            onLongClick = {
                                if (folder.id > 0) {
                                    contextMenuFolderIndex = index
                                    contextMenuExpanded = true
                                }
                            }
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val icon = getFolderIcon(folder.iconName)
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = contentColor
                        )
                    }

                    Text(
                        text = if (folder.id == -1) {
                            stringResource(R.string.folders_system_all_chats)
                        } else {
                            folder.title
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = contentColor
                    )

                }

                DropdownMenu(
                    expanded = contextMenuExpanded && contextMenuFolderIndex == index,
                    onDismissRequest = { contextMenuExpanded = false },
                    offset = DpOffset(x = 0.dp, y = 8.dp),
                    shape = RoundedCornerShape(22.dp),
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Surface(
                        modifier = Modifier.widthIn(min = 220.dp, max = 260.dp),
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            DropdownMenuItem(
                                text = { Text(text = stringResource(R.string.folder_tab_edit)) },
                                onClick = {
                                    onEditFolderClick(folder)
                                    contextMenuExpanded = false
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Edit,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(text = stringResource(R.string.folder_tab_reorder)) },
                                onClick = {
                                    onReorderFoldersClick()
                                    contextMenuExpanded = false
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.DragIndicator,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.folder_tab_delete),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    onDeleteFolderClick(folder)
                                    contextMenuExpanded = false
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        item {
            IconButton(onClick = onEditClick) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}