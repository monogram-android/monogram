package org.monogram.feature.dialog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.models.Message
import org.monogram.core.ui.menu.AppMenuDefaults
import org.monogram.core.ui.menu.AppMenuGrowth
import org.monogram.core.ui.menu.AppMenuGroup
import org.monogram.core.ui.menu.AppMenuItem
import org.monogram.core.ui.menu.AppMenuMotion
import org.monogram.core.ui.menu.AppMenuSurface
import org.monogram.feature.dialog.R

data class MessageMenuActions(
    val canReply: Boolean,
    val canCopy: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val canForward: Boolean,
    val forwardRestricted: Boolean,
    val canSelectForForwarding: Boolean = canForward,
)

@Composable
fun MessageActionMenu(
    expanded: Boolean,
    message: Message?,
    actions: MessageMenuActions,
    onDismiss: () -> Unit,
    onReply: (Message) -> Unit,
    onCopy: (Message) -> Unit,
    onSelectText: (Message) -> Unit = {},
    onSelectForForwarding: (Message) -> Unit = {},
    onEdit: (Message) -> Unit,
    onDelete: (Message) -> Unit,
    onForward: (Message) -> Unit,
    onReact: ((emoticon: String, documentId: Long) -> Unit)? = null,
    recentReactions: List<org.monogram.core.models.ReactionChoice> = emptyList(),
    outgoing: Boolean = false,
    alignToAnchorEnd: Boolean = outgoing,
    growth: AppMenuGrowth? = null,
    seenByRow: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    visibilityState: MutableTransitionState<Boolean>? = null,
) {
    val visible = visibilityState ?: remember { MutableTransitionState(false) }
    visible.targetState = expanded && message != null
    if (visible.isIdle && !visible.currentState && !visible.targetState) return
    val target = message ?: return
    val origin = TransformOrigin(
        pivotFractionX = if (alignToAnchorEnd) 1f else 0f,
        pivotFractionY = if (growth == AppMenuGrowth.Above) 1f else 0f,
    )
    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(AppMenuMotion.OpenSpec) +
            scaleIn(AppMenuMotion.OpenSpec, initialScale = AppMenuMotion.InitialScale, transformOrigin = origin),
        exit = fadeOut(AppMenuMotion.CloseSpec),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.widthIn(min = 196.dp, max = AppMenuDefaults.MaxWidth),
            horizontalAlignment = if (alignToAnchorEnd) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(AppMenuDefaults.SurfaceGap),
        ) {
            if (onReact != null) {
                ReactionPickerStrip(
                    recent = recentReactions,
                    compact = true,
                    onReact = { emoji, doc ->
                        onReact(emoji, doc)
                        onDismiss()
                    },
                )
            }
            seenByRow?.invoke()
            AppMenuSurface(modifier = Modifier.fillMaxWidth()) {
                AppMenuGroup {
                    if (actions.canReply) {
                        AppMenuItem(
                            text = stringResource(R.string.dialog_reply),
                            icon = Icons.AutoMirrored.Outlined.Reply,
                            onClick = {
                                onReply(target)
                                onDismiss()
                            },
                        )
                    }
                    if (actions.forwardRestricted || actions.canForward) {
                        AppMenuItem(
                            text = stringResource(R.string.dialog_forward),
                            icon = Icons.AutoMirrored.Outlined.Forward,
                            enabled = actions.canForward,
                            contentDescription = if (actions.forwardRestricted && !actions.canForward) {
                                stringResource(R.string.dialog_forward_restricted)
                            } else {
                                null
                            },
                            onClick = {
                                onForward(target)
                                onDismiss()
                            },
                        )
                    }
                    if (actions.canSelectForForwarding) {
                        AppMenuItem(
                            text = stringResource(R.string.dialog_select_message),
                            icon = Icons.Outlined.Checklist,
                            onClick = {
                                onSelectForForwarding(target)
                                onDismiss()
                            },
                        )
                    }
                }
                if (actions.canCopy || actions.canEdit) {
                    AppMenuGroup {
                        if (actions.canCopy) {
                            AppMenuItem(
                                text = stringResource(R.string.dialog_copy),
                                icon = Icons.Outlined.ContentCopy,
                                onClick = {
                                    onCopy(target)
                                    onDismiss()
                                },
                            )
                            AppMenuItem(
                                text = stringResource(R.string.dialog_select_text),
                                icon = Icons.Outlined.Checklist,
                                onClick = {
                                    onSelectText(target)
                                    onDismiss()
                                },
                            )
                        }
                        if (actions.canEdit) {
                            AppMenuItem(
                                text = stringResource(R.string.dialog_edit),
                                icon = Icons.Outlined.Edit,
                                onClick = {
                                    onEdit(target)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
                if (actions.canDelete) {
                    AppMenuGroup {
                        AppMenuItem(
                            text = stringResource(R.string.dialog_delete),
                            icon = Icons.Outlined.Delete,
                            destructive = true,
                            onClick = {
                                onDelete(target)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}
