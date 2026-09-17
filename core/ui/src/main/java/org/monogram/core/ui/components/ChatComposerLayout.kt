package org.monogram.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.ComposerStyle
import org.monogram.core.ui.loading.MonogramBusyIconButton

/** Shared geometry for the real composer and its settings preview. The caller owns text input. */
@Composable
fun ChatComposerLayout(
    style: ComposerStyle,
    showEmoji: Boolean,
    attachEnabled: Boolean,
    emojiEnabled: Boolean,
    sendEnabled: Boolean,
    sending: Boolean,
    editing: Boolean,
    attachDescription: String,
    emojiDescription: String,
    sendDescription: String,
    onAttach: () -> Unit,
    onEmoji: () -> Unit,
    onSend: () -> Unit,
    topTrailing: (@Composable BoxScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
    field: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val ios = style == ComposerStyle.IOS
    val sendColor by animateColorAsState(
        if (sendEnabled) scheme.primary else scheme.surfaceContainerHighest,
        tween(180), label = "composerSendColor",
    )
    val corner by animateDpAsState(if (ios) 26.dp else 16.dp, tween(220), label = "composerCorner")
    val shape = RoundedCornerShape(corner)
    val attach: @Composable () -> Unit = {
        IconButton(onClick = onAttach, enabled = attachEnabled, modifier = Modifier.size(48.dp)) {
            Icon(if (ios) Icons.Outlined.Add else Icons.Outlined.AttachFile, attachDescription)
        }
    }
    Row(
        modifier = modifier.fillMaxWidth().animateContentSize(tween(220)),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (ios) attach()
        Surface(
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = shape,
            color = animateColorAsState(
                if (ios) scheme.surface.copy(alpha = 0.58f) else scheme.surfaceContainerHighest,
                tween(220), label = "composerSurface",
            ).value,
        ) {
            Box {
                Row(verticalAlignment = Alignment.Bottom) {
                    if (!ios) attach()
                    Box(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .padding(start = if (ios) 14.dp else 0.dp, end = if (showEmoji) 6.dp else 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { field() }
                    if (showEmoji) {
                        IconButton(
                            onClick = onEmoji,
                            enabled = emojiEnabled,
                            modifier = Modifier.padding(end = 4.dp).size(44.dp),
                        ) {
                            Icon(Icons.Outlined.EmojiEmotions, emojiDescription)
                        }
                    }
                }
                topTrailing?.invoke(this)
            }
        }
        MonogramBusyIconButton(
            onClick = onSend,
            busy = sending,
            enabled = sendEnabled,
            modifier = Modifier.size(48.dp),
            shape = if (style == ComposerStyle.Material) RoundedCornerShape(16.dp) else CircleShape,
            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                containerColor = sendColor,
                contentColor = if (sendEnabled) scheme.onPrimary else scheme.onSurfaceVariant,
                disabledContainerColor = sendColor,
                disabledContentColor = if (sending) scheme.onPrimary else scheme.onSurfaceVariant,
            ),
            status = sendDescription,
            icon = {
                Icon(
                    when {
                        editing -> Icons.Outlined.Check
                        ios -> Icons.Outlined.ArrowUpward
                        else -> Icons.AutoMirrored.Outlined.Send
                    },
                    sendDescription,
                )
            },
        )
    }
}
