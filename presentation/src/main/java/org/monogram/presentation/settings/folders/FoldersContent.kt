@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.settings.folders

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.monogram.domain.models.FolderModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersContent(component: FoldersComponent) {
    val state by component.state.subscribeAsState()
    val defaultComponent = component as? DefaultFoldersComponent

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "FoldersContent" },
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
                ContainedLoadingIndicator(modifier = Modifier.align(Alignment.Center))
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
            onSearchChats = component::onSearchChats
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
            onSearchChats = component::onSearchChats
        )
    }
}

@Composable
fun FolderList(
    systemFolders: List<FolderModel>,
    userFolders: List<FolderModel>,
    onFolderClick: (Int) -> Unit,
    onDeleteClick: (Int) -> Unit,
    onReorder: (Int, Int) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var draggedItemId by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var initialDragStartOffset by remember { mutableStateOf(0) }
    var initialPointerY by remember { mutableStateOf(0f) }
    var totalDragDistance by remember { mutableStateOf(0f) }
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }
    var autoScrollVelocity by remember { mutableStateOf(0f) }

    val userFoldersStartIndex = remember(systemFolders, userFolders) {
        1 +
                (if (systemFolders.isNotEmpty()) systemFolders.size + 2 else 0) +
                (if (userFolders.isNotEmpty()) 1 else 0)
    }

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
            itemsIndexed(userFolders, key = { _, folder -> "user_${folder.id}" }) { index, folder ->
                val currentUserFolders by rememberUpdatedState(userFolders)
                val currentIndex by rememberUpdatedState(index)
                val currentOnReorder by rememberUpdatedState(onReorder)

                val isDragging = draggedItemId == folder.id
                val elevation by animateDpAsState(
                    targetValue = if (isDragging) 8.dp else 0.dp,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "elevation"
                )
                val scale by animateFloatAsState(
                    targetValue = if (isDragging) 1.02f else 1f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "scale"
                )

                val position = when {
                    userFolders.size == 1 -> ItemPosition.STANDALONE
                    index == 0 -> ItemPosition.TOP
                    index == userFolders.size - 1 -> ItemPosition.BOTTOM
                    else -> ItemPosition.MIDDLE
                }
                val effectivePosition = if (isDragging) ItemPosition.STANDALONE else position
                val shape = remember(effectivePosition) { getFolderShape(effectivePosition) }

                Box(
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .then(
                            if (isDragging) {
                                Modifier
                            } else {
                                Modifier.animateItem(
                                    fadeInSpec = null,
                                    placementSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    ),
                                    fadeOutSpec = null
                                )
                            }
                        )
                        .graphicsLayer {
                            translationY = if (isDragging) dragOffset else 0f
                            scaleX = scale
                            scaleY = scale
                        }
                        .shadow(elevation, shape = shape)
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    draggedItemId = folder.id
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                                    val itemInfo = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.key == "user_${folder.id}" }
                                    initialDragStartOffset = itemInfo?.offset ?: 0
                                    initialPointerY = offset.y + (itemInfo?.offset ?: 0)
                                    totalDragDistance = 0f
                                    dragOffset = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    totalDragDistance += dragAmount.y

                                    val currentItemInfo = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.key == "user_${folder.id}" }
                                        ?: return@detectDragGesturesAfterLongPress

                                    val targetY = initialDragStartOffset + totalDragDistance
                                    dragOffset = targetY - currentItemInfo.offset

                                    val draggedCenter = targetY + currentItemInfo.size / 2

                                    val targetItem = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { item ->
                                            draggedCenter > item.offset &&
                                                    draggedCenter < item.offset + item.size &&
                                                    item.index >= userFoldersStartIndex
                                        }

                                    if (targetItem != null) {
                                        val targetIndex = targetItem.index - userFoldersStartIndex
                                        if (targetIndex != currentIndex && targetIndex in currentUserFolders.indices) {
                                            currentOnReorder(currentIndex, targetIndex)
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }

                                    val viewportHeight = listState.layoutInfo.viewportSize.height
                                    val topThreshold = 100.dp.toPx()
                                    val bottomThreshold = viewportHeight - 100.dp.toPx()
                                    val pointerY = initialPointerY + totalDragDistance

                                    if (pointerY < topThreshold) {
                                        val intensity = ((topThreshold - pointerY) / topThreshold).coerceIn(0f, 1f)
                                        autoScrollVelocity = -(6f + (18f * intensity))
                                    } else if (pointerY > bottomThreshold) {
                                        val intensity = ((pointerY - bottomThreshold) / topThreshold).coerceIn(0f, 1f)
                                        autoScrollVelocity = 6f + (18f * intensity)
                                    } else {
                                        autoScrollVelocity = 0f
                                        autoScrollJob?.cancel()
                                        autoScrollJob = null
                                    }

                                    if (autoScrollJob == null && abs(autoScrollVelocity) > 0f) {
                                        autoScrollJob = scope.launch {
                                            while (abs(autoScrollVelocity) > 0f) {
                                                listState.scrollBy(autoScrollVelocity)
                                                delay(16)
                                            }
                                        }.also { job ->
                                            job.invokeOnCompletion {
                                                if (autoScrollJob === job) autoScrollJob = null
                                            }
                                        }
                                    }
                                },
                                onDragEnd = {
                                    draggedItemId = null
                                    dragOffset = 0f
                                    totalDragDistance = 0f
                                    autoScrollVelocity = 0f
                                    autoScrollJob?.cancel()
                                    autoScrollJob = null
                                },
                                onDragCancel = {
                                    draggedItemId = null
                                    dragOffset = 0f
                                    totalDragDistance = 0f
                                    autoScrollVelocity = 0f
                                    autoScrollJob?.cancel()
                                    autoScrollJob = null
                                }
                            )
                        }
                ) {
                    SwipeToDeleteContainer(
                        onDelete = { onDeleteClick(folder.id) },
                        enabled = draggedItemId == null
                    ) {
                        FolderItem(
                            folder = folder,
                            isSystem = false,
                            position = effectivePosition,
                            onClick = { onFolderClick(folder.id) },
                            showReorder = true
                        )
                    }
                }
            }
        }

        if (userFolders.isEmpty() && systemFolders.isEmpty()) {
            item { EmptyFoldersState() }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

private fun getFolderShape(position: ItemPosition): Shape {
    val cornerRadius = 24.dp
    return when (position) {
        ItemPosition.TOP -> RoundedCornerShape(
            topStart = cornerRadius,
            topEnd = cornerRadius,
            bottomStart = 4.dp,
            bottomEnd = 4.dp
        )

        ItemPosition.MIDDLE -> RoundedCornerShape(4.dp)
        ItemPosition.BOTTOM -> RoundedCornerShape(
            bottomStart = cornerRadius,
            bottomEnd = cornerRadius,
            topStart = 4.dp,
            topEnd = 4.dp
        )

        ItemPosition.STANDALONE -> RoundedCornerShape(cornerRadius)
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
