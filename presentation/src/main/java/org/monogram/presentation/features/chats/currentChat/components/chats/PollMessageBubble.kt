package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.*
import org.monogram.presentation.R

@Composable
fun PollMessageBubble(
    content: MessageContent.Poll,
    msg: MessageModel,
    isOutgoing: Boolean,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    fontSize: Float,
    letterSpacing: Float,
    bubbleRadius: Float = 18f,
    onOptionClick: (Int) -> Unit,
    onRetractVote: () -> Unit = {},
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    onShowVoters: (Int) -> Unit = {},
    onClosePoll: () -> Unit = {},
    onLongClick: (Offset) -> Unit = {},
    toProfile: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cornerRadius = bubbleRadius.dp
    val smallCorner = (bubbleRadius / 4f).coerceAtLeast(4f).dp
    val tailCorner = 2.dp

    val bubbleShape = RoundedCornerShape(
        topStart = if (!isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
        topEnd = if (isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
        bottomStart = if (!isOutgoing) (if (isSameSenderBelow) smallCorner else tailCorner) else cornerRadius,
        bottomEnd = if (isOutgoing) (if (isSameSenderBelow) smallCorner else tailCorner) else cornerRadius
    )

    val containerColor =
        if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor =
        if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    val hasVoted = content.options.any { it.isChosen }
    val isQuiz = content.type is PollType.Quiz
    val quizType = content.type as? PollType.Quiz
    val isMultiChoice = (content.type as? PollType.Regular)?.allowMultipleAnswers == true

    Column(
        modifier = modifier
            .width(IntrinsicSize.Max)
            .widthIn(min = 240.dp, max = 320.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onLongClick(it) }
                )
            },
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = containerColor,
            contentColor = contentColor,
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                msg.forwardInfo?.let { ForwardContent(it, isOutgoing, onForwardClick = toProfile) }
                msg.replyToMsg?.let { ReplyContent(it, isOutgoing) { onReplyClick(it) } }

                PollHeader(
                    question = content.question,
                    pollType = content.type,
                    fontSize = fontSize,
                    letterSpacing = letterSpacing,
                    hasVoted = hasVoted,
                    isClosed = content.isClosed,
                    isAnonymous = content.isAnonymous,
                    isOutgoing = isOutgoing,
                    onRetractVote = onRetractVote,
                    onClosePoll = onClosePoll
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    content.options.forEachIndexed { index, option ->
                        val isCorrect = isQuiz && quizType?.correctOptionId == index
                        val isWrong = isQuiz && option.isChosen && quizType?.correctOptionId != index

                        val canVote = !content.isClosed && !hasVoted

                        PollOptionItem(
                            option = option,
                            isMultiChoice = isMultiChoice,
                            isQuiz = isQuiz,
                            isCorrect = isCorrect,
                            isWrong = isWrong,
                            showResults = hasVoted || content.isClosed,
                            isAnonymous = content.isAnonymous,
                            canVote = canVote,
                            onClick = { onOptionClick(index) },
                            onShowVoters = { onShowVoters(index) }
                        )
                    }
                }

                AnimatedVisibility(
                    visible = isQuiz && hasVoted && !quizType?.explanation.isNullOrBlank()
                ) {
                    QuizExplanationBox(
                        text = quizType?.explanation ?: "",
                        containerColor = contentColor.copy(alpha = 0.05f),
                        textColor = contentColor
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                PollFooter(
                    totalVotes = content.totalVoterCount,
                    date = msg.date,
                    isOutgoing = isOutgoing,
                    sendingState = msg.sendingState,
                    isRead = msg.isRead,
                    contentColor = contentColor
                )
            }
        }

        MessageReactionsView(
            reactions = msg.reactions,
            onReactionClick = onReactionClick,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun PollHeader(
    question: String,
    pollType: PollType,
    fontSize: Float,
    letterSpacing: Float,
    hasVoted: Boolean,
    isClosed: Boolean,
    isAnonymous: Boolean,
    isOutgoing: Boolean,
    onRetractVote: () -> Unit,
    onClosePoll: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            val isQuiz = pollType is PollType.Quiz
            val isMultiChoice = (pollType as? PollType.Regular)?.allowMultipleAnswers == true

            val label = if (isClosed) {
                stringResource(R.string.poll_final_results)
            } else {
                buildString {
                    append(if (isAnonymous) stringResource(R.string.poll_anonymous) else stringResource(R.string.poll_public))
                    append(" ")
                    append(if (isQuiz) stringResource(R.string.poll_type_quiz) else stringResource(R.string.poll_type_poll))
                    if (isMultiChoice) append(stringResource(R.string.poll_multiple_choice))
                }
            }

            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            if (!isClosed && (hasVoted || isOutgoing)) {
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.cd_more),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        if (hasVoted) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.poll_retract_vote)) },
                                onClick = {
                                    showMenu = false
                                    onRetractVote()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Cancel,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                        if (isOutgoing) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.poll_close_poll)) },
                                onClick = {
                                    showMenu = false
                                    onClosePoll()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Stop,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = question,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = (fontSize + 2).sp,
                letterSpacing = letterSpacing.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = (fontSize + 6).sp
            )
        )
    }
}

