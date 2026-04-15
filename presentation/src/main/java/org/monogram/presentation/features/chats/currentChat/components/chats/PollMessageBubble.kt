package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.HowToVote
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendingState
import org.monogram.domain.models.PollOption
import org.monogram.domain.models.PollType
import org.monogram.presentation.R
import org.monogram.presentation.core.util.DateFormatManager

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
    hasCommentsButton: Boolean = false,
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
        bottomEnd = if (isOutgoing) {
            if (isSameSenderBelow) smallCorner else tailCorner
        } else {
            if (hasCommentsButton) 4.dp else cornerRadius
        }
    )

    val containerColor =
        if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor =
        if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val accentColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.primary
    }

    val hasVoted = content.options.any { it.isChosen }
    val isQuiz = content.type is PollType.Quiz
    val quizType = content.type as? PollType.Quiz
    val isMultiChoice = when (val type = content.type) {
        is PollType.Regular -> type.allowMultipleAnswers
        is PollType.Quiz -> type.correctOptionIds.size > 1
    }
    val showResults = content.isClosed || (hasVoted && !content.hideResultsUntilCloses)

    Column(
        modifier = modifier
            .width(IntrinsicSize.Max)
            .widthIn(min = 240.dp, max = 332.dp)
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
                modifier = Modifier
                    .padding(12.dp)
                    .animateContentSize()
            ) {
                msg.forwardInfo?.let { ForwardContent(it, isOutgoing, onForwardClick = toProfile) }
                msg.replyToMsg?.let { ReplyContent(it, isOutgoing) { onReplyClick(it) } }

                PollHeroHeader(
                    content = content,
                    fontSize = fontSize,
                    letterSpacing = letterSpacing,
                    hasVoted = hasVoted,
                    isOutgoing = isOutgoing,
                    accentColor = accentColor,
                    contentColor = contentColor,
                    onRetractVote = onRetractVote,
                    onClosePoll = onClosePoll
                )

                Spacer(modifier = Modifier.height(10.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    content.options.forEachIndexed { index, option ->
                        val isCorrect =
                            isQuiz && (quizType?.correctOptionIds?.contains(index) == true)
                        val isWrong = isQuiz && option.isChosen && !isCorrect
                        val canVote = !content.isClosed && !hasVoted

                        PollOptionCard(
                            index = index,
                            option = option,
                            isMultiChoice = isMultiChoice,
                            isQuiz = isQuiz,
                            isCorrect = isCorrect,
                            isWrong = isWrong,
                            showResults = showResults,
                            isAnonymous = content.isAnonymous,
                            canVote = canVote,
                            accentColor = accentColor,
                            onClick = { onOptionClick(index) },
                            onShowVoters = { onShowVoters(index) }
                        )
                    }
                }

                AnimatedVisibility(
                    visible = isQuiz && hasVoted && !quizType?.explanation.isNullOrBlank()
                ) {
                    QuizExplanationCard(
                        text = quizType?.explanation.orEmpty(),
                        accentColor = accentColor,
                        contentColor = contentColor
                    )
                }

                PollFooterBar(
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
private fun PollHeroHeader(
    content: MessageContent.Poll,
    fontSize: Float,
    letterSpacing: Float,
    hasVoted: Boolean,
    isOutgoing: Boolean,
    accentColor: Color,
    contentColor: Color,
    onRetractVote: () -> Unit,
    onClosePoll: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val dateFormatManager: DateFormatManager = koinInject()
    val timeFormat = dateFormatManager.getHourMinuteFormat()
    var nowEpochSeconds by remember { mutableIntStateOf((System.currentTimeMillis() / 1000L).toInt()) }

    LaunchedEffect(content.closeDate, content.isClosed) {
        if (content.closeDate <= 0 || content.isClosed) return@LaunchedEffect
        while (true) {
            nowEpochSeconds = (System.currentTimeMillis() / 1000L).toInt()
            if (nowEpochSeconds >= content.closeDate) break
            delay(1_000L)
        }
    }

    val remainingSeconds = (content.closeDate - nowEpochSeconds).coerceAtLeast(0)
    val isQuiz = content.type is PollType.Quiz
    val isMultiChoice = when (val type = content.type) {
        is PollType.Regular -> type.allowMultipleAnswers
        is PollType.Quiz -> type.correctOptionIds.size > 1
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PollHeaderPill(
                    text = if (content.isClosed) {
                        stringResource(R.string.poll_final_results)
                    } else if (content.isAnonymous) {
                        stringResource(R.string.poll_anonymous)
                    } else {
                        stringResource(R.string.poll_public)
                    },
                    accentColor = accentColor,
                    prominent = true
                )
                PollHeaderPill(
                    text = if (isQuiz) {
                        stringResource(R.string.poll_type_quiz)
                    } else {
                        stringResource(R.string.poll_type_poll)
                    },
                    accentColor = accentColor
                )
                if (isMultiChoice) {
                    PollHeaderPill(
                        text = stringResource(R.string.poll_create_chip_multiple_answers),
                        accentColor = accentColor
                    )
                }
            }

            if (!content.isClosed && (hasVoted || isOutgoing)) {
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.cd_more),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        offset = DpOffset(x = 0.dp, y = 8.dp),
                        shape = RoundedCornerShape(22.dp),
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {
                        Surface(
                            modifier = Modifier.widthIn(min = 220.dp, max = 260.dp),
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
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
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    )
                                }
                                if (isOutgoing) {
                                    if (hasVoted) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 12.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(
                                                alpha = 0.5f
                                            )
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = stringResource(R.string.poll_close_poll),
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            onClosePoll()
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Stop,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = content.question,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = (fontSize + 2).sp,
                letterSpacing = letterSpacing.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = (fontSize + 7).sp
            )
        )

        if (!content.description.isNullOrBlank()) {
            Text(
                text = content.description.orEmpty(),
                style = MaterialTheme.typography.bodySmall.copy(letterSpacing = letterSpacing.sp),
                color = contentColor.copy(alpha = 0.78f)
            )
        }

        AnimatedVisibility(visible = content.closeDate > 0) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = accentColor.copy(alpha = 0.09f),
                modifier = Modifier.animateContentSize()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Event,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                R.string.poll_meta_closes_at,
                                formatTime(content.closeDate, timeFormat)
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = contentColor
                        )
                        if (!content.isClosed && remainingSeconds > 0) {
                            Text(
                                text = stringResource(
                                    R.string.poll_meta_time_left,
                                    formatRemainingDuration(remainingSeconds)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = 0.7f)
                            )
                        }
                    }
                    if (content.hideResultsUntilCloses && !content.isClosed) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        val metaLabel = buildList {
            if (content.hideResultsUntilCloses && !content.isClosed && content.closeDate <= 0) {
                add(stringResource(R.string.poll_meta_results_hidden_until_close))
            }
            if (content.allowsRevoting) add(stringResource(R.string.poll_meta_revoting_enabled))
            if (content.shuffleOptions) add(stringResource(R.string.poll_meta_shuffle_enabled))
        }.joinToString(" • ")

        if (metaLabel.isNotBlank()) {
            Text(
                text = metaLabel,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.68f)
            )
        }
    }
}
@Composable
private fun PollHeaderPill(
    text: String,
    accentColor: Color,
    prominent: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (prominent) accentColor.copy(alpha = 0.14f) else accentColor.copy(alpha = 0.08f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = accentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PollOptionCard(
    index: Int,
    option: PollOption,
    isMultiChoice: Boolean,
    isQuiz: Boolean,
    isCorrect: Boolean,
    isWrong: Boolean,
    showResults: Boolean,
    isAnonymous: Boolean,
    canVote: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    onShowVoters: () -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = if (showResults) option.votePercentage / 100f else 0f,
        animationSpec = tween(durationMillis = 650),
        label = "pollProgress"
    )

    val successColor = Color(0xFF2E8B57)
    val cardScale by animateFloatAsState(
        targetValue = if (option.isBeingChosen) 0.985f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "pollOptionScale"
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (option.isBeingChosen) 0.78f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "pollOptionAlpha"
    )
    val stateColor = when {
        showResults && isCorrect -> successColor
        showResults && isWrong -> MaterialTheme.colorScheme.error
        option.isChosen || option.isBeingChosen -> accentColor
        else -> MaterialTheme.colorScheme.outline
    }
    val borderColor = when {
        option.isBeingChosen -> accentColor
        option.isChosen || (showResults && isCorrect) -> stateColor.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    }
    val backgroundColor = if (showResults && (isCorrect || isWrong || option.isChosen)) {
        stateColor.copy(alpha = 0.08f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val clickEnabled = canVote || (showResults && !isAnonymous)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
                alpha = cardAlpha
            }
            .animateContentSize()
            .clickable(enabled = clickEnabled) {
                if (canVote) onClick() else if (showResults && !isAnonymous) onShowVoters()
            }
    ) {
        if (showResults) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(stateColor.copy(alpha = 0.12f))
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    BorderStroke(if (option.isChosen) 2.dp else 1.dp, borderColor),
                    RoundedCornerShape(20.dp)
                )
                .background(backgroundColor)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = stateColor.copy(alpha = 0.12f)
            ) {
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (showResults && (isCorrect || isWrong)) {
                        Icon(
                            imageVector = if (isCorrect) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            tint = stateColor,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Text(
                            text = (index + 1).toString(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = stateColor
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = option.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (showResults && (isCorrect || isWrong)) stateColor else MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (showResults) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.poll_votes_count,
                            option.voterCount,
                            option.voterCount
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (showResults) {
                    Text(
                        text = "${option.votePercentage}%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = stateColor
                    )
                }
                Icon(
                    imageVector = when {
                        showResults && isQuiz && isCorrect -> Icons.Default.CheckCircle
                        showResults && isQuiz && isWrong -> Icons.Default.Cancel
                        option.isChosen -> Icons.Default.Check
                        isMultiChoice -> Icons.Rounded.CheckBoxOutlineBlank
                        else -> Icons.Outlined.RadioButtonUnchecked
                    },
                    contentDescription = null,
                    tint = if (option.isChosen || (showResults && (isCorrect || isWrong))) {
                        stateColor
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun QuizExplanationCard(
    text: String,
    accentColor: Color,
    contentColor: Color
) {
    Surface(
        modifier = Modifier.padding(top = 10.dp),
        shape = RoundedCornerShape(20.dp),
        color = accentColor.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Lightbulb,
                contentDescription = stringResource(R.string.cd_explanation),
                tint = accentColor,
                modifier = Modifier
                    .size(18.dp)
                    .padding(top = 1.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.poll_create_section_quiz),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor
                )
            }
        }
    }
}

@Composable
private fun PollFooterBar(
    totalVotes: Int,
    date: Int,
    isOutgoing: Boolean,
    sendingState: MessageSendingState?,
    isRead: Boolean,
    contentColor: Color
) {
    val metaColor = contentColor.copy(alpha = 0.65f)
    val dateFormatManager: DateFormatManager = koinInject()
    val timeFormat = dateFormatManager.getHourMinuteFormat()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.HowToVote,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = metaColor
            )
            Text(
                text = pluralStringResource(R.plurals.poll_votes_count, totalVotes, totalVotes),
                style = MaterialTheme.typography.labelSmall,
                color = metaColor
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatTime(date, timeFormat),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = metaColor
            )

            if (isOutgoing) {
                Spacer(modifier = Modifier.width(4.dp))
                val (icon, tint) = when (sendingState) {
                    is MessageSendingState.Pending -> Icons.Default.Schedule to metaColor
                    is MessageSendingState.Failed -> Icons.Default.Error to MaterialTheme.colorScheme.error
                    null -> if (isRead) Icons.Default.DoneAll to MaterialTheme.colorScheme.primary else Icons.Default.Check to metaColor
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

private fun formatRemainingDuration(totalSeconds: Int): String {
    val days = totalSeconds / 86_400
    val hours = (totalSeconds % 86_400) / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return when {
        days > 0 -> String.format("%dd %02dh", days, hours)
        hours > 0 -> String.format("%02d:%02d:%02d", hours, minutes, seconds)
        else -> String.format("%02d:%02d", minutes, seconds)
    }
}
