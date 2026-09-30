package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Toc
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.core.models.InstantViewHeading
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.ExpressiveDefaults
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.core.ui.components.SheetPanelHost
import org.monogram.feature.dialog.R

@Composable
private fun IvSheetHeader(
    title: String,
    meta: String? = null,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!meta.isNullOrBlank()) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = meta,
                style = ExpressiveDefaults.tabularLabel(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun InstantViewTextSizeSheet(
    visible: Boolean,
    fontSize: Int,
    previewText: String,
    onFontSizeChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetPanelHost(visible) { shown, onExited ->
        AppModalSheet(
            onDismissRequest = onDismiss,
            visible = shown,
            onExited = onExited,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                IvSheetHeader(
                    title = stringResource(R.string.dialog_instant_view_font),
                    meta = fontSize.toString(),
                    icon = Icons.Outlined.FormatSize,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { onFontSizeChange(fontSize - 1) },
                        enabled = fontSize > AppearanceSettings.MIN_IV_FONT_SIZE,
                    ) {
                        Icon(
                            Icons.Outlined.TextDecrease,
                            contentDescription = stringResource(R.string.dialog_instant_view_font_smaller),
                        )
                    }
                    Slider(
                        value = fontSize.toFloat(),
                        onValueChange = { onFontSizeChange(it.toInt()) },
                        valueRange = AppearanceSettings.MIN_IV_FONT_SIZE.toFloat()..
                                AppearanceSettings.MAX_IV_FONT_SIZE.toFloat(),
                        steps = AppearanceSettings.MAX_IV_FONT_SIZE -
                                AppearanceSettings.MIN_IV_FONT_SIZE - 1,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { onFontSizeChange(fontSize + 1) },
                        enabled = fontSize < AppearanceSettings.MAX_IV_FONT_SIZE,
                    ) {
                        Icon(
                            Icons.Outlined.TextIncrease,
                            contentDescription = stringResource(R.string.dialog_instant_view_font_larger),
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(IvSpacing.CardCorner),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        text = previewText,
                        modifier = Modifier.padding(16.dp),
                        style = ivBodyStyle(ivScale(fontSize)),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onFontSizeChange(AppearanceSettings.DEFAULT_IV_FONT_SIZE) },
                        enabled = fontSize != AppearanceSettings.DEFAULT_IV_FONT_SIZE,
                    ) {
                        Text(stringResource(R.string.dialog_instant_view_font_reset))
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.dialog_instant_view_done))
                    }
                }
            }
        }
    }
}

@Composable
internal fun InstantViewOutlineSheet(
    visible: Boolean,
    outline: List<InstantViewHeading>,
    readingMinutes: Int,
    activeIndex: Int?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetPanelHost(visible) { shown, onExited ->
        AppModalSheet(
            onDismissRequest = onDismiss,
            visible = shown,
            onExited = onExited,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                IvSheetHeader(
                    title = stringResource(R.string.dialog_instant_view_contents),
                    meta = if (readingMinutes > 0) {
                        stringResource(R.string.dialog_instant_view_min_read, readingMinutes)
                    } else {
                        null
                    },
                    icon = Icons.AutoMirrored.Outlined.Toc,
                )
                if (outline.isEmpty()) {
                    Text(
                        text = stringResource(R.string.dialog_instant_view_contents_empty),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        state = rememberLazyListState(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        item(key = "iv-outline-top") {
                            OutlineRow(
                                text = stringResource(R.string.dialog_instant_view_beginning),
                                level = 0,
                                active = activeIndex == null,
                                onClick = { onSelect(0) },
                            )
                        }
                        items(
                            outline,
                            key = { heading -> "iv-outline-${heading.index}" }) { heading ->
                            OutlineRow(
                                text = heading.text,
                                level = heading.level,
                                active = heading.index == activeIndex,
                                onClick = { onSelect(heading.index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutlineRow(
    text: String,
    level: Int,
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(
                start = (12 + level.coerceIn(0, 3) * 16).dp,
                end = 12.dp,
                top = 12.dp,
                bottom = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (active || level == 0) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun InstantViewArticleTextSheet(
    visible: Boolean,
    text: String,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetPanelHost(visible) { shown, onExited ->
        AppModalSheet(
            onDismissRequest = onDismiss,
            visible = shown,
            onExited = onExited,
        ) {
            var field by remember(text) {
                mutableStateOf(TextFieldValue(text = text, selection = TextRange(0, text.length)))
            }
            val allSelected = field.selection.min == 0 && field.selection.max >= text.length
            val selection = field.selection.takeIf { !it.collapsed }?.let { range ->
                text.substring(
                    range.min.coerceIn(0, text.length),
                    range.max.coerceIn(0, text.length)
                )
            }.orEmpty().ifEmpty { text }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                IvSheetHeader(
                    title = stringResource(R.string.dialog_instant_view_text),
                    meta = stringResource(R.string.dialog_instant_view_chars, text.length),
                    icon = Icons.Outlined.SelectAll,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .heightIn(min = 180.dp, max = 380.dp)
                        .clip(RoundedCornerShape(IvSpacing.CardCorner))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    BasicTextField(
                        value = field,
                        onValueChange = { updated -> field = updated.copy(text = text) },
                        readOnly = true,
                        textStyle = ivBodyStyle(1f, size = 15f),
                        cursorBrush = SolidColor(Color.Transparent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            field = field.copy(
                                selection = if (allSelected) {
                                    TextRange.Zero
                                } else {
                                    TextRange(0, text.length)
                                },
                            )
                        },
                    ) {
                        Text(
                            if (allSelected) {
                                stringResource(R.string.dialog_instant_view_clear_selection)
                            } else {
                                stringResource(R.string.dialog_instant_view_select_all)
                            },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = { onCopy(selection) }) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.dialog_instant_view_copy))
                    }
                    Button(onClick = { onShare(selection) }) {
                        Icon(
                            Icons.Outlined.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.dialog_instant_view_share))
                    }
                }
            }
        }
    }
}
