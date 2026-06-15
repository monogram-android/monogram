package org.monogram.presentation.features.chats.conversation.ui.message

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PlaylistAddCheck
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import org.monogram.domain.repository.ChecklistDraft
import org.monogram.domain.repository.ChecklistTaskDraft
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SectionHeader
import org.monogram.presentation.core.ui.SettingsSwitchTile
import org.monogram.presentation.core.ui.SettingsTextField
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecklistEditorSheet(
    draft: ChecklistDraft,
    onSave: (ChecklistDraft) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(draft) { mutableStateOf(draft.title) }
    val tasks = remember(draft) {
        mutableStateListOf<ChecklistTaskDraft>().apply {
            addAll(draft.tasks.ifEmpty { listOf(ChecklistTaskDraft(id = 1, text = "")) })
        }
    }
    var othersCanAddTasks by remember(draft) { mutableStateOf(draft.othersCanAddTasks) }
    var othersCanMarkTasksAsDone by remember(draft) { mutableStateOf(draft.othersCanMarkTasksAsDone) }

    var dismissOffsetY by remember { mutableFloatStateOf(0f) }
    var sheetHeightPx by remember { mutableFloatStateOf(0f) }
    var isAnimationReady by remember { mutableStateOf(false) }
    var isClosing by remember { mutableStateOf(false) }

    val trimmedTasks = tasks.mapNotNull { task ->
        task.copy(text = task.text.trim()).takeIf { it.text.isNotBlank() }
    }
    val canSubmit = trimmedTasks.isNotEmpty()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val statusBarTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val dismissDistanceThresholdPx = with(density) { 104.dp.toPx() }
    val dismissVelocityThresholdPx = with(density) { 360.dp.toPx() }
    val hiddenOffset = sheetHeightPx.takeIf { it > 0f } ?: with(density) { 720.dp.toPx() }
    val dismissProgress = (dismissOffsetY / hiddenOffset).coerceIn(0f, 1f)

    fun requestDismiss() {
        if (isClosing) return
        isClosing = true
        scope.launch {
            animate(
                initialValue = dismissOffsetY,
                targetValue = hiddenOffset,
                animationSpec = tween(durationMillis = 220)
            ) { value, _ -> dismissOffsetY = value }
            onDismiss()
        }
    }

    fun addTask() {
        val nextId = (tasks.maxOfOrNull(ChecklistTaskDraft::id) ?: 0) + 1
        tasks.add(ChecklistTaskDraft(id = nextId, text = ""))
    }

    LaunchedEffect(sheetHeightPx) {
        if (sheetHeightPx > 0f && !isAnimationReady) {
            dismissOffsetY = hiddenOffset
            isAnimationReady = true
        }
    }

    LaunchedEffect(isAnimationReady) {
        if (!isAnimationReady) return@LaunchedEffect
        animate(
            initialValue = dismissOffsetY,
            targetValue = 0f,
            animationSpec = spring()
        ) { value, _ -> dismissOffsetY = value }
    }

    val dismissDragState = rememberDraggableState { delta ->
        if (!isClosing) dismissOffsetY = (dismissOffsetY + delta).coerceAtLeast(0f)
    }
    val surfaceScale by animateFloatAsState(
        targetValue = if (isAnimationReady && !isClosing) 1f else 0.985f,
        animationSpec = spring(),
        label = "checklistEditorScale"
    )
    val surfaceAlpha by animateFloatAsState(
        targetValue = if (isAnimationReady && !isClosing) 1f else 0.92f,
        animationSpec = tween(durationMillis = 220),
        label = "checklistEditorAlpha"
    )

    Dialog(
        onDismissRequest = ::requestDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        ChecklistEditorSystemBars()
        val scrimInteractionSource = remember { MutableInteractionSource() }

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.48f * (1f - dismissProgress)))
                    .clickable(
                        interactionSource = scrimInteractionSource,
                        indication = null,
                        onClick = ::requestDismiss
                    )
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxSize()
                    .padding(top = statusBarTopPadding)
                    .offset { IntOffset(0, dismissOffsetY.roundToInt()) }
                    .onSizeChanged { sheetHeightPx = it.height.toFloat() }
                    .graphicsLayer {
                        scaleX = surfaceScale
                        scaleY = surfaceScale
                        alpha = surfaceAlpha
                    },
                shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .animateContentSize()
                ) {
                    ChecklistEditorHeader(
                        modifier = Modifier
                            .fillMaxWidth()
                            .draggable(
                                state = dismissDragState,
                                orientation = Orientation.Vertical,
                                onDragStopped = { velocity ->
                                    if (isClosing) return@draggable
                                    val shouldDismiss =
                                        dismissOffsetY > dismissDistanceThresholdPx ||
                                                velocity > dismissVelocityThresholdPx
                                    if (shouldDismiss) {
                                        requestDismiss()
                                    } else {
                                        scope.launch {
                                            animate(
                                                initialValue = dismissOffsetY,
                                                targetValue = 0f,
                                                animationSpec = spring()
                                            ) { value, _ -> dismissOffsetY = value }
                                        }
                                    }
                                }
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        onDismiss = ::requestDismiss
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        ChecklistSectionCard(title = stringResource(R.string.checklist_create_section_main)) {
                            SettingsTextField(
                                value = title,
                                onValueChange = { title = it },
                                placeholder = stringResource(R.string.checklist_create_title_label),
                                icon = Icons.Rounded.Description,
                                position = ItemPosition.STANDALONE,
                                singleLine = false,
                                minLines = 1,
                                maxLines = 3,
                                itemSpacing = 2.dp
                            )
                        }

                        ChecklistSectionCard(
                            title = stringResource(R.string.checklist_create_section_tasks),
                            subtitle = stringResource(R.string.checklist_create_tasks_hint),
                            trailing = {
                                TextButton(onClick = ::addTask) {
                                    Text(stringResource(R.string.checklist_create_add_task))
                                }
                            }
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                tasks.forEachIndexed { index, task ->
                                    AnimatedVisibility(
                                        visible = true,
                                        enter = fadeIn(tween(160)) + expandVertically(tween(160)),
                                        exit = fadeOut(tween(120)) + shrinkVertically(tween(120))
                                    ) {
                                        ChecklistTaskInputRow(
                                            number = index + 1,
                                            value = task.text,
                                            onValueChange = { text ->
                                                tasks[index] = task.copy(text = text)
                                            },
                                            position = when {
                                                tasks.size == 1 -> ItemPosition.STANDALONE
                                                index == 0 -> ItemPosition.TOP
                                                index == tasks.lastIndex -> ItemPosition.BOTTOM
                                                else -> ItemPosition.MIDDLE
                                            },
                                            onRemove = if (tasks.size > 1) {
                                                { tasks.removeAt(index) }
                                            } else {
                                                null
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        ChecklistSectionCard(
                            title = stringResource(R.string.checklist_create_section_permissions),
                            subtitle = stringResource(R.string.checklist_create_permissions_hint)
                        ) {
                            SettingsSwitchTile(
                                icon = Icons.Rounded.PlaylistAddCheck,
                                title = stringResource(R.string.checklist_create_others_can_add_tasks),
                                checked = othersCanAddTasks,
                                iconColor = MaterialTheme.colorScheme.primary,
                                position = ItemPosition.TOP,
                                onCheckedChange = { othersCanAddTasks = it }
                            )
                            SettingsSwitchTile(
                                icon = Icons.Rounded.VerifiedUser,
                                title = stringResource(R.string.checklist_create_others_can_mark_tasks),
                                checked = othersCanMarkTasksAsDone,
                                iconColor = MaterialTheme.colorScheme.primary,
                                position = ItemPosition.BOTTOM,
                                onCheckedChange = { othersCanMarkTasksAsDone = it }
                            )
                        }
                    }

                    ChecklistEditorFooter(
                        canSubmit = canSubmit,
                        onCancel = ::requestDismiss,
                        onSubmit = {
                            onSave(
                                ChecklistDraft(
                                    title = title.trim(),
                                    titleEntities = draft.titleEntities,
                                    tasks = trimmedTasks,
                                    othersCanAddTasks = othersCanAddTasks,
                                    othersCanMarkTasksAsDone = othersCanMarkTasksAsDone
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChecklistEditorSystemBars() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    val navigationBarColor = MaterialTheme.colorScheme.surfaceContainerLow
    val useDarkNavIcons = navigationBarColor.luminance() > 0.5f

    DisposableEffect(window, navigationBarColor, useDarkNavIcons) {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val previousStatusColor = window.statusBarColor
        val previousNavigationColor = window.navigationBarColor
        val previousLightStatus = insetsController.isAppearanceLightStatusBars
        val previousLightNavigation = insetsController.isAppearanceLightNavigationBars
        val previousNavContrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced
        } else {
            false
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = useDarkNavIcons
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        onDispose {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.statusBarColor = previousStatusColor
            window.navigationBarColor = previousNavigationColor
            insetsController.isAppearanceLightStatusBars = previousLightStatus
            insetsController.isAppearanceLightNavigationBars = previousLightNavigation
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = previousNavContrast
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChecklistEditorHeader(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BottomSheetDefaults.DragHandle(modifier = Modifier.align(Alignment.CenterHorizontally))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.checklist_create_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cancel_button)
                )
            }
        }
    }
}

@Composable
private fun ChecklistSectionCard(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.animateContentSize()) {
        SectionHeader(
            text = title,
            modifier = Modifier.padding(top = 2.dp)
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                modifier = Modifier.padding(start = 12.dp, bottom = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                if (trailing != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        trailing()
                    }
                }
                content()
            }
        }
    }
}

@Composable
private fun ChecklistTaskInputRow(
    number: Int,
    value: String,
    onValueChange: (String) -> Unit,
    position: ItemPosition,
    onRemove: (() -> Unit)?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = checklistGroupShape(position),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Box(
                    modifier = Modifier.size(34.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = stringResource(R.string.checklist_create_task_label, number),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge,
                singleLine = false,
                minLines = 1,
                maxLines = 3,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                )
            )

            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.checklist_create_remove_task),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ChecklistEditorFooter(
    canSubmit: Boolean,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        text = stringResource(R.string.cancel_button),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Button(
                    onClick = onSubmit,
                    enabled = canSubmit,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        text = stringResource(R.string.checklist_save),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private fun checklistGroupShape(position: ItemPosition): RoundedCornerShape {
    val corner = 22.dp
    return when (position) {
        ItemPosition.TOP -> RoundedCornerShape(
            topStart = corner,
            topEnd = corner,
            bottomStart = 8.dp,
            bottomEnd = 8.dp
        )

        ItemPosition.MIDDLE -> RoundedCornerShape(8.dp)
        ItemPosition.BOTTOM -> RoundedCornerShape(
            topStart = 8.dp,
            topEnd = 8.dp,
            bottomStart = corner,
            bottomEnd = corner
        )

        ItemPosition.STANDALONE -> RoundedCornerShape(corner)
    }
}
