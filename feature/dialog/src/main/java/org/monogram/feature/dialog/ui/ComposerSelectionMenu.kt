package org.monogram.feature.dialog.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.monogram.core.ui.menu.AppMenuDefaults
import org.monogram.core.ui.menu.AppMenuItem
import org.monogram.feature.dialog.R

internal object HiddenTextToolbar : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden
    override fun hide() = Unit
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = Unit
}

internal object HiddenTextContextMenu : TextContextMenuProvider {
    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) = Unit
}

private enum class ComposerMenuPage { Actions, Format }

@Composable
internal fun ComposerSelectionMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    canPaste: Boolean,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onWrap: (String, String) -> Unit,
    onQuote: () -> Unit,
) {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = visible
    var page by remember { mutableStateOf(ComposerMenuPage.Actions) }
    LaunchedEffect(visible) {
        if (!visible) page = ComposerMenuPage.Actions
    }
    if (!visibleState.currentState && !visibleState.targetState) return
    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
    val itemColors = MenuDefaults.itemColors(
        textColor = MaterialTheme.colorScheme.onSurface,
        leadingIconColor = iconTint,
        trailingIconColor = iconTint,
        disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        disabledLeadingIconColor = iconTint.copy(alpha = 0.38f),
        disabledTrailingIconColor = iconTint.copy(alpha = 0.38f),
    )
    Popup(
        popupPositionProvider = rememberComposerMenuPosition(),
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = true,
            clippingEnabled = false,
        ),
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(tween(160)) +
                scaleIn(
                    initialScale = 0.92f,
                    animationSpec = tween(180),
                    transformOrigin = TransformOrigin(0.08f, 1f),
                ) +
                slideInVertically(tween(180)) { it / 10 },
            exit = fadeOut(tween(140)) +
                scaleOut(
                    targetScale = 0.94f,
                    animationSpec = tween(140),
                    transformOrigin = TransformOrigin(0.08f, 1f),
                ) +
                slideOutVertically(tween(140)) { it / 12 },
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(min = AppMenuDefaults.MinWidth, max = AppMenuDefaults.MaxWidth)
                    .heightIn(max = 320.dp),
                shape = AppMenuDefaults.ContainerShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        val forward = targetState == ComposerMenuPage.Format
                        val enter = slideInHorizontally(tween(200)) {
                            if (forward) it / 4 else -it / 4
                        } + fadeIn(tween(160))
                        val exit = slideOutHorizontally(tween(200)) {
                            if (forward) -it / 4 else it / 4
                        } + fadeOut(tween(120))
                        (enter togetherWith exit).using(SizeTransform(clip = false) { _, _ -> tween(200) })
                    },
                    label = "composerMenuPage",
                ) { current ->
                    Column(
                        modifier = Modifier
                            .padding(AppMenuDefaults.ContainerPadding)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(AppMenuDefaults.GroupGap),
                    ) {
                        when (current) {
                            ComposerMenuPage.Actions -> {
                                ComposerMenuRow(
                                    label = stringResource(R.string.dialog_copy),
                                    icon = Icons.Outlined.ContentCopy,
                                    colors = itemColors,
                                    tint = iconTint,
                                    onClick = onCopy,
                                )
                                ComposerMenuRow(
                                    label = stringResource(R.string.dialog_cut),
                                    icon = Icons.Outlined.ContentCut,
                                    colors = itemColors,
                                    tint = iconTint,
                                    onClick = onCut,
                                )
                                ComposerMenuRow(
                                    label = stringResource(R.string.dialog_paste),
                                    icon = Icons.Outlined.ContentPaste,
                                    colors = itemColors,
                                    tint = iconTint,
                                    enabled = canPaste,
                                    onClick = onPaste,
                                )
                                ComposerMenuRow(
                                    label = stringResource(R.string.dialog_select_all),
                                    icon = Icons.Outlined.SelectAll,
                                    colors = itemColors,
                                    tint = iconTint,
                                    onClick = onSelectAll,
                                )
                                HorizontalDivider()
                                AppMenuItem(
                                    text = stringResource(R.string.dialog_format),
                                    icon = Icons.Outlined.TextFormat,
                                    trailing = {
                                        Icon(
                                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = iconTint,
                                        )
                                    },
                                    onClick = { page = ComposerMenuPage.Format },
                                )
                            }
                            ComposerMenuPage.Format -> {
                                AppMenuItem(
                                    text = stringResource(R.string.dialog_format_back),
                                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                    onClick = { page = ComposerMenuPage.Actions },
                                )
                                HorizontalDivider()
                                ComposerMenuRow(
                                    label = stringResource(R.string.dialog_format_bold),
                                    icon = Icons.Outlined.FormatBold,
                                    colors = itemColors,
                                    tint = iconTint,
                                    onClick = { onWrap("**", "**") },
                                )
                                ComposerMenuRow(
                                    label = stringResource(R.string.dialog_format_italic),
                                    icon = Icons.Outlined.FormatItalic,
                                    colors = itemColors,
                                    tint = iconTint,
                                    onClick = { onWrap("*", "*") },
                                )
                                ComposerMenuRow(
                                    label = stringResource(R.string.dialog_format_code),
                                    icon = Icons.Outlined.Code,
                                    colors = itemColors,
                                    tint = iconTint,
                                    onClick = { onWrap("`", "`") },
                                )
                                ComposerMenuRow(
                                    label = stringResource(R.string.dialog_format_strike),
                                    icon = Icons.Outlined.FormatStrikethrough,
                                    colors = itemColors,
                                    tint = iconTint,
                                    onClick = { onWrap("~~", "~~") },
                                )
                                ComposerMenuRow(
                                    label = stringResource(R.string.dialog_format_spoiler),
                                    icon = Icons.Outlined.VisibilityOff,
                                    colors = itemColors,
                                    tint = iconTint,
                                    onClick = { onWrap("||", "||") },
                                )
                                ComposerMenuRow(
                                    label = stringResource(R.string.dialog_format_link),
                                    icon = Icons.Outlined.Link,
                                    colors = itemColors,
                                    tint = iconTint,
                                    onClick = { onWrap("[", "](https://)") },
                                )
                                ComposerMenuRow(
                                    label = stringResource(R.string.dialog_format_quote),
                                    icon = Icons.Outlined.FormatQuote,
                                    colors = itemColors,
                                    tint = iconTint,
                                    onClick = onQuote,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberComposerMenuPosition(): PopupPositionProvider {
    val density = LocalDensity.current
    val margin = with(density) { 12.dp.roundToPx() }
    val gap = with(density) { 8.dp.roundToPx() }
    val statusTop = WindowInsets.statusBars.getTop(density)
    return remember(margin, gap, statusTop) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = if (layoutDirection == LayoutDirection.Ltr) {
                    anchorBounds.left + margin
                } else {
                    anchorBounds.right - popupContentSize.width - margin
                }
                val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
                val y = (anchorBounds.top - popupContentSize.height - gap)
                    .coerceAtLeast(statusTop + margin)
                return IntOffset(x.coerceIn(margin, maxX), y)
            }
        }
    }
}

@Composable
private fun ComposerMenuRow(
    label: String,
    icon: ImageVector,
    colors: MenuItemColors?,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    AppMenuItem(text = label, icon = icon, onClick = onClick, enabled = enabled)
}
