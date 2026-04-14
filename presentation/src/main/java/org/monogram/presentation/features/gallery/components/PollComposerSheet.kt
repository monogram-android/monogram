package org.monogram.presentation.features.gallery.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.monogram.domain.models.PollDraft
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.features.chats.chatList.components.SettingsTextField
import org.monogram.presentation.features.chats.currentChat.components.inputbar.ScheduleDatePickerDialog
import org.monogram.presentation.features.chats.currentChat.components.inputbar.ScheduleTimePickerDialog
import org.monogram.presentation.features.chats.currentChat.components.inputbar.buildScheduledDateEpochSeconds
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PollComposerSheet(
    onDismiss: () -> Unit,
    onCreatePoll: (PollDraft) -> Unit
) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var description by remember { mutableStateOf("") }
    var isAnonymous by remember { mutableStateOf(true) }
    var allowsMultipleAnswers by remember { mutableStateOf(false) }
    var allowsRevoting by remember { mutableStateOf(true) }
    var shuffleOptions by remember { mutableStateOf(false) }
    var hideResultsUntilCloses by remember { mutableStateOf(false) }
    var openPeriodText by remember { mutableStateOf("") }
    var closeDateEpoch by remember { mutableStateOf<Int?>(null) }
    var isClosed by remember { mutableStateOf(false) }
    var isQuiz by remember { mutableStateOf(false) }
    var explanation by remember { mutableStateOf("") }
    val correctOptionIds = remember { mutableStateListOf<Int>() }

    var showCloseDatePicker by remember { mutableStateOf(false) }
    var showCloseTimePicker by remember { mutableStateOf(false) }
    var pendingCloseDateMillis by remember { mutableStateOf<Long?>(null) }
    var draggingOptionIndex by remember { mutableStateOf<Int?>(null) }
    var optionDragOffset by remember { mutableFloatStateOf(0f) }
    var dismissOffsetY by remember { mutableFloatStateOf(0f) }

    val preparedOptions = options.map { it.trim() }.filter { it.isNotEmpty() }
    val openPeriod = openPeriodText.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val closeDate = closeDateEpoch ?: 0
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val optionDragThresholdPx = with(density) { 44.dp.toPx() }
    val dismissDistanceThresholdPx = with(density) { 104.dp.toPx() }
    val dismissVelocityThresholdPx = with(density) { 360.dp.toPx() }
    val statusBarTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val hasClosingLimit = openPeriod > 0 || closeDate > 0
    val hasValidCorrectSelections = if (!isQuiz) {
        true
    } else if (allowsMultipleAnswers) {
        correctOptionIds.any { it in preparedOptions.indices }
    } else {
        correctOptionIds.firstOrNull() in preparedOptions.indices
    }
    val canSubmit = question.trim().isNotEmpty() &&
            preparedOptions.size >= 2 &&
            hasValidCorrectSelections

    LaunchedEffect(isQuiz, allowsMultipleAnswers, preparedOptions.size, hasClosingLimit) {
        val validIds = correctOptionIds.filter { it in preparedOptions.indices }
        if (validIds.size != correctOptionIds.size || validIds != correctOptionIds.toList()) {
            correctOptionIds.clear()
            correctOptionIds.addAll(validIds.distinct())
        }
        if (!isQuiz) {
            correctOptionIds.clear()
        } else if (!allowsMultipleAnswers && correctOptionIds.size > 1) {
            val first = correctOptionIds.first()
            correctOptionIds.clear()
            correctOptionIds.add(first)
        }
        if (!hasClosingLimit) {
            hideResultsUntilCloses = false
        }
    }

    val moveOption: (Int, Int) -> Unit = move@{ from, to ->
        if (from !in options.indices || to !in options.indices || from == to) return@move
        val movedValue = options.removeAt(from)
        options.add(to, movedValue)

        if (correctOptionIds.isNotEmpty()) {
            val remapped = correctOptionIds.map { selected ->
                when {
                    selected == from -> to
                    from < to && selected in (from + 1)..to -> selected - 1
                    from > to && selected in to until from -> selected + 1
                    else -> selected
                }
            }.distinct()
            correctOptionIds.clear()
            correctOptionIds.addAll(remapped)
        }
    }
    val dismissDragState = rememberDraggableState { delta ->
        dismissOffsetY = (dismissOffsetY + delta).coerceAtLeast(0f)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val scrimInteractionSource = remember { MutableInteractionSource() }
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.48f))
                    .clickable(
                        interactionSource = scrimInteractionSource,
                        indication = null,
                        onClick = onDismiss
                    )
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxSize()
                    .padding(top = statusBarTopPadding)
                    .offset { IntOffset(0, dismissOffsetY.roundToInt()) },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .draggable(
                                state = dismissDragState,
                                orientation = Orientation.Vertical,
                                onDragStopped = { velocity ->
                                    val shouldDismiss =
                                        dismissOffsetY > dismissDistanceThresholdPx ||
                                                velocity > dismissVelocityThresholdPx
                                    if (shouldDismiss) {
                                        onDismiss()
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
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        BottomSheetDefaults.DragHandle()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.action_create_poll),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(bottom = 8.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Top
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))

            SettingsTextField(
                value = question,
                onValueChange = { question = it },
                placeholder = stringResource(R.string.poll_question_label),
                icon = Icons.Rounded.ChatBubbleOutline,
                position = ItemPosition.TOP,
                itemSpacing = 2.dp,
                singleLine = false,
                maxLines = 4
            )
            SettingsTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = stringResource(R.string.poll_description_label),
                icon = Icons.Rounded.Description,
                position = ItemPosition.BOTTOM,
                itemSpacing = 2.dp,
                singleLine = false,
                maxLines = 3
            )

            Spacer(modifier = Modifier.height(8.dp))
            PollSectionHeader(stringResource(R.string.poll_options_label))
            options.forEachIndexed { index, value ->
                val position = when {
                    options.size == 1 -> ItemPosition.STANDALONE
                    index == 0 -> ItemPosition.TOP
                    index == options.lastIndex -> ItemPosition.BOTTOM
                    else -> ItemPosition.MIDDLE
                }
                PollOptionInputRow(
                    number = index + 1,
                    value = value,
                    onValueChange = { options[index] = it },
                    placeholder = stringResource(R.string.poll_option_label, index + 1),
                    position = position,
                    isDragging = draggingOptionIndex == index,
                    dragOffset = if (draggingOptionIndex == index) optionDragOffset else 0f,
                    onDragStart = {
                        draggingOptionIndex = index
                        optionDragOffset = 0f
                    },
                    onDragDelta = { deltaY ->
                        optionDragOffset += deltaY
                        if (optionDragOffset >= optionDragThresholdPx && index < options.lastIndex) {
                            moveOption(index, index + 1)
                            draggingOptionIndex = index + 1
                            optionDragOffset = 0f
                        } else if (optionDragOffset <= -optionDragThresholdPx && index > 0) {
                            moveOption(index, index - 1)
                            draggingOptionIndex = index - 1
                            optionDragOffset = 0f
                        }
                    },
                    onDragEnd = {
                        draggingOptionIndex = null
                        optionDragOffset = 0f
                    },
                    onRemove = if (options.size > 2) {
                        { options.removeAt(index) }
                    } else {
                        null
                    }
                )
            }
            if (options.size < 10) {
                TextButton(onClick = { options.add("") }) {
                    Text(stringResource(R.string.poll_add_option))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            PollSectionHeader(stringResource(R.string.poll_options_label))
            PollSwitchRow(
                title = stringResource(R.string.poll_anonymous),
                icon = Icons.Rounded.VisibilityOff,
                position = ItemPosition.TOP,
                checked = isAnonymous,
                onCheckedChange = { isAnonymous = it }
            )
            PollSwitchRow(
                title = stringResource(R.string.poll_multiple_choice),
                icon = Icons.Rounded.CheckCircle,
                position = ItemPosition.MIDDLE,
                checked = allowsMultipleAnswers,
                onCheckedChange = { allowsMultipleAnswers = it }
            )
            PollSwitchRow(
                title = stringResource(R.string.poll_allows_revoting),
                icon = Icons.Rounded.RestartAlt,
                position = ItemPosition.MIDDLE,
                checked = allowsRevoting,
                onCheckedChange = { allowsRevoting = it }
            )
            PollSwitchRow(
                title = stringResource(R.string.poll_shuffle_options),
                icon = Icons.Rounded.Shuffle,
                position = ItemPosition.MIDDLE,
                checked = shuffleOptions,
                onCheckedChange = { shuffleOptions = it }
            )
            PollSwitchRow(
                title = stringResource(R.string.poll_closed_immediately),
                icon = Icons.Rounded.Bolt,
                position = ItemPosition.BOTTOM,
                checked = isClosed,
                onCheckedChange = { isClosed = it }
            )

            Spacer(modifier = Modifier.height(8.dp))
            PollSectionHeader(stringResource(R.string.poll_open_period_seconds))
            SettingsTextField(
                value = openPeriodText,
                onValueChange = { openPeriodText = it.filter(Char::isDigit) },
                placeholder = stringResource(R.string.poll_open_period_seconds),
                icon = Icons.Rounded.Schedule,
                position = ItemPosition.STANDALONE,
                itemSpacing = 2.dp,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(modifier = Modifier.height(6.dp))
            PollDatePickerRow(
                icon = Icons.Rounded.Event,
                title = stringResource(R.string.poll_close_date_epoch),
                value = closeDateEpoch?.let(::formatEpochSeconds)
                    ?: stringResource(R.string.poll_close_date_not_set),
                onPickClick = { showCloseDatePicker = true },
                onClearClick = { closeDateEpoch = null }
            )
            AnimatedVisibility(
                visible = hasClosingLimit,
                enter = fadeIn(animationSpec = tween(180)) + expandVertically(
                    animationSpec = tween(
                        180
                    )
                ),
                exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(
                    animationSpec = tween(
                        120
                    )
                )
            ) {
                Column {
                    Spacer(modifier = Modifier.height(6.dp))
                    PollSwitchRow(
                        title = stringResource(R.string.poll_hide_results_until_close),
                        icon = Icons.Rounded.Lock,
                        position = ItemPosition.STANDALONE,
                        checked = hideResultsUntilCloses,
                        onCheckedChange = { hideResultsUntilCloses = it }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            PollSectionHeader(stringResource(R.string.poll_mode_quiz))
            PollSwitchRow(
                title = stringResource(R.string.poll_mode_quiz),
                icon = Icons.Rounded.School,
                position = ItemPosition.STANDALONE,
                checked = isQuiz,
                onCheckedChange = {
                    isQuiz = it
                    if (!it) correctOptionIds.clear()
                }
            )
            AnimatedVisibility(
                visible = isQuiz,
                enter = fadeIn(animationSpec = tween(200)) + expandVertically(
                    animationSpec = tween(
                        200
                    )
                ),
                exit = fadeOut(animationSpec = tween(140)) + shrinkVertically(
                    animationSpec = tween(
                        140
                    )
                )
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    PollSectionHeader(stringResource(R.string.poll_correct_option_label))
                    preparedOptions.forEachIndexed { index, option ->
                        val position = when {
                            preparedOptions.size == 1 -> ItemPosition.STANDALONE
                            index == 0 -> ItemPosition.TOP
                            index == preparedOptions.lastIndex -> ItemPosition.BOTTOM
                            else -> ItemPosition.MIDDLE
                        }
                        PollCorrectOptionRow(
                            title = option,
                            position = position,
                            checked = correctOptionIds.contains(index),
                            allowsMultipleAnswers = allowsMultipleAnswers,
                            onClick = {
                                if (allowsMultipleAnswers) {
                                    if (correctOptionIds.contains(index)) {
                                        correctOptionIds.remove(index)
                                    } else {
                                        correctOptionIds.add(index)
                                    }
                                } else {
                                    correctOptionIds.clear()
                                    correctOptionIds.add(index)
                                }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsTextField(
                        value = explanation,
                        onValueChange = { explanation = it },
                        placeholder = stringResource(R.string.poll_explanation_label),
                        icon = Icons.Rounded.Info,
                        position = ItemPosition.STANDALONE,
                        itemSpacing = 2.dp,
                        singleLine = false,
                        maxLines = 3
                    )
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
                    Text(
                        text = stringResource(R.string.action_cancel),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        onCreatePoll(
                            PollDraft(
                                question = question.trim(),
                                options = preparedOptions,
                                description = description.trim().ifBlank { null },
                                isAnonymous = isAnonymous,
                                allowsMultipleAnswers = allowsMultipleAnswers,
                                allowsRevoting = allowsRevoting,
                                shuffleOptions = shuffleOptions,
                                hideResultsUntilCloses = hideResultsUntilCloses,
                                openPeriod = openPeriod,
                                closeDate = closeDate,
                                isClosed = isClosed,
                                isQuiz = isQuiz,
                                correctOptionIds = if (isQuiz) correctOptionIds.toList() else emptyList(),
                                explanation = explanation.trim().ifBlank { null }
                            )
                        )
                    },
                    enabled = canSubmit,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.action_create_poll),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
            }
        }
    }

    if (showCloseDatePicker) {
        ScheduleDatePickerDialog(
            onDismiss = { showCloseDatePicker = false },
            onDateSelected = { selectedDateMillis ->
                pendingCloseDateMillis = selectedDateMillis
                showCloseDatePicker = false
                showCloseTimePicker = true
            }
        )
    }

    if (showCloseTimePicker) {
        val defaultTime = remember {
            Calendar.getInstance()
                .let { now -> now.get(Calendar.HOUR_OF_DAY) to now.get(Calendar.MINUTE) }
        }
        ScheduleTimePickerDialog(
            initialHour = defaultTime.first,
            initialMinute = defaultTime.second,
            onDismiss = {
                showCloseTimePicker = false
                pendingCloseDateMillis = null
            },
            onConfirm = { hour, minute ->
                val selectedDateMillis = pendingCloseDateMillis
                pendingCloseDateMillis = null
                showCloseTimePicker = false
                if (selectedDateMillis != null) {
                    closeDateEpoch =
                        buildScheduledDateEpochSeconds(selectedDateMillis, hour, minute)
                }
            }
        )
    }
}

@Composable
private fun PollSectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp, top = 16.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun PollSwitchRow(
    title: String,
    icon: ImageVector,
    position: ItemPosition,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val iconTint by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 180),
        label = "pollSwitchIconTint"
    )
    val titleColor by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 180),
        label = "pollSwitchTitleColor"
    )
    val cornerRadius = 24.dp
    val shape = when (position) {
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint
                )
                Text(title, color = titleColor)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
    if (position != ItemPosition.BOTTOM && position != ItemPosition.STANDALONE) {
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun PollOptionInputRow(
    number: Int,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    position: ItemPosition,
    isDragging: Boolean,
    dragOffset: Float,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onRemove: (() -> Unit)?
) {
    val cornerRadius = 24.dp
    val shape = when (position) {
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

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = if (isDragging) dragOffset else 0f
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$number.",
                modifier = Modifier.width(28.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )

            TextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(placeholder) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                )
            )

            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .pointerInput(number) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                onDragStart()
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDragDelta(dragAmount.y)
                            }
                        )
                    }
            )

            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.action_remove),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    if (position != ItemPosition.BOTTOM && position != ItemPosition.STANDALONE) {
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun PollCorrectOptionRow(
    title: String,
    position: ItemPosition,
    checked: Boolean,
    allowsMultipleAnswers: Boolean,
    onClick: () -> Unit
) {
    val cornerRadius = 24.dp
    val shape = when (position) {
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
    val trailingIconTint by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(
            alpha = 0.7f
        ),
        animationSpec = tween(durationMillis = 180),
        label = "pollCorrectOptionTint"
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge
            )
            Icon(
                imageVector = if (checked) {
                    if (allowsMultipleAnswers) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonChecked
                } else {
                    Icons.Rounded.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = trailingIconTint
            )
        }
    }
    if (position != ItemPosition.BOTTOM && position != ItemPosition.STANDALONE) {
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun PollDatePickerRow(
    icon: ImageVector,
    title: String,
    value: String,
    onPickClick: () -> Unit,
    onClearClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onPickClick) {
                Text(stringResource(R.string.action_pick))
            }
            TextButton(onClick = onClearClick) {
                Text(stringResource(R.string.action_clear))
            }
        }
    }
}

private fun formatEpochSeconds(epochSeconds: Int): String {
    val formatter = DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
        Locale.getDefault()
    )
    return formatter.format(Date(epochSeconds.toLong() * 1000L))
}
