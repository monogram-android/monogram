package org.monogram.core.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun UnreadCountRow(
    unmuted: Int,
    muted: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    unmutedColor: Color? = null,
    mutedColor: Color? = null,
) {
    if (unmuted <= 0 && muted <= 0) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (unmuted > 0) {
            UnreadBadge(
                count = unmuted,
                muted = false,
                compact = compact,
                unmutedColor = unmutedColor,
                mutedColor = mutedColor,
            )
        }
        if (muted > 0) {
            UnreadBadge(
                count = muted,
                muted = true,
                compact = compact,
                unmutedColor = unmutedColor,
                mutedColor = mutedColor,
            )
        }
    }
}

@Composable
fun UnreadBadge(
    count: Int,
    muted: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    description: String? = null,
    unmutedColor: Color? = null,
    mutedColor: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (muted) {
        mutedColor ?: scheme.onSurfaceVariant
    } else {
        unmutedColor ?: scheme.primary
    }
    val content = contentColorFor(container).takeIf { it != Color.Unspecified }
        ?: if (muted) scheme.surface else scheme.onPrimary
    AnimatedVisibility(
        visible = count > 0,
        modifier = modifier.then(
            if (description.isNullOrBlank()) {
                Modifier
            } else {
                Modifier.semantics { contentDescription = description }
            },
        ),
        enter = fadeIn() + scaleIn(initialScale = 0.72f),
        exit = fadeOut() + scaleOut(targetScale = 0.72f),
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(container)
                .padding(
                    horizontal = if (compact) 6.dp else 7.dp,
                    vertical = if (compact) 1.dp else 2.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = if (count > 999) "999+" else count.toString(),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "unread-count",
            ) { label ->
                Text(
                    text = label,
                    style = if (compact) {
                        MaterialTheme.typography.labelSmall
                    } else {
                        MaterialTheme.typography.labelMedium
                    },
                    color = content,
                )
            }
        }
    }
}
