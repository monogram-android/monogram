package org.monogram.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import org.monogram.feature.folders.FoldersComponent
import org.monogram.feature.folders.R
import org.monogram.feature.folders.ui.FoldersEditorScreen
import org.monogram.feature.folders.ui.FoldersListScreen
import org.monogram.feature.settings.SettingsFolderHost

/**
 * Adapts `feature/folders` to the Settings page chrome. The settings module never sees the
 * folders model, and the two features stay independent of each other.
 */
@Composable
fun rememberSettingsFolderHost(component: FoldersComponent): SettingsFolderHost {
    val state by component.state.collectAsState()
    val editing by component.editing.collectAsState()
    val listTitle = stringResource(R.string.folders_title)
    val newFolderTitle = stringResource(R.string.folders_new)
    LaunchedEffect(Unit) { component.onHydrateChats() }

    return SettingsFolderHost(
        title = editing?.title?.ifBlank { newFolderTitle } ?: listTitle,
        isEditing = editing != null,
        canSave = editing?.title?.isNotBlank() == true,
        onAdd = component::onNewFolder,
        onRefresh = component::onRefresh,
        onSave = component::onSaveEdit,
        onBack = component::onCancelEdit,
        content = { innerPadding ->
            val draft = editing
            if (draft != null) {
                FoldersEditorScreen(
                    folder = draft,
                    chats = state.chats,
                    innerPadding = innerPadding,
                    onChange = component::onChangeFolder,
                    onDelete = { component.onDeleteEdit() },
                )
            } else {
                FoldersListScreen(
                    state = state,
                    innerPadding = innerPadding,
                    onMove = component::onMoveFolder,
                    onOpen = component::onEditFolder,
                    onRetry = component::onRefresh,
                )
            }
        },
    )
}
