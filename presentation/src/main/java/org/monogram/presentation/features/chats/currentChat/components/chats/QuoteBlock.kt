package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.monogram.presentation.core.ui.spacer.WeightSpacer

/**
 * Quote block
 *
 * @param text quote text
 * @param isOutgoing marks if message sent by me
 * @param expandable true, if quote supports expanding
 **/
@Composable
internal fun QuoteBlock(
    text: String,
    isOutgoing: Boolean,
    expandable: Boolean,
) {
    var isCollapsed by remember(expandable, text) { mutableStateOf(true) }
    val background = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    }
    val bottomIcon = if (isCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp

    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .animateContentSize()
            .clickable(
                interactionSource = null,
                enabled = expandable,
                indication = ripple()
            ) {
                isCollapsed = !isCollapsed
            }
            .background(
                color = background,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Spacer(
            Modifier
                .width(2.dp)
                .padding(vertical = 2.dp)
                .fillMaxHeight()
                .background(
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(4.dp)
                )
        )

        Text(
            text = text,
            maxLines = if (expandable && isCollapsed) 3 else Int.MAX_VALUE,
            modifier = Modifier
                .weight(1f, fill = false),
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )

        Column {
            Icon(
                imageVector = Icons.Default.FormatQuote,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            WeightSpacer(1f)

            if (expandable) {
                Icon(
                    imageVector = bottomIcon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun QuoteBlockPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuoteBlock(
                text = "Ваше мнение, конечно, очень важно, но, не очень-то и нужно...",
                isOutgoing = true,
                expandable = false
            )

            QuoteBlock(
                text = "Ваше мнение, конечно, очень важно, но, не очень-то и нужно...",
                isOutgoing = false,
                expandable = false
            )

            QuoteBlock(
                text = "Ваше мнение, конечно, очень важно, но, не очень-то и нужно...".repeat(4),
                isOutgoing = true,
                expandable = true
            )

            QuoteBlock(
                text = "Ваше мнение, конечно, очень важно, но, не очень-то и нужно...".repeat(4),
                isOutgoing = false,
                expandable = true
            )
        }
    }
}