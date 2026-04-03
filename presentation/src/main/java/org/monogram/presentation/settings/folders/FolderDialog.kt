package org.monogram.presentation.settings.folders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.ChatModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.features.chats.chatList.components.SectionHeader
import org.monogram.presentation.features.chats.chatList.components.SettingsTextField


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderDialog(
    title: String,
    initialText: String,
    initialIcon: String? = null,
    initialSelectedChatIds: List<Long> = emptyList(),
    availableChats: List<ChatModel> = emptyList(),
    confirmButtonText: String,
    isEditMode: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String, String?, List<Long>) -> Unit,
    onDelete: (() -> Unit)? = null,
    onSearchChats: (String) -> Unit = {}
) {
    var text by remember { mutableStateOf(initialText) }
    var selectedIcon by remember { mutableStateOf(initialIcon) }
    var selectedChatIds by remember { mutableStateOf(initialSelectedChatIds.toSet()) }
    var searchQuery by remember { mutableStateOf("") }
    val canConfirm = text.isNotBlank() && selectedChatIds.isNotEmpty()

    val icons = listOf(
        "All", "Unread", "Unmuted", "Bots", "Channels", "Groups", "Private", "Custom",
        "Setup", "Cat", "Crown", "Favorite", "Flower", "Game", "Home", "Love",
        "Mask", "Party", "Sport", "Study", "Trade", "Travel", "Work", "Airplane",
        "Book", "Light", "Like", "Money", "Note", "Palette"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                if (isEditMode && onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.folders_delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            SettingsTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = stringResource(R.string.folders_name_placeholder),
                icon = getFolderIcon(selectedIcon) ?: Icons.Rounded.Folder,
                position = ItemPosition.STANDALONE,
                singleLine = true
            )

            SectionHeader(stringResource(R.string.folders_choose_icon))

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(48.dp),
                    modifier = Modifier
                        .height(140.dp)
                        .padding(8.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(icons) { iconName ->
                        val icon = getFolderIcon(iconName)
                        if (icon != null) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selectedIcon == iconName) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                        else Color.Transparent
                                    )
                                    .clickable { selectedIcon = iconName }
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = iconName,
                                    tint = if (selectedIcon == iconName) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            SectionHeader(stringResource(R.string.folders_included_chats))

            SettingsTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    onSearchChats(it)
                },
                placeholder = stringResource(R.string.folders_search_chats),
                icon = Icons.Rounded.Search,
                position = ItemPosition.TOP,
                singleLine = true
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp, topStart = 4.dp, topEnd = 4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                LazyColumn(
                    modifier = Modifier
                        .height(200.dp)
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(availableChats) { chat ->
                        val isSelected = selectedChatIds.contains(chat.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedChatIds = if (isSelected) {
                                        selectedChatIds - chat.id
                                    } else {
                                        selectedChatIds + chat.id
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(
                                path = chat.avatarPath,
                                fallbackPath = chat.personalAvatarPath,
                                name = chat.title,
                                size = 36.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = chat.title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Icon(
                                imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = 0.5f
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.folders_cancel), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        if (canConfirm) {
                            onConfirm(text, selectedIcon, selectedChatIds.toList())
                        }
                    },
                    enabled = canConfirm,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(confirmButtonText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
