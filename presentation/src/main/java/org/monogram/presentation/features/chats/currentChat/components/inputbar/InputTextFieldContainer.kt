package org.monogram.presentation.features.chats.currentChat.components.inputbar

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.BotCommandModel
import org.monogram.domain.models.BotMenuButtonModel
import org.monogram.domain.models.StickerModel
import org.monogram.presentation.R

@Composable
fun InputTextFieldContainer(
    textValue: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    isBot: Boolean,
    botMenuButton: BotMenuButtonModel,
    botCommands: List<BotCommandModel>,
    canSendStickers: Boolean,
    canWriteText: Boolean,
    isStickerMenuVisible: Boolean,
    onStickerMenuToggle: () -> Unit,
    onShowBotCommands: () -> Unit,
    onOpenMiniApp: (String, String) -> Unit,
    knownCustomEmojis: Map<Long, StickerModel>,
    emojiFontFamily: FontFamily,
    focusRequester: FocusRequester,
    pendingMediaPaths: List<String>,
    onFocus: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
        ) {
            val showBotActions = remember(isBot, textValue.text) { isBot && textValue.text.isEmpty() }
            if (showBotActions) {
                BotInputActions(
                    botMenuButton = botMenuButton,
                    botCommands = botCommands,
                    canSendStickers = canSendStickers,
                    isStickerMenuVisible = isStickerMenuVisible,
                    onStickerMenuToggle = onStickerMenuToggle,
                    onShowBotCommands = onShowBotCommands,
                    onOpenMiniApp = onOpenMiniApp
                )
            } else if (canSendStickers) {
                IconButton(
                    onClick = onStickerMenuToggle,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (isStickerMenuVisible) Icons.Default.Keyboard else Icons.Outlined.EmojiEmotions,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(8.dp))
            }

            InputTextField(
                textValue = textValue,
                onValueChange = onValueChange,
                canWriteText = canWriteText,
                knownCustomEmojis = knownCustomEmojis,
                emojiFontFamily = emojiFontFamily,
                focusRequester = focusRequester,
                pendingMediaPaths = pendingMediaPaths,
                onFocus = onFocus,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BotInputActions(
    botMenuButton: BotMenuButtonModel,
    botCommands: List<BotCommandModel>,
    canSendStickers: Boolean,
    isStickerMenuVisible: Boolean,
    onStickerMenuToggle: () -> Unit,
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
        } else if (botMenuButton !is BotMenuButtonModel.WebApp && canSendStickers) {
            IconButton(
                onClick = onStickerMenuToggle,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (isStickerMenuVisible) Icons.Default.Keyboard else Icons.Outlined.EmojiEmotions,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}