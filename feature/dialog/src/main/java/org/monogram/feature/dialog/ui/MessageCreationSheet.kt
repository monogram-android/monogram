package org.monogram.feature.dialog.ui

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.ChecklistSendSpec
import org.monogram.core.models.MessageSendOptions
import org.monogram.core.models.PollSendSpec
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.core.ui.components.SettingsCard
import org.monogram.core.ui.components.SettingsCardRow
import org.monogram.feature.dialog.R

internal enum class MessageCreationKind { Poll, Checklist }

@Composable
internal fun MessageCreationSheet(
    kind: MessageCreationKind, admissionId: Long?, busy: Boolean, error: TelegramError?,
    onSendPoll: (PollSendSpec, MessageSendOptions) -> Unit,
    onSendChecklist: (ChecklistSendSpec, MessageSendOptions) -> Unit,
    onDismiss: () -> Unit, allowed: Boolean = true,
) {
    val checklist = kind == MessageCreationKind.Checklist
    val minItems = if (checklist) 1 else 2
    val maxItems = if (checklist) 30 else 10
    var question by rememberSaveable(kind) { mutableStateOf("") }
    var answers by rememberSaveable(kind) { mutableStateOf(ArrayList(List(minItems) { "" })) }
    var itemIds by rememberSaveable(kind) { mutableStateOf(ArrayList(List(minItems) { it })) }
    var nextItemId by rememberSaveable(kind) { mutableIntStateOf(minItems) }
    var removingIds by remember { mutableStateOf(setOf<Int>()) }
    var focusItemId by remember { mutableIntStateOf(-1) }
    var append by rememberSaveable(kind) { mutableStateOf(false) }
    var complete by rememberSaveable(kind) { mutableStateOf(false) }
    var anonymous by rememberSaveable(kind) { mutableStateOf(true) }
    var multiple by rememberSaveable(kind) { mutableStateOf(false) }
    var quiz by rememberSaveable(kind) { mutableStateOf(false) }
    var correct by rememberSaveable(kind) { mutableIntStateOf(-1) }
    var solution by rememberSaveable(kind) { mutableStateOf("") }
    val sendState = rememberSaveable(kind, saver = CreationSendState.Saver) { CreationSendState() }
    val interactive = allowed && !busy
    val previousAdmission = remember(kind) { admissionId }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(admissionId) { if (admissionId != previousAdmission) closing = true }
    val pollSpec = PollSendSpec(
        question,
        answers.toList(),
        anonymous,
        multiple,
        quiz,
        correct.takeIf { quiz && it >= 0 },
        solution.takeIf { quiz && it.isNotBlank() })
    val checklistSpec = ChecklistSendSpec(question, answers.toList(), append, complete)
    val valid =
        removingIds.isEmpty() && runCatching { if (checklist) checklistSpec.requireValid() else pollSpec.requireValid() }.isSuccess
    val send: (MessageSendOptions) -> Unit = { options ->
        if (checklist) onSendChecklist(checklistSpec, options) else onSendPoll(pollSpec, options)
    }
    val itemLabel = if (checklist) R.string.dialog_checklist_item else R.string.dialog_poll_option
    val fieldColors = TextFieldDefaults.colors(
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
    )
    AppModalSheet(
        modifier = Modifier.fillMaxHeight(0.95f),
        onDismissRequest = { if (!busy) closing = true },
        visible = !closing, onExited = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            CreationSheetHeader(
                title = stringResource(if (checklist) R.string.dialog_checklist_create else R.string.dialog_poll_create),
                enabled = !busy, onDismiss = { closing = true },
            )
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextField(
                    question,
                    { question = it },
                    shape = MaterialTheme.shapes.large,
                    label = { Text(stringResource(if (checklist) R.string.dialog_checklist_title else R.string.dialog_poll_question)) },
                    enabled = interactive,
                    colors = fieldColors,
                    isError = question.length > if (checklist) 32 else 255,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(
                    Modifier.animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemIds.forEachIndexed { index, id ->
                        key(id) {
                            val optionLabel = stringResource(itemLabel, index + 1)
                            val focusRequester = remember { FocusRequester() }
                            val bringIntoView = remember { BringIntoViewRequester() }
                            LaunchedEffect(focusItemId == id) {
                                if (focusItemId == id) {
                                    focusRequester.requestFocus()
                                    withFrameNanos { }
                                    bringIntoView.bringIntoView()
                                    focusItemId = -1
                                }
                            }
                            val rowVisible = remember {
                                MutableTransitionState(focusItemId != id).apply {
                                    targetState = true
                                }
                            }
                            LaunchedEffect(rowVisible.isIdle, rowVisible.currentState) {
                                if (rowVisible.isIdle && !rowVisible.currentState && !rowVisible.targetState) {
                                    val removedIndex = itemIds.indexOf(id)
                                    if (removedIndex >= 0) {
                                        answers =
                                            ArrayList(answers).apply { removeAt(removedIndex) }
                                        itemIds =
                                            ArrayList(itemIds).apply { removeAt(removedIndex) }
                                        correct = when {
                                            correct == removedIndex -> -1
                                            correct > removedIndex -> correct - 1
                                            else -> correct
                                        }
                                    }
                                    removingIds = removingIds - id
                                }
                            }
                            AnimatedVisibility(
                                rowVisible,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TextField(
                                        answers[index],
                                        { value ->
                                            answers = ArrayList(answers).apply { set(index, value) }
                                        },
                                        shape = MaterialTheme.shapes.large,
                                        label = { Text(optionLabel) },
                                        singleLine = true,
                                        enabled = interactive,
                                        colors = fieldColors,
                                        isError = answers[index].length > if (checklist) 64 else 100,
                                        leadingIcon = if (checklist) ({
                                            Icon(
                                                Icons.Outlined.Checklist,
                                                contentDescription = null
                                            )
                                        }) else null,
                                        modifier = Modifier
                                            .weight(1f)
                                            .focusRequester(focusRequester)
                                            .bringIntoViewRequester(bringIntoView),
                                    )
                                    AnimatedVisibility(
                                        !checklist && quiz,
                                        enter = fadeIn() + expandVertically(),
                                        exit = fadeOut() + shrinkVertically()
                                    ) {
                                        val correctLabel = stringResource(
                                            R.string.dialog_poll_correct_option,
                                            index + 1
                                        )
                                        RadioButton(
                                            selected = correct == index,
                                            onClick = { correct = index },
                                            enabled = interactive,
                                            modifier = Modifier.semantics {
                                                contentDescription = correctLabel
                                            },
                                        )
                                    }
                                    if (answers.size > minItems) FilledTonalIconButton(
                                        onClick = {
                                            removingIds = removingIds + id
                                            rowVisible.targetState = false
                                        },
                                        enabled = interactive && answers.size - removingIds.size > minItems && id !in removingIds,
                                        shapes = IconButtonDefaults.shapes(),
                                    ) {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription = stringResource(R.string.dialog_delete) + ": " + optionLabel
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (answers.size < maxItems) FilledTonalButton(
                    onClick = {
                        answers = ArrayList(answers).apply { add("") }
                        focusItemId = nextItemId
                        itemIds = ArrayList(itemIds).apply { add(nextItemId++) }
                    },
                    enabled = interactive,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Icon(
                        Icons.Outlined.Add, contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(stringResource(if (checklist) R.string.dialog_checklist_add else R.string.dialog_poll_add_option))
                }
                SettingsCard {
                    if (checklist) {
                        CreationSetting(
                            stringResource(R.string.dialog_checklist_allow_append),
                            append,
                            interactive,
                            icon = Icons.Outlined.PlaylistAdd
                        ) { append = it }
                        CreationSetting(
                            stringResource(R.string.dialog_checklist_allow_complete),
                            complete,
                            interactive,
                            icon = Icons.Outlined.DoneAll
                        ) { complete = it }
                    } else {
                        CreationSetting(
                            stringResource(R.string.dialog_poll_anonymous), anonymous, interactive,
                            icon = Icons.Outlined.VisibilityOff
                        ) { anonymous = it }
                        CreationSetting(
                            stringResource(R.string.dialog_poll_multiple),
                            multiple,
                            interactive && !quiz,
                            icon = Icons.Outlined.DoneAll
                        ) { multiple = it }
                        CreationSetting(
                            stringResource(R.string.dialog_poll_quiz), quiz, interactive,
                            icon = Icons.Outlined.Quiz
                        ) { quiz = it; if (it) multiple = false }
                    }
                }
                AnimatedVisibility(
                    !checklist && quiz,
                    enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()
                ) {
                    TextField(
                        solution,
                        { solution = it },
                        shape = MaterialTheme.shapes.large,
                        label = { Text(stringResource(R.string.dialog_poll_explanation)) },
                        enabled = interactive,
                        colors = fieldColors,
                        isError = solution.length > 200,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CreationSendControls(sendState, valid, busy, allowed, error, send)
            }
        }
    }
}

@Composable
internal fun CreationSetting(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    icon: ImageVector = Icons.Outlined.NotificationsOff,
    onChecked: (Boolean) -> Unit,
) {
    SettingsCardRow(
        icon = icon,
        title = label,
        iconColor = MaterialTheme.colorScheme.primary,
        enabled = enabled,
        toggle = checked,
        onToggle = onChecked,
        trailingContent = { Switch(checked, onCheckedChange = null, enabled = enabled) },
    )
}
