package org.monogram.presentation.features.gallery.components

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import org.monogram.domain.models.PollDraft
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsSwitchTile
import org.monogram.presentation.core.ui.SettingsTile
import org.monogram.presentation.features.chats.chatList.components.SectionHeader
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
    var closeDateEpoch by remember { mutableStateOf<Int?>(null) }
    var isQuiz by remember { mutableStateOf(false) }
    var explanation by remember { mutableStateOf("") }
    val correctOptionIds = remember { mutableStateListOf<Int>() }

    var showCloseDatePicker by remember { mutableStateOf(false) }
    var showCloseTimePicker by remember { mutableStateOf(false) }
    var pendingCloseDateMillis by remember { mutableStateOf<Long?>(null) }
    var draggingOptionIndex by remember { mutableStateOf<Int?>(null) }
    var optionDragOffset by remember { mutableFloatStateOf(0f) }
    var dismissOffsetY by remember { mutableFloatStateOf(0f) }
    var sheetHeightPx by remember { mutableFloatStateOf(0f) }
    var isAnimationReady by remember { mutableStateOf(false) }
    var isClosing by remember { mutableStateOf(false) }

    val trimmedOptionEntries = options.mapIndexedNotNull { index, value ->
        value.trim().takeIf { it.isNotEmpty() }?.let { trimmed ->
            index to trimmed
        }
    }
    val sourceToPreparedIndex = trimmedOptionEntries.mapIndexed { preparedIndex, (sourceIndex, _) ->
        sourceIndex to preparedIndex
    }.toMap()
    val preparedOptions = trimmedOptionEntries.map { it.second }
    val selectedPreparedCorrectIds =
        correctOptionIds.mapNotNull(sourceToPreparedIndex::get).distinct()
    val closeDate = closeDateEpoch ?: 0
    val hasClosingLimit = closeDate > 0
    val hasValidCorrectSelections = when {
        !isQuiz -> true
        allowsMultipleAnswers -> selectedPreparedCorrectIds.isNotEmpty()
        else -> selectedPreparedCorrectIds.size == 1
    }
    val canSubmit = question.trim().isNotEmpty() &&
            preparedOptions.size >= 2 &&
            hasValidCorrectSelections

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val statusBarTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val optionDragThresholdPx = with(density) { 44.dp.toPx() }
    val dismissDistanceThresholdPx = with(density) { 104.dp.toPx() }
    val dismissVelocityThresholdPx = with(density) { 360.dp.toPx() }
    val hiddenOffset = sheetHeightPx.takeIf { it > 0f } ?: with(density) { 720.dp.toPx() }
    val dismissProgress = (dismissOffsetY / hiddenOffset).coerceIn(0f, 1f)

    fun removeOption(index: Int) {
        if (index !in options.indices || options.size <= 2) return
        options.removeAt(index)
        val remapped = correctOptionIds.mapNotNull { selected ->
            when {
                selected == index -> null
                selected > index -> selected - 1
                else -> selected
            }
        }
        correctOptionIds.clear()
        correctOptionIds.addAll(remapped.distinct())
    }

    val moveOption: (Int, Int) -> Unit = move@{ from, to ->
        if (from !in options.indices || to !in options.indices || from == to) return@move
        val movedValue = options.removeAt(from)
        options.add(to, movedValue)

        val remapped = correctOptionIds.map { selected ->
            when {
                selected == from -> to
                from < to && selected in (from + 1)..to -> selected - 1
                from > to && selected in to until from -> selected + 1
                else -> selected
            }
        }
        correctOptionIds.clear()
        correctOptionIds.addAll(remapped.distinct())
    }

    fun requestDismiss() {
        if (isClosing) return
        isClosing = true
        scope.launch {
            animate(
                initialValue = dismissOffsetY,
                targetValue = hiddenOffset,
                animationSpec = tween(durationMillis = 220)
            ) { value, _ ->
                dismissOffsetY = value
            }
            onDismiss()
        }
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
        ) { value, _ ->
            dismissOffsetY = value
        }
    }

    LaunchedEffect(isQuiz, allowsMultipleAnswers, hasClosingLimit, options.toList()) {
        val validIds = correctOptionIds
            .filter { index -> options.getOrNull(index)?.trim()?.isNotEmpty() == true }
            .distinct()
        if (validIds != correctOptionIds.toList()) {
            correctOptionIds.clear()
            correctOptionIds.addAll(validIds)
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

    val dismissDragState = rememberDraggableState { delta ->
        if (isClosing) return@rememberDraggableState
        dismissOffsetY = (dismissOffsetY + delta).coerceAtLeast(0f)
    }
    val surfaceScale by animateFloatAsState(
        targetValue = if (isAnimationReady && !isClosing) 1f else 0.985f,
        animationSpec = spring(),
        label = "pollComposerScale"
    )
    val surfaceAlpha by animateFloatAsState(
        targetValue = if (isAnimationReady && !isClosing) 1f else 0.92f,
        animationSpec = tween(durationMillis = 220),
        label = "pollComposerAlpha"
    )

    Dialog(
        onDismissRequest = ::requestDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        PollComposerSystemBars()
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
                Column(modifier = Modifier.fillMaxSize()) {
                    PollComposerHeader(
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
                                            ) { value, _ ->
                                                dismissOffsetY = value
                                            }
                                        }
                                    }
                                }
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        onDismiss = ::requestDismiss
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        PollSectionCard(
                            title = stringResource(R.string.poll_create_section_main)
                        ) {
                            PollEditorField(
                                icon = Icons.Rounded.ChatBubbleOutline,
                                value = question,
                                onValueChange = { question = it },
                                placeholder = stringResource(R.string.poll_create_question_label),
                                position = ItemPosition.TOP,
                                minLines = 2,
                                maxLines = 4,
                                prominent = true
                            )
                            PollEditorField(
                                icon = Icons.Rounded.Description,
                                value = description,
                                onValueChange = { description = it },
                                placeholder = stringResource(R.string.poll_create_description_label),
                                position = ItemPosition.BOTTOM,
                                minLines = 2,
                                maxLines = 3
                            )
                        }

                        PollSectionCard(
                            title = stringResource(R.string.poll_create_section_options),
                            subtitle = stringResource(R.string.poll_create_options_hint),
                            trailing = {
                                if (options.size < 10) {
                                    TextButton(onClick = { options.add("") }) {
                                        Text(stringResource(R.string.poll_create_add_option))
                                    }
                                }
                            }
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                        placeholder = stringResource(
                                            R.string.poll_create_option_label,
                                            index + 1
                                        ),
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
                                            { removeOption(index) }
                                        } else {
                                            null
                                        }
                                    )
                                }
                            }
                        }
                        PollSectionCard(
                            title = stringResource(R.string.poll_create_section_settings),
                            subtitle = stringResource(R.string.poll_create_settings_hint)
                        ) {
                            data class SettingsToggleItem(
                                val icon: ImageVector,
                                val title: String,
                                val checked: Boolean,
                                val onCheckedChange: (Boolean) -> Unit
                            )

                            val settingsItems = listOf(
                                SettingsToggleItem(
                                    icon = Icons.Rounded.VisibilityOff,
                                    title = stringResource(R.string.poll_create_anonymous),
                                    checked = isAnonymous,
                                    onCheckedChange = { isAnonymous = it }
                                ),
                                SettingsToggleItem(
                                    icon = Icons.Rounded.CheckCircle,
                                    title = stringResource(R.string.poll_create_multiple_answers),
                                    checked = allowsMultipleAnswers,
                                    onCheckedChange = { allowsMultipleAnswers = it }
                                ),
                                SettingsToggleItem(
                                    icon = Icons.Rounded.RestartAlt,
                                    title = stringResource(R.string.poll_create_allows_revoting),
                                    checked = allowsRevoting,
                                    onCheckedChange = { allowsRevoting = it }
                                ),
                                SettingsToggleItem(
                                    icon = Icons.Rounded.Shuffle,
                                    title = stringResource(R.string.poll_create_shuffle_options),
                                    checked = shuffleOptions,
                                    onCheckedChange = { shuffleOptions = it }
                                )
                            )

                            settingsItems.forEachIndexed { index, item ->
                                val position = when {
                                    settingsItems.size == 1 -> ItemPosition.STANDALONE
                                    index == 0 -> ItemPosition.TOP
                                    index == settingsItems.lastIndex -> ItemPosition.BOTTOM
                                    else -> ItemPosition.MIDDLE
                                }
                                PollToggleRow(
                                    icon = item.icon,
                                    title = item.title,
                                    checked = item.checked,
                                    onCheckedChange = item.onCheckedChange,
                                    position = position
                                )
                            }
                        }

                        PollSectionCard(
                            title = stringResource(R.string.poll_create_section_schedule),
                            subtitle = stringResource(R.string.poll_create_closing_hint)
                        ) {
                            PollDatePickerCard(
                                value = if (hasClosingLimit) {
                                    formatEpochSeconds(closeDate)
                                } else {
                                    stringResource(R.string.poll_create_close_date_not_set)
                                },
                                onPickDate = { showCloseDatePicker = true },
                                onClearDate = if (hasClosingLimit) {
                                    {
                                        closeDateEpoch = null
                                        hideResultsUntilCloses = false
                                    }
                                } else {
                                    null
                                }
                            )

                            AnimatedVisibility(
                                visible = hasClosingLimit,
                                enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                                exit = fadeOut(tween(140)) + shrinkVertically(tween(140))
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    PollToggleRow(
                                        icon = Icons.Rounded.Lock,
                                        title = stringResource(R.string.poll_create_hide_results_until_close),
                                        checked = hideResultsUntilCloses,
                                        onCheckedChange = { hideResultsUntilCloses = it },
                                        position = ItemPosition.STANDALONE
                                    )
                                }
                            }
                        }

                        PollSectionCard(
                            title = stringResource(R.string.poll_create_section_quiz),
                            subtitle = stringResource(R.string.poll_create_quiz_hint)
                        ) {
                            PollToggleRow(
                                icon = Icons.Rounded.School,
                                title = stringResource(R.string.poll_create_quiz_mode),
                                checked = isQuiz,
                                onCheckedChange = { isQuiz = it },
                                position = ItemPosition.STANDALONE
                            )

                            AnimatedVisibility(
                                visible = isQuiz,
                                enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                                exit = fadeOut(tween(140)) + shrinkVertically(tween(140))
                            ) {
                                Column(
                                    modifier = Modifier.padding(top = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.poll_create_correct_answer_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        trimmedOptionEntries.forEachIndexed { preparedIndex, entry ->
                                            val (sourceIndex, text) = entry
                                            val position = when {
                                                trimmedOptionEntries.size == 1 -> ItemPosition.STANDALONE
                                                preparedIndex == 0 -> ItemPosition.TOP
                                                preparedIndex == trimmedOptionEntries.lastIndex -> ItemPosition.BOTTOM
                                                else -> ItemPosition.MIDDLE
                                            }
                                            PollCorrectOptionRow(
                                                number = preparedIndex + 1,
                                                text = text,
                                                checked = correctOptionIds.contains(sourceIndex),
                                                allowMultipleSelection = allowsMultipleAnswers,
                                                position = position,
                                                onClick = {
                                                    if (allowsMultipleAnswers) {
                                                        if (correctOptionIds.contains(sourceIndex)) {
                                                            correctOptionIds.remove(sourceIndex)
                                                        } else {
                                                            correctOptionIds.add(sourceIndex)
                                                        }
                                                    } else {
                                                        correctOptionIds.clear()
                                                        correctOptionIds.add(sourceIndex)
                                                    }
                                                }
                                            )
                                        }
                                    }

                                    PollEditorField(
                                        icon = Icons.Rounded.Description,
                                        value = explanation,
                                        onValueChange = { explanation = it },
                                        placeholder = stringResource(R.string.poll_create_explanation_label),
                                        position = ItemPosition.STANDALONE,
                                        minLines = 2,
                                        maxLines = 4
                                    )
                                }
                            }
                        }

                    }

                    PollComposerFooter(
                        canSubmit = canSubmit,
                        onCancel = ::requestDismiss,
                        onSubmit = {
                            if (!canSubmit) return@PollComposerFooter
                            onCreatePoll(
                                PollDraft(
                                    question = question.trim(),
                                    options = preparedOptions,
                                    description = description.trim().ifBlank { null },
                                    isAnonymous = isAnonymous,
                                    allowsMultipleAnswers = allowsMultipleAnswers,
                                    allowsRevoting = allowsRevoting,
                                    shuffleOptions = shuffleOptions,
                                    hideResultsUntilCloses = hasClosingLimit && hideResultsUntilCloses,
                                    openPeriod = 0,
                                    closeDate = closeDate,
                                    isClosed = false,
                                    isQuiz = isQuiz,
                                    correctOptionIds = if (isQuiz) selectedPreparedCorrectIds else emptyList(),
                                    explanation = explanation.trim().ifBlank { null }
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

    if (showCloseDatePicker) {
        ScheduleDatePickerDialog(
            onDismiss = {
                showCloseDatePicker = false
                pendingCloseDateMillis = null
            },
            onDateSelected = { selectedMillis ->
                pendingCloseDateMillis = selectedMillis
                showCloseDatePicker = false
                showCloseTimePicker = true
            }
        )
    }

    if (showCloseTimePicker) {
        val calendar = remember(closeDateEpoch, pendingCloseDateMillis) {
            Calendar.getInstance().apply {
                timeInMillis = when {
                    closeDateEpoch != null -> closeDateEpoch!! * 1000L
                    pendingCloseDateMillis != null -> pendingCloseDateMillis!!
                    else -> System.currentTimeMillis()
                }
            }
        }

        ScheduleTimePickerDialog(
            initialHour = calendar.get(Calendar.HOUR_OF_DAY),
            initialMinute = calendar.get(Calendar.MINUTE),
            onDismiss = {
                showCloseTimePicker = false
                pendingCloseDateMillis = null
            },
            onConfirm = { hour, minute ->
                val selectedDateMillis = pendingCloseDateMillis ?: System.currentTimeMillis()
                closeDateEpoch = buildScheduledDateEpochSeconds(selectedDateMillis, hour, minute)
                showCloseTimePicker = false
                pendingCloseDateMillis = null
            }
        )
    }
}

@Composable
private fun PollComposerSystemBars() {
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
private fun PollComposerHeader(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BottomSheetDefaults.DragHandle(
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.poll_create_title),
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
private fun PollSectionCard(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.animateContentSize()
    ) {
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
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
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
private fun PollEditorField(
    icon: ImageVector,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    position: ItemPosition,
    minLines: Int,
    maxLines: Int,
    prominent: Boolean = false
) {
    SettingsTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        icon = icon,
        position = position,
        singleLine = false,
        minLines = minLines,
        maxLines = maxLines,
        itemSpacing = 2.dp,
        modifier = if (prominent) {
            Modifier.shadow(0.dp)
        } else {
            Modifier
        }
    )
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
    onRemove: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val elevation by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "optionElevation"
    )
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.02f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "optionScale"
    )
    val containerColor by animateColorAsState(
        targetValue = if (isDragging) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "optionColor"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isDragging) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        } else {
            Color.Transparent
        },
        label = "optionBorder"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffset
                scaleX = scale
                scaleY = scale
            }
            .shadow(elevation, shape = pollGroupShape(position)),
        shape = pollGroupShape(if (isDragging) ItemPosition.STANDALONE else position),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                            text = placeholder,
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
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onDragStart()
                                },
                                onDragCancel = onDragEnd,
                                onDragEnd = onDragEnd,
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDragDelta(dragAmount.y)
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DragHandle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PollToggleRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    position: ItemPosition
) {
    SettingsSwitchTile(
        icon = icon,
        title = title,
        checked = checked,
        iconColor = MaterialTheme.colorScheme.primary,
        position = position,
        onCheckedChange = onCheckedChange
    )
}

