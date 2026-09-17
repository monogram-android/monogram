package org.monogram.feature.dialog.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.core.models.Message
import org.monogram.core.models.MessageReaction
import org.monogram.core.models.ReactionChoice
import org.monogram.core.models.parseReactionsJson
import org.monogram.core.models.reactionPickerChoices
import org.monogram.feature.dialog.R

private val ReactionGlyphSize = 18.dp
private val ReactionGlyphSp = 18.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReactionBar(
    message: Message,
    onReact: (emoticon: String, documentId: Long) -> Unit,
    modifier: Modifier = Modifier,
    onAddReaction: (() -> Unit)? = null,
    onShowUsers: (() -> Unit)? = null,
) {
    val reactions = remember(message.reactionsJson) { parseReactionsJson(message.reactionsJson) }
    if (reactions.isEmpty()) return
    Row(
        modifier = modifier.wrapContentWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        reactions.forEach { reaction ->
            ReactionChip(
                reaction = reaction,
                onClick = {
                    onReact(reaction.emoticon.orEmpty(), reaction.documentId ?: 0L)
                },
                onLongClick = onShowUsers,
            )
        }
        if (onAddReaction != null) {
            val addLabel = stringResource(R.string.dialog_add_reaction)
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = addLabel,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(onClick = onAddReaction)
                    .padding(5.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ReactionPickerStrip(
    recent: List<ReactionChoice>,
    onReact: (emoticon: String, documentId: Long) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val choices = remember(recent) { reactionPickerChoices(recent) }
    val cell = if (compact) 40.dp else 44.dp
    val glyph = ReactionGlyphSize
    Surface(
        modifier = modifier.wrapContentWidth(),
        shape = RoundedCornerShape(if (compact) 22.dp else 32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(
                    horizontal = if (compact) 4.dp else 8.dp,
                    vertical = if (compact) 2.dp else 6.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            choices.forEach { choice ->
                val label = choice.emoticon.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.dialog_add_reaction)
                Box(
                    modifier = Modifier
                        .size(cell)
                        .clip(CircleShape)
                        .semantics { contentDescription = "react $label" }
                        .clickable { onReact(choice.emoticon, choice.documentId) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (choice.documentId != 0L) {
                        CustomEmojiGlyph(documentId = choice.documentId, size = glyph)
                    } else {
                        Text(
                            text = choice.emoticon,
                            fontSize = ReactionGlyphSp,
                            lineHeight = ReactionGlyphSp,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReactionChip(
    reaction: MessageReaction,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val label = reaction.emoticon.orEmpty()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (reaction.chosen) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            )
            .semantics { contentDescription = "reaction $label" }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val documentId = reaction.documentId
        if (documentId != null && documentId != 0L) {
            CustomEmojiGlyph(documentId = documentId, size = ReactionGlyphSize)
        } else {
            Text(
                text = reaction.emoticon.orEmpty(),
                fontSize = ReactionGlyphSp,
                lineHeight = ReactionGlyphSp,
            )
        }
        if (reaction.count > 0) {
            Text(
                text = reaction.count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
