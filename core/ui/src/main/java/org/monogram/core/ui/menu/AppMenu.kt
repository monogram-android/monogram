package org.monogram.core.ui.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import org.monogram.core.ui.components.PeerAvatar
import java.io.File

object AppMenuDefaults {
    val ContainerShape = RoundedCornerShape(16.dp)
    val ItemShape = RoundedCornerShape(12.dp)
    val ContainerPadding = 8.dp
    val ItemHorizontalPadding = 12.dp
    val ItemMinHeight = 48.dp
    val IconSize = 24.dp
    val IconLabelGap = 12.dp
    val GroupGap = 8.dp

    val SurfaceGap = 8.dp
    val MinWidth = 112.dp
    val MaxWidth = 280.dp
    const val ScrimAlpha = 0.32f
}

object AppMenuMotion {
    private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val OpenSpec: FiniteAnimationSpec<Float> = tween(220, easing = EmphasizedDecelerate)
    val CloseSpec: FiniteAnimationSpec<Float> = tween(120, easing = EmphasizedDecelerate)
    const val InitialScale = 0.8f
}

internal fun appMenuStateLayerAlpha(
    pressed: Boolean,
    focused: Boolean,
    hovered: Boolean,
    enabled: Boolean,
): Float = when {
    !enabled -> 0f
    pressed -> 0.12f
    focused -> 0.10f
    hovered -> 0.08f
    else -> 0f
}

@Composable
fun AppMenuSurface(
    modifier: Modifier = Modifier,
    width: Dp? = null,
    scrollState: ScrollState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.then(
            if (width != null) {
                Modifier.width(width)
            } else {
                Modifier.widthIn(AppMenuDefaults.MinWidth, AppMenuDefaults.MaxWidth)
            },
        ),
        shape = AppMenuDefaults.ContainerShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(AppMenuDefaults.ContainerPadding)
                .then(if (scrollState != null) Modifier.verticalScroll(scrollState) else Modifier),
            verticalArrangement = if (scrollState != null) {
                Arrangement.Top
            } else {
                Arrangement.spacedBy(AppMenuDefaults.GroupGap)
            },
            content = content,
        )
    }
}

@Composable
fun AppMenuGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), content = content)
}

@Composable
fun AppMenuDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = 4.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

data class AppMenuAvatar(val label: String, val imageFile: File? = null)

@Composable
fun AppMenuAvatarStack(
    avatars: List<AppMenuAvatar>,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    max: Int = 3,
) {
    val shown = remember(avatars, max) { avatars.take(max) }
    if (shown.isEmpty()) return
    val overlap = size * 0.3f
    val stackWidth = size + overlap * (shown.size - 1)
    Box(modifier = modifier.width(stackWidth).heightIn(min = size)) {
        shown.forEachIndexed { index, avatar ->
            PeerAvatar(
                title = avatar.label,
                imageFile = avatar.imageFile,
                size = size,
                modifier = Modifier
                    .offset(x = overlap * index)
                    .border(1.5.dp, MaterialTheme.colorScheme.surfaceContainer, CircleShape),
            )
        }
    }
}

@Composable
fun AppMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    trailingText: String? = null,
    supportingText: String? = null,
    leadingSpinner: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    contentDescription: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val overlayAlpha = appMenuStateLayerAlpha(pressed, focused, hovered, enabled)
    val disabledAlpha = if (enabled) 1f else 0.38f
    val labelColor = when {
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }.copy(alpha = disabledAlpha)
    val iconColor = when {
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }.copy(alpha = disabledAlpha)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AppMenuDefaults.ItemMinHeight)
            .clip(AppMenuDefaults.ItemShape)
            .background(
                if (overlayAlpha > 0f) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = overlayAlpha)
                } else {
                    Color.Transparent
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = AppMenuDefaults.ItemHorizontalPadding)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> {
                leading()
                Spacer(Modifier.width(AppMenuDefaults.IconLabelGap))
            }

            leadingSpinner -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(AppMenuDefaults.IconSize * 0.75f),
                    strokeWidth = 2.dp,
                    color = iconColor,
                )
                Spacer(Modifier.width(AppMenuDefaults.IconLabelGap))
            }

            icon != null -> {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(AppMenuDefaults.IconSize),
                )
                Spacer(Modifier.width(AppMenuDefaults.IconLabelGap))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingText != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha),
                maxLines = 1,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun AppMenuScrim(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = AppMenuDefaults.ScrimAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    )
}

@Composable
fun AppMenuScrimPopup(visible: Boolean, onDismiss: () -> Unit) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = visible
    if (!transition.currentState && !transition.targetState) return
    androidx.compose.ui.window.Popup(
        popupPositionProvider = remember {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: androidx.compose.ui.unit.IntRect,
                    windowSize: androidx.compose.ui.unit.IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: androidx.compose.ui.unit.IntSize,
                ): IntOffset = IntOffset.Zero
            }
        },
        properties = androidx.compose.ui.window.PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
        onDismissRequest = onDismiss,
    ) {
        AnimatedVisibility(
            visibleState = transition,
            enter = fadeIn(AppMenuMotion.OpenSpec),
            exit = fadeOut(AppMenuMotion.CloseSpec),
        ) {
            AppMenuScrim(onClick = onDismiss)
        }
    }
}

@Composable
fun AppMenuPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    touch: androidx.compose.ui.geometry.Offset? = null,
    alignToAnchorEnd: Boolean = false,
    topInset: Dp = AppMenuSystemBarInset,
    bottomInset: Dp = AppMenuSystemBarInset,
    scrim: Boolean = false,
    content: @Composable () -> Unit,
) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = expanded
    if (!transition.currentState && !transition.targetState) return
    val placement = remember { AppMenuPlacementState() }
    if (scrim) AppMenuScrimPopup(visible = expanded, onDismiss = onDismiss)
    androidx.compose.ui.window.Popup(
        popupPositionProvider = rememberAppMenuPositionProvider(
            touch = touch,
            alignToAnchorEnd = alignToAnchorEnd,
            topInset = topInset,
            bottomInset = bottomInset,
            placementState = placement,
        ),
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(
            focusable = true,
            clippingEnabled = false,
        ),
    ) {
        val origin = TransformOrigin(
            pivotFractionX = if (alignToAnchorEnd) 1f else 0f,
            pivotFractionY = if (placement.growth == AppMenuGrowth.Above) 1f else 0f,
        )
        AnimatedVisibility(
            visibleState = transition,
            enter = fadeIn(AppMenuMotion.OpenSpec) +
                scaleIn(AppMenuMotion.OpenSpec, initialScale = AppMenuMotion.InitialScale, transformOrigin = origin),
            exit = fadeOut(AppMenuMotion.CloseSpec) +
                scaleOut(AppMenuMotion.CloseSpec, targetScale = AppMenuMotion.InitialScale, transformOrigin = origin),
            modifier = modifier,
        ) {
            content()
        }
    }
}
