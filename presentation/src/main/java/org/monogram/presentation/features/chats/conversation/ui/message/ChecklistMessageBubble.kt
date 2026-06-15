package org.monogram.presentation.features.chats.conversation.ui.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.ChecklistTask
import org.monogram.domain.models.ForwardInfo
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.R

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChecklistMessageBubble(
    content: MessageContent.Checklist,
    msg: MessageModel,
    isOutgoing: Boolean,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    fontSize: Float,
    letterSpacing: Float,
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    onTaskToggle: (Int, Boolean) -> Unit = { _, _ -> },
    onLongClick: () -> Unit = {},
    onOpenEditor: (() -> Unit)? = null,
    toProfile: (Long) -> Unit = {},
    onForwardOriginClick: (ForwardInfo) -> Unit = {},
    isGroup: Boolean = false
) {
    val cornerRadius = 18.dp
    val smallCorner = 4.dp
    val tailCorner = 2.dp
    val bubbleShape = RoundedCornerShape(
        topStart = if (!isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
        topEnd = if (isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
        bottomStart = if (!isOutgoing) {
            if (isSameSenderBelow) smallCorner else tailCorner
        } else cornerRadius,
        bottomEnd = if (isOutgoing) {
            if (isSameSenderBelow) smallCorner else tailCorner
        } else cornerRadius
    )
    val backgroundColor =
        if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor =
        if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val completedCount = content.tasks.count { it.completedById != null }
    val progress = if (content.tasks.isNotEmpty()) {
        completedCount.toFloat() / content.tasks.size.toFloat()
    } else {
        0f
    }

    Surface(
        modifier = Modifier.combinedClickable(
            onClick = {},
            onLongClick = onLongClick
        ),
        shape = bubbleShape,
        color = backgroundColor,
        contentColor = contentColor,
        tonalElevation = if (isOutgoing) 0.dp else 1.dp
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 324.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isGroup && !isOutgoing && !isSameSenderAbove) {
                MessageSenderName(msg, toProfile = toProfile)
            }
            msg.forwardInfo?.let {
                ForwardContent(
                    it,
                    isOutgoing,
                    onForwardClick = onForwardOriginClick
                )
            }
            msg.replyToMsg?.let { ReplyContent(it, isOutgoing, onClick = { onReplyClick(it) }) }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (content.title.isNotBlank()) {
                    val titleData = rememberMessageTextRenderData(
                        text = content.title,
                        entities = content.titleEntities,
                        allowBigEmoji = false,
                        isOutgoing = isOutgoing,
                        fontSize = fontSize
                    )
                    MessageText(
                        text = titleData.annotatedText,
                        rawText = content.title,
                        inlineContent = titleData.inlineContent,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = (fontSize + 1f).sp,
                            letterSpacing = letterSpacing.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                ChecklistProgressBar(
                    progress = progress,
                    label = stringResource(
                        R.string.checklist_progress,
                        completedCount,
                        content.tasks.size
                    ),
                    isOutgoing = isOutgoing
                )
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (isOutgoing) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                border = BorderStroke(
                    1.dp,
                    if (isOutgoing) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    content.tasks.forEach { task ->
                        ChecklistTaskRow(
                            task = task,
                            isOutgoing = isOutgoing,
                            canMarkTasksAsDone = content.canMarkTasksAsDone,
                            onToggle = {
                                val canToggle = if (task.completedById == null) {
                                    content.canMarkTasksAsDone
                                } else {
                                    true
                                }
                                if (canToggle) {
                                    onTaskToggle(task.id, task.completedById == null)
                                }
                            }
                        )
                    }
                }
            }

            if (onOpenEditor != null && content.canAddTasks) {
                ChecklistEditAction(
                    isOutgoing = isOutgoing,
                    onClick = onOpenEditor
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                MessageMetadata(
                    msg = msg,
                    isOutgoing = isOutgoing,
                    contentColor = if (isOutgoing) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    }
                )
            }

            MessageReactionsView(
                reactions = msg.reactions,
                onReactionClick = onReactionClick
            )
        }
    }
}

@Composable
private fun ChecklistProgressBar(
    progress: Float,
    label: String,
    isOutgoing: Boolean
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "checklistProgress"
    )
    val trackColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val progressColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.58f)
    } else {
        MaterialTheme.colorScheme.primary
    }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isOutgoing) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .background(trackColor, RoundedCornerShape(999.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .height(5.dp)
                    .background(progressColor, RoundedCornerShape(999.dp))
            )
        }
    }
}

@Composable
private fun ChecklistTaskRow(
    task: ChecklistTask,
    isOutgoing: Boolean,
    canMarkTasksAsDone: Boolean,
    onToggle: () -> Unit
) {
    val isDone = task.completedById != null
    val isEnabled = isDone || canMarkTasksAsDone
    val textColor by animateColorAsState(
        targetValue = when {
            isOutgoing && isDone -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.58f)
            isOutgoing -> MaterialTheme.colorScheme.onPrimaryContainer
            isDone -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        },
        label = "checklistTaskText"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isDone) 1f else 0.92f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "checklistTaskIcon"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isEnabled, onClick = onToggle)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(22.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
            shape = CircleShape,
            color = if (isDone) {
                if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
            } else {
                Color.Transparent
            },
            border = if (isDone) {
                null
            } else {
                BorderStroke(
                    1.5.dp,
                    if (isOutgoing) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.42f)
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.48f)
                    }
                )
            }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Crossfade(
                    targetState = isDone,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "checklistTaskCheck"
                ) { checked ->
                    if (checked) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = if (isOutgoing) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
                            modifier = Modifier.size(14.dp)
                        )
                    } else {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = Color.Transparent,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = task.text,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None
            )
            AnimatedVisibility(visible = isDone && !task.completedByName.isNullOrBlank()) {
                Text(
                    text = stringResource(
                        R.string.checklist_done_by,
                        task.completedByName.orEmpty()
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOutgoing) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                    }
                )
            }
        }
    }
}

@Composable
private fun ChecklistEditAction(
    isOutgoing: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (isOutgoing) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = null,
                tint = if (isOutgoing) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.checklist_edit_action),
                style = MaterialTheme.typography.labelMedium,
                color = if (isOutgoing) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
