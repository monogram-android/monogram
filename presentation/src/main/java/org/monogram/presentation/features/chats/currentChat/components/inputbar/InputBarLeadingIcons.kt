package org.monogram.presentation.features.chats.currentChat.components.inputbar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.R

@Composable
fun InputBarLeadingIcons(
    editingMessage: MessageModel?,
    pendingMediaPaths: List<String>,
    pendingDocumentPaths: List<String>,
    canSendMedia: Boolean,
    onAttachClick: () -> Unit
) {
    val canAttachMedia =
        editingMessage == null && pendingMediaPaths.isEmpty() && pendingDocumentPaths.isEmpty() && canSendMedia

    AnimatedContent(
        targetState = canAttachMedia,
        transitionSpec = {
            (fadeIn() + scaleIn(initialScale = 0.85f)).togetherWith(
                fadeOut() + scaleOut(targetScale = 0.85f)
            ).using(SizeTransform(clip = false))
        },
        label = "AttachIconVisibility"
    ) { showAttach ->
        if (showAttach) {
            IconButton(onClick = onAttachClick) {
                Icon(
                    imageVector = Icons.Outlined.AddCircleOutline,
                    contentDescription = stringResource(R.string.cd_attach),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Spacer(modifier = Modifier.width(12.dp))
        }
    }
}