@Composable
private fun PollOptionItem(
    option: PollOption,
    isMultiChoice: Boolean,
    isQuiz: Boolean,
    isCorrect: Boolean,
    isWrong: Boolean,
    showResults: Boolean,
    isAnonymous: Boolean,
    canVote: Boolean,
    onClick: () -> Unit,
    onShowVoters: () -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = if (showResults) option.votePercentage / 100f else 0f,
        animationSpec = tween(durationMillis = 600), label = "progress"
    )

    val baseContentColor = MaterialTheme.colorScheme.onSurface
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val successColor = Color(0xFF4CAF50)

    val stateColor = when {
        showResults && isCorrect -> successColor
        showResults && isWrong -> errorColor
        option.isChosen -> primaryColor
        else -> MaterialTheme.colorScheme.outline
    }

    val shape = RoundedCornerShape(12.dp)
    val borderColor =
        if (option.isChosen || (showResults && isCorrect)) stateColor else MaterialTheme.colorScheme.outlineVariant.copy(
            alpha = 0.5f
        )
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainer

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(backgroundColor)
            .border(
                border = BorderStroke(if (option.isChosen) 2.dp else 1.dp, borderColor),
                shape = shape
            )
            .clickable(enabled = canVote || (showResults && !isAnonymous)) {
                if (canVote) onClick() else if (showResults && !isAnonymous) onShowVoters()
            }
    ) {
        if (showResults) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(stateColor.copy(alpha = 0.15f))
            )
        }

        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (showResults) {
                    if (isCorrect || isWrong || option.isChosen) {
                        Icon(
                            imageVector = when {
                                isCorrect -> Icons.Default.CheckCircle
                                isWrong -> Icons.Default.Cancel
                                else -> Icons.Default.CheckCircle
                            },
                            contentDescription = null,
                            tint = stateColor,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text(
                            text = "${option.votePercentage}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = baseContentColor.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    val unselectedIcon =
                        if (isMultiChoice) Icons.Rounded.CheckBoxOutlineBlank else Icons.Outlined.RadioButtonUnchecked
                    Icon(
                        imageVector = unselectedIcon,
                        contentDescription = stringResource(R.string.cd_vote),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = option.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (showResults && (isCorrect || isWrong)) stateColor else baseContentColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (showResults) {
                Spacer(modifier = Modifier.width(8.dp))
                if (isCorrect || isWrong || option.isChosen) {
                    Text(
                        text = "${option.votePercentage}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = stateColor
                    )
                }
            }
        }
    }
}

@Composable
private fun QuizExplanationBox(
    text: String,
    containerColor: Color,
    textColor: Color
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = containerColor
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = stringResource(R.string.cd_explanation),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(16.dp)
                        .padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor
                )
            }
        }
    }
}

@Composable
private fun PollFooter(
    totalVotes: Int,
    date: Int,
    isOutgoing: Boolean,
    sendingState: MessageSendingState?,
    isRead: Boolean,
    contentColor: Color
) {
    val metaColor = contentColor.copy(alpha = 0.65f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = pluralStringResource(R.plurals.poll_votes_count, totalVotes, totalVotes),
                style = MaterialTheme.typography.labelSmall,
                color = metaColor
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatTime(date),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = metaColor
            )

            if (isOutgoing) {
                Spacer(modifier = Modifier.width(4.dp))
                val (icon, tint) = when (sendingState) {
                    is MessageSendingState.Pending -> Icons.Default.Schedule to metaColor
                    is MessageSendingState.Failed -> Icons.Default.Error to MaterialTheme.colorScheme.error
                    null -> if (isRead) Icons.Default.DoneAll to MaterialTheme.colorScheme.primary
                    else Icons.Default.Check to metaColor
                }

                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = tint
                )
            }
        }
    }
}