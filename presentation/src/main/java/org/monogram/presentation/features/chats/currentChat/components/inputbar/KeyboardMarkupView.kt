package org.monogram.presentation.features.chats.currentChat.components.inputbar

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.KeyboardButtonModel
import org.monogram.domain.models.KeyboardButtonType
import org.monogram.domain.models.ReplyMarkupModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeyboardMarkupView(
    markup: ReplyMarkupModel.ShowKeyboard,
    onButtonClick: (KeyboardButtonModel) -> Unit,
    onOpenMiniApp: (String, String) -> Unit = { _, _ -> }
) {
    val rows = remember(markup) { markup.rows.filter { it.isNotEmpty() } }
    if (rows.isEmpty()) return

    val clipboardManager = LocalClipboard.current
    val nativeClipboard = clipboardManager.nativeClipboard
    val haptic = LocalHapticFeedback.current

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .background(MaterialTheme.colorScheme.surface),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(8.dp)
    ) {
        items(rows) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { button ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .combinedClickable(
                                onClick = {
                                    if (button.type is KeyboardButtonType.WebApp) {
                                        onOpenMiniApp((button.type as KeyboardButtonType.WebApp).url, button.text)
                                    } else {
                                        onButtonClick(button)
                                    }
                                },
                                onLongClick = {
                                    val dataToCopy = when (val type = button.type) {
                                        is KeyboardButtonType.WebApp -> type.url
                                        else -> button.text
                                    }

                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                                    nativeClipboard.setPrimaryClip(
                                        ClipData.newPlainText("", (AnnotatedString(dataToCopy)))
                                    )
                                }
                            ),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = button.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}