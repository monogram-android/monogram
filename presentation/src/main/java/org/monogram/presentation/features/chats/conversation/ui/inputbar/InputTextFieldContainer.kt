package org.monogram.presentation.features.chats.conversation.ui.inputbar

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.BotCommandModel
import org.monogram.domain.models.BotMenuButtonModel
import org.monogram.domain.models.StickerModel
import org.monogram.presentation.R
import org.monogram.presentation.core.util.LocalTabletInterfaceEnabled

@Composable
internal fun InputTextFieldContainer(
    uiState: InputTextFieldUiState,
    onValueChange: (TextFieldValue) -> Unit,
    onRichTextValueChange: (TextFieldValue) -> Unit = onValueChange,
    onStickerMenuToggle: () -> Unit,
    onAttachClick: () -> Unit,
    onShowBotCommands: () -> Unit,
    onOpenMiniApp: (String, String) -> Unit,
    knownCustomEmojis: Map<Long, StickerModel>,
    emojiFontFamily: FontFamily,
    focusRequester: FocusRequester,
    pendingMediaPaths: List<String>,
    pendingDocumentPaths: List<String>,
    onPasteImages: (List<Uri>) -> Unit = {},
    onFocus: () -> Unit = {},
    onOpenFullScreenEditor: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val onAttachClickState by rememberUpdatedState(onAttachClick)
    val onStickerMenuToggleState by rememberUpdatedState(onStickerMenuToggle)
    val onOpenFullScreenEditorState by rememberUpdatedState(onOpenFullScreenEditor)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        val isTablet =
            LocalConfiguration.current.screenWidthDp >= 600 && LocalTabletInterfaceEnabled.current

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
        ) {
            AnimatedContent(
                targetState = uiState.canAttachMedia,
                transitionSpec = {
                    (fadeIn() + scaleIn(initialScale = 0.85f)).togetherWith(
                        fadeOut() + scaleOut(targetScale = 0.85f)
                    ).using(SizeTransform(clip = false))
                },
                label = "AttachIconInFieldVisibility"
            ) { showAttach ->
                if (showAttach) {
                    IconButton(
                        onClick = { onAttachClickState() },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AddCircleOutline,
                            contentDescription = stringResource(R.string.cd_attach),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }

            InputTextField(
                textValue = uiState.textValue,
                onValueChange = onValueChange,
                onRichTextValueChange = onRichTextValueChange,
                canWriteText = uiState.canWriteText,
                knownCustomEmojis = knownCustomEmojis,
                emojiFontFamily = emojiFontFamily,
                focusRequester = focusRequester,
                pendingMediaPaths = pendingMediaPaths,
                pendingDocumentPaths = pendingDocumentPaths,
                canPasteMediaFromClipboard = uiState.canPasteMediaFromClipboard,
                onPasteImages = onPasteImages,
                onFocus = onFocus,
                modifier = Modifier.weight(1f)
            )

            AnimatedVisibility(
                visible = uiState.isBot && uiState.canShowBotActions && uiState.textValue.text.isEmpty(),
                enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End)
            ) {
                BotInputActions(
                    botMenuButton = uiState.botMenuButton,
                    botCommands = uiState.botCommands,
                    onShowBotCommands = onShowBotCommands,
                    onOpenMiniApp = onOpenMiniApp
                )
            }

            if (uiState.canWriteText) {
                AnimatedVisibility(
                    visible = uiState.showExpandEditorAction || isTablet,
                    enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                    exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End)
                ) {
                    IconButton(
                        onClick = { onOpenFullScreenEditorState() },
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.action_open_fullscreen_editor),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = uiState.canSendStickers,
                enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End)
            ) {
                IconButton(
                    onClick = { onStickerMenuToggleState() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (uiState.isStickerMenuVisible) Icons.Default.Keyboard else Icons.Outlined.EmojiEmotions,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BotInputActions(
    botMenuButton: BotMenuButtonModel,
    botCommands: List<BotCommandModel>,
    onShowBotCommands: () -> Unit,
    onOpenMiniApp: (String, String) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (botMenuButton is BotMenuButtonModel.WebApp) {
            IconButton(
                onClick = { onOpenMiniApp(botMenuButton.url, botMenuButton.text) },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Language,
                    contentDescription = botMenuButton.text,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (botCommands.isNotEmpty()) {
            IconButton(
                onClick = onShowBotCommands,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = stringResource(R.string.bot_commands),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}