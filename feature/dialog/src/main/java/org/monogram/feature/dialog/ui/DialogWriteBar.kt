@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package org.monogram.feature.dialog.ui

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuDropdownProvider
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.components.ChatComposerLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import org.monogram.core.models.UploadItem
import org.monogram.core.ui.ExpressiveDefaults
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.R

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun DialogWriteBar(
    composer: TextFieldValue,
    onComposerChange: (TextFieldValue) -> Unit,
    sending: Boolean,
    canSendPlain: Boolean,
    canSendPhotos: Boolean,
    isChannel: Boolean,
    editing: Boolean,
    editingBody: String,
    replyBody: String?,
    pendingAttach: List<UploadItem>,
    hasFailed: Boolean,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onEmoji: () -> Unit = {},
    onRetryFailed: () -> Unit,
    onCancelEdit: () -> Unit,
    onClearReply: () -> Unit,
    onClearAttach: () -> Unit,
    onReceiveMedia: (List<Uri>, TransferableContent?) -> Unit = { _, _ -> },
    onOpenEditor: () -> Unit = {},
    onSelectionMenuVisibilityChange: (Boolean) -> Unit = {},
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appearance by AppearanceSettings.state.collectAsStateWithLifecycle()
    val canCompose = canSendPlain || canSendPhotos
    if (!canCompose) {
        RestrictionBar(
            text = when {
                isChannel -> stringResource(R.string.dialog_channel_readonly)
                else -> stringResource(R.string.dialog_cant_send)
            },
            modifier = modifier,
        )
        return
    }

    val sendEnabled = when {
        sending && editing -> false
        editing -> composer.text.isNotBlank()
        else -> (composer.text.isNotBlank() && canSendPlain) ||
            (pendingAttach.isNotEmpty() && canSendPhotos)
    }
    val scheme = MaterialTheme.colorScheme

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = scheme.surfaceContainer,
        contentColor = scheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
                if (hasFailed) {
                    TextButton(
                        onClick = onRetryFailed,
                        shapes = ExpressiveDefaults.buttonShapesFor(ButtonDefaults.MinHeight),
                    ) {
                        Text(stringResource(R.string.dialog_retry))
                    }
                }
                if (editing) {
                    WriteBarContext(
                        title = stringResource(R.string.dialog_editing),
                        body = editingBody,
                        onClear = onCancelEdit,
                    )
                }
                replyBody?.let { body ->
                    WriteBarContext(
                        title = stringResource(R.string.dialog_replying),
                        body = body,
                        onClear = onClearReply,
                    )
                }
                if (pendingAttach.isNotEmpty()) {
                    WriteBarContext(
                        title = when {
                            pendingAttach.size > 1 -> stringResource(R.string.dialog_attach)
                            pendingAttach.first().kind == "video" -> stringResource(R.string.dialog_media_video)
                            pendingAttach.first().kind == "document" -> stringResource(R.string.dialog_media_document)
                            else -> stringResource(R.string.dialog_media_photo)
                        },
                        body = if (pendingAttach.size > 1) pendingAttach.size.toString() else "",
                        onClear = onClearAttach,
                        leading = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                pendingAttach.take(4).forEach { item ->
                                    AsyncImage(
                                        model = File(item.path),
                                        contentDescription = stringResource(R.string.dialog_attach),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(MaterialTheme.shapes.extraSmall),
                                    )
                                }
                            }
                        },
                        clearDescription = stringResource(R.string.dialog_attach_remove),
                    )
                }
                val showExpand = shouldShowFullScreenEditor(composer.text)
                ChatComposerLayout(
                    style = appearance.composerStyle,
                    showEmoji = appearance.showEmojiButton,
                    attachEnabled = !editing && (canSendPhotos || canSendPlain),
                    emojiEnabled = !editing && canSendPlain,
                    sendEnabled = sendEnabled,
                    sending = sending,
                    editing = editing,
                    attachDescription = stringResource(R.string.dialog_attach),
                    emojiDescription = stringResource(R.string.dialog_emoji_open),
                    sendDescription = stringResource(if (editing) R.string.dialog_editing else R.string.dialog_send),
                    onAttach = onAttach,
                    onEmoji = onEmoji,
                    onSend = onSend,
                    topTrailing = if (showExpand) {
                        {
                            IconButton(
                                onClick = onOpenEditor,
                                enabled = editing || canSendPlain,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 0.dp, end = 4.dp)
                                    .size(44.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Fullscreen,
                                    contentDescription = stringResource(R.string.dialog_markdown_expand),
                                    tint = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else null,
                ) {
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (composer.text.isEmpty()) {
                            Text(
                                text = hint ?: stringResource(R.string.dialog_message_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 2.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                            )
                        }
                        val markdownPreview = remember(scheme.primary) {
                            markdownOutputTransformation(scheme.primary)
                        }
                        val textFieldState = rememberTextFieldState(
                            initialText = composer.text,
                            initialSelection = composer.selection,
                        )
                        val latestComposer by rememberUpdatedState(composer)
                        val latestComposerChange by rememberUpdatedState(onComposerChange)
                        val latestReceiveMedia by rememberUpdatedState(onReceiveMedia)
                        LaunchedEffect(textFieldState) {
                            snapshotFlow {
                                TextFieldValue(
                                    text = textFieldState.text.toString(),
                                    selection = textFieldState.selection,
                                    composition = textFieldState.composition,
                                )
                            }.collect { updated ->
                                if (updated != latestComposer) latestComposerChange(updated)
                            }
                        }
                        LaunchedEffect(composer.text, composer.selection) {
                            val fieldText = textFieldState.text.toString()
                            val fieldSelection = textFieldState.selection
                            if (fieldText == composer.text && fieldSelection == composer.selection) {
                                return@LaunchedEffect
                            }
                            textFieldState.edit {
                                // Replacing an unchanged buffer collapses IME selection, so
                                // the next Delete only removes the last glyph.
                                if (fieldText != composer.text) {
                                    replace(0, length, composer.text)
                                }
                                selection = composer.selection
                            }
                        }
                        val receiveMedia = remember(canSendPhotos, editing) {
                            ReceiveContentListener { content ->
                                if (!canSendPhotos || editing || !content.hasMediaType(MediaType.Image)) {
                                    content
                                } else {
                                    val uris = buildList {
                                        val clip = content.clipEntry.clipData
                                        for (index in 0 until clip.itemCount) {
                                            clip.getItemAt(index).uri?.let(::add)
                                        }
                                    }.distinct().filter { uri ->
                                        runCatching {
                                            context.contentResolver.getType(uri)?.startsWith("image/") == true
                                        }.getOrDefault(false)
                                    }
                                    if (uris.isNotEmpty()) latestReceiveMedia(uris, content)
                                    content.consume { it.uri in uris }
                                }
                            }
                        }
                        val emojiSpans = remember(composer.text) { composerEmojiSpans(composer.text) }
                        val measurer = rememberTextMeasurer()
                        val fieldStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface)
                        var fieldWidth by remember { mutableIntStateOf(0) }
                        val latestSelectionMenuVisibilityChange by rememberUpdatedState(onSelectionMenuVisibilityChange)
                        val textToolbar = remember {
                            ComposerTextToolbar { latestSelectionMenuVisibilityChange(it) }
                        }
                        val textContextMenu = remember {
                            ComposerTextContextMenu { latestSelectionMenuVisibilityChange(it) }
                        }
                        CompositionLocalProvider(
                            LocalTextToolbar provides textToolbar,
                            LocalTextContextMenuToolbarProvider provides textContextMenu,
                            LocalTextContextMenuDropdownProvider provides textContextMenu,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 2.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                            ) {
                                BasicTextField(
                                    state = textFieldState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .contentReceiver(receiveMedia)
                                        .onPreviewKeyEvent { event ->
                                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                            if (event.key != Key.Backspace && event.key != Key.Delete) {
                                                return@onPreviewKeyEvent false
                                            }
                                            val range = textFieldState.selection
                                            if (!composerDeleteRemovesSelection(range)) return@onPreviewKeyEvent false
                                            textFieldState.edit {
                                                replace(range.min, range.max, "")
                                                selection = TextRange(range.min)
                                            }
                                            true
                                        }
                                        .onSizeChanged { fieldWidth = it.width },
                                    enabled = editing || canSendPlain,
                                    outputTransformation = markdownPreview,
                                    textStyle = fieldStyle,
                                    cursorBrush = SolidColor(scheme.primary),
                                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Sentences,
                                        keyboardType = KeyboardType.Text,
                                        imeAction = if (appearance.sendByEnter) ImeAction.Send else ImeAction.Default,
                                    ),
                                    onKeyboardAction = {
                                        if (appearance.sendByEnter && sendEnabled) onSend()
                                    },
                                )
                                if (emojiSpans.isNotEmpty() && fieldWidth > 0) {
                                    val collapsed = collapseCustomEmojiMarkdown(composer.text)?.text?.text
                                        ?: composer.text
                                    val layout = measurer.measure(
                                        text = collapsed,
                                        style = fieldStyle,
                                        constraints = Constraints(maxWidth = fieldWidth),
                                    )
                                    emojiSpans.forEach { span ->
                                        val index = span.displayStart.coerceIn(0, (collapsed.length - 1).coerceAtLeast(0))
                                        val box = layout.getBoundingBox(index)
                                        Box(
                                            modifier = Modifier.offset {
                                                IntOffset(box.left.toInt(), box.top.toInt())
                                            },
                                        ) {
                                            CustomEmojiGlyph(
                                                documentId = span.documentId,
                                                size = 20.dp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }
}

@Composable
internal fun RestrictionBar(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WriteBarContext(
    title: String,
    body: String,
    onClear: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    clearDescription: String = stringResource(R.string.dialog_cancel),
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (body.isNotBlank()) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(
            onClick = onClear,
            modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
            shapes = ExpressiveDefaults.iconButtonShapes(),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = clearDescription,
                modifier = Modifier.size(IconButtonDefaults.smallIconSize),
            )
        }
    }
}

@Preview(showBackground = true, name = "Idle light")
@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    name = "Idle dark",
)
@Composable
private fun DialogWriteBarIdlePreview() {
    MonogramTheme(dynamicColor = false) {
        DialogWriteBar(
            composer = TextFieldValue(""),
            onComposerChange = {},
            sending = false,
            canSendPlain = true,
            canSendPhotos = true,
            isChannel = false,
            editing = false,
            editingBody = "",
            replyBody = null,
            pendingAttach = emptyList(),
            hasFailed = false,
            onSend = {},
            onAttach = {},
            onRetryFailed = {},
            onCancelEdit = {},
            onClearReply = {},
            onClearAttach = {},
        )
    }
}

@Preview(showBackground = true, name = "Ready + reply")
@Composable
private fun DialogWriteBarReadyPreview() {
    MonogramTheme(dynamicColor = false) {
        DialogWriteBar(
            composer = TextFieldValue("Hello there"),
            onComposerChange = {},
            sending = false,
            canSendPlain = true,
            canSendPhotos = true,
            isChannel = false,
            editing = false,
            editingBody = "",
            replyBody = "Previous message that is being quoted",
            pendingAttach = emptyList(),
            hasFailed = true,
            onSend = {},
            onAttach = {},
            onRetryFailed = {},
            onCancelEdit = {},
            onClearReply = {},
            onClearAttach = {},
        )
    }
}

@Preview(showBackground = true, name = "Readonly channel")
@Composable
private fun DialogWriteBarRestrictedPreview() {
    MonogramTheme(dynamicColor = false) {
        DialogWriteBar(
            composer = TextFieldValue(""),
            onComposerChange = {},
            sending = false,
            canSendPlain = false,
            canSendPhotos = false,
            isChannel = true,
            editing = false,
            editingBody = "",
            replyBody = null,
            pendingAttach = emptyList(),
            hasFailed = false,
            onSend = {},
            onAttach = {},
            onRetryFailed = {},
            onCancelEdit = {},
            onClearReply = {},
            onClearAttach = {},
        )
    }
}
