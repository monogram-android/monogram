package org.monogram.feature.dialog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.feature.dialog.R

internal const val COMMENT_FOOTER_TAG = "message-comments"

@Composable
internal fun CommentFooter(
    repliesCount: Int,
    onClick: () -> Unit,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val label = if (repliesCount > 0) {
        pluralStringResource(R.plurals.dialog_comments_count, repliesCount, repliesCount)
    } else {
        stringResource(R.string.dialog_leave_comment)
    }
    val muted = contentColor.copy(alpha = 0.82f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(COMMENT_FOOTER_TAG)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        HorizontalDivider(
            thickness = 1.dp,
            color = contentColor.copy(alpha = 0.12f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .padding(start = BUBBLE_CONTENT_PAD, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = contentColor.copy(alpha = 0.45f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

internal fun Modifier.bleedCommentFooter(
    edgeVisualMedia: Boolean,
): Modifier = layout { measurable, constraints ->
    val hInset = if (edgeVisualMedia) 0 else BUBBLE_CONTENT_PAD.roundToPx()
    val vInset = BUBBLE_CONTENT_VPAD.roundToPx()
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val width = constraints.maxWidth + hInset * 2
    val placeable = measurable.measure(
        constraints.copy(minWidth = width, maxWidth = width, minHeight = 0),
    )
    layout(constraints.maxWidth, (placeable.height - vInset).coerceAtLeast(0)) {
        placeable.place(-hInset, 0)
    }
}