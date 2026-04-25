package org.monogram.presentation.features.chats.conversation.ui.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.MessageModel

@Composable
fun MessageViaBotAttribution(
    msg: MessageModel,
    isOutgoing: Boolean,
    onViaBotClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val botUsername = msg.viaBotName?.takeIf { it.isNotBlank() } ?: return
    val textColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(
                text = "via",
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.65f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "@$botUsername",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onViaBotClick(botUsername) }
            )
        }
    }
}