@Composable
private fun PollCorrectOptionRow(
    number: Int,
    text: String,
    checked: Boolean,
    allowMultipleSelection: Boolean,
    position: ItemPosition,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = pollGroupShape(position),
        color = if (checked) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            1.dp,
            if (checked) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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

            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Icon(
                imageVector = when {
                    allowMultipleSelection && checked -> Icons.Rounded.CheckCircle
                    allowMultipleSelection -> Icons.Rounded.RadioButtonUnchecked
                    checked -> Icons.Rounded.RadioButtonChecked
                    else -> Icons.Rounded.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun PollDatePickerCard(
    value: String,
    onPickDate: () -> Unit,
    onClearDate: (() -> Unit)?
) {
    SettingsTile(
        icon = Icons.Rounded.Event,
        title = stringResource(R.string.poll_create_close_date),
        subtitle = value,
        iconColor = MaterialTheme.colorScheme.primary,
        position = ItemPosition.STANDALONE,
        onClick = onPickDate,
        trailingContent = if (onClearDate != null) {
            {
                IconButton(onClick = onClearDate) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.poll_create_clear_date),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            null
        }
    )
}

@Composable
private fun PollComposerFooter(
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
                        text = stringResource(R.string.action_create_poll),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private fun pollGroupShape(position: ItemPosition): RoundedCornerShape {
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

private fun formatEpochSeconds(epochSeconds: Int): String {
    return try {
        val formatter = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            Locale.getDefault()
        )
        formatter.format(Date(epochSeconds * 1000L))
    } catch (_: Exception) {
        ""
    }
}
