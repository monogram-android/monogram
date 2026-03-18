package org.monogram.presentation.settings.folders

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.domain.models.FolderModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersContent(component: FoldersComponent) {
    val state by component.state.subscribeAsState()
    val defaultComponent = component as? DefaultFoldersComponent

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.folders_title),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = component::onBackClicked) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.folders_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = component::onCreateFolderClicked,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.folders_new_folder)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                expanded = true
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        val systemFolders = remember(state.folders) { state.folders.filter { it.id < 0 } }
        val userFolders = remember(state.folders) { state.folders.filter { it.id >= 0 } }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                FolderList(
                    systemFolders = systemFolders,
                    userFolders = userFolders,
                    onFolderClick = component::onFolderClicked,
                    onDeleteClick = component::onDeleteFolder,
                    onReorder = { fromIndex, toIndex ->
                        val fromFolder = userFolders.getOrNull(fromIndex)
                        val toFolder = userFolders.getOrNull(toIndex)
                        if (fromFolder != null && toFolder != null) {
                            val globalFrom = state.folders.indexOfFirst { it.id == fromFolder.id }
                            val globalTo = state.folders.indexOfFirst { it.id == toFolder.id }

                            if (globalFrom != -1 && globalTo != -1) {
                                component.onMoveFolder(globalFrom, globalTo)
                            }
                        }
                    }
                )
            }
        }
    }

    if (state.showAddFolderDialog) {
        FolderDialog(
            title = stringResource(R.string.folders_new_folder),
            initialText = "",
            availableChats = state.availableChats,
            confirmButtonText = stringResource(R.string.folders_create),
            onDismiss = { defaultComponent?.dismissDialog() },
            onConfirm = component::onAddFolder,
            onSearchChats = component::onSearchChats,
            videoPlayerPool = component.videoPlayerPool
        )
    }

    if (state.showEditFolderDialog && state.selectedFolder != null) {
        FolderDialog(
            title = stringResource(R.string.folders_edit_folder),
            initialText = state.selectedFolder!!.title,
            initialIcon = state.selectedFolder!!.iconName,
            initialSelectedChatIds = state.selectedChatIds,
            availableChats = state.availableChats,
            confirmButtonText = stringResource(R.string.folders_save),
            isEditMode = true,
            onDismiss = { defaultComponent?.dismissDialog() },
            onConfirm = { title, icon, chatIds ->
                component.onEditFolder(
                    state.selectedFolder!!.id,
                    title,
                    icon,
                    chatIds
                )
            },
            onDelete = { component.onDeleteFolder(state.selectedFolder!!.id) },
            onSearchChats = component::onSearchChats,
            videoPlayerPool = component.videoPlayerPool
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderList(
    systemFolders: List<FolderModel>,
    userFolders: List<FolderModel>,
    onFolderClick: (Int) -> Unit,
    onDeleteClick: (Int) -> Unit,
    onReorder: (Int, Int) -> Unit
) {
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item {
            FolderHeaderDescription()
            Spacer(Modifier.height(14.dp))
        }

        if (systemFolders.isNotEmpty()) {
            item {
                SectionHeader(stringResource(R.string.folders_default_section))
            }
            itemsIndexed(systemFolders, key = { _, folder -> "sys_${folder.id}" }) { index, folder ->
                val position = when {
                    systemFolders.size == 1 -> ItemPosition.STANDALONE
                    index == 0 -> ItemPosition.TOP
                    index == systemFolders.size - 1 -> ItemPosition.BOTTOM
                    else -> ItemPosition.MIDDLE
                }
                FolderItem(
                    folder = folder,
                    isSystem = true,
                    position = position,
                    onClick = { }
                )
            }
            item { Spacer(Modifier.height(14.dp)) }
        }

        if (userFolders.isNotEmpty()) {
            item {
                SectionHeader(stringResource(R.string.folders_custom_section))
            }
            itemsIndexed(userFolders, key = { _, folder -> folder.id }) { index, folder ->
                val position = when {
                    userFolders.size == 1 -> ItemPosition.STANDALONE
                    index == 0 -> ItemPosition.TOP
                    index == userFolders.size - 1 -> ItemPosition.BOTTOM
                    else -> ItemPosition.MIDDLE
                }

                SwipeToDeleteContainer(
                    onDelete = { onDeleteClick(folder.id) }
                ) {
                    FolderItem(
                        folder = folder,
                        isSystem = false,
                        position = position,
                        onClick = { onFolderClick(folder.id) },
                        onMoveUp = if (index > 0) {
                            { onReorder(index, index - 1) }
                        } else null,
                        onMoveDown = if (index < userFolders.size - 1) {
                            { onReorder(index, index + 1) }
                        } else null
                    )
                }
            }
        }

        if (userFolders.isEmpty() && systemFolders.isEmpty()) {
            item { EmptyFoldersState() }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteContainer(
    onDelete: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        content()
        return
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val color by animateColorAsState(
                (if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                    MaterialTheme.colorScheme.errorContainer
                else Color.Transparent),
                label = "color"
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.folders_delete),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        },
        content = { content() }
    )
}

@Composable
fun FolderHeaderDescription() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.FolderSpecial,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.folders_header_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}

@Composable
fun EmptyFoldersState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.folders_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.folders_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp, top = 16.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}
