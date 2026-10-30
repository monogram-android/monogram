package org.monogram.core.ui.loading

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.onLongClick as semanticsLongClick

@Composable
private fun MonogramBusySwap(
    busy: Boolean,
    modifier: Modifier = Modifier,
    idle: @Composable () -> Unit,
    busyContent: @Composable () -> Unit,
) {
    AnimatedContent(
        targetState = busy,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(MonogramMotion.appear) + scaleIn(
                MonogramMotion.appear, initialScale = MonogramMotion.AppearScale
            )) togetherWith (fadeOut(MonogramMotion.disappear) + scaleOut(
                MonogramMotion.disappear, targetScale = MonogramMotion.DisappearScale
            ))
        },
        contentAlignment = Alignment.Center,
        label = "monoBusySwap",
    ) { isBusy ->
        if (isBusy) busyContent() else idle()
    }
}

@Composable
fun MonogramBusyButton(
    onClick: () -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    shape: Shape = ButtonDefaults.shape,
    shapes: ButtonShapes? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    status: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val body: @Composable RowScope.() -> Unit = {
        val rowScope = this
        MonogramBusySwap(
            busy = busy,
            idle = { with(rowScope) { content() } },
            busyContent = {
                MonogramLoading(
                    visible = true,
                    size = MonogramLoadingInlineSize,
                    color = LocalContentColor.current,
                    status = status,
                )
            },
        )
    }
    if (shapes != null) {
        Button(
            onClick = onClick,
            shapes = shapes,
            modifier = modifier,
            enabled = enabled && !busy,
            colors = colors,
            contentPadding = contentPadding,
            content = body,
        )
    } else {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled && !busy,
            shape = shape,
            colors = colors,
            contentPadding = contentPadding,
            content = body,
        )
    }
}

@Composable
fun MonogramBusyButton(
    text: String,
    onClick: () -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    shape: Shape = ButtonDefaults.shape,
    shapes: ButtonShapes? = null,
    status: String? = null,
) {
    MonogramBusyButton(
        onClick = onClick,
        busy = busy,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        shape = shape,
        shapes = shapes,
        status = status,
    ) {
        Text(text = text)
    }
}

@Composable
fun MonogramBusyIconButton(
    onClick: () -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = IconButtonDefaults.filledShape,
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(),
    status: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    icon: @Composable () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val latestLongClick by rememberUpdatedState(onLongClick)
    var held by remember { mutableStateOf(false) }
    val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
    LaunchedEffect(interactions, enabled, busy, onLongClick != null, timeout) {
        if (!enabled || busy || latestLongClick == null) return@LaunchedEffect
        var hold: Job? = null
        try {
            interactions.interactions.collect { event ->
                when (event) {
                    is PressInteraction.Press -> {
                        held = false
                        hold?.cancel()
                        hold = launch { delay(timeout); held = true; latestLongClick?.invoke() }
                    }

                    is PressInteraction.Release -> hold?.cancel()
                    is PressInteraction.Cancel -> {
                        hold?.cancel(); held = false
                    }
                }
            }
        } finally {
            hold?.cancel()
        }
    }
    FilledIconButton(
        onClick = { if (held) held = false else onClick() },
        modifier = if (onLongClick != null && enabled && !busy) modifier.semantics {
            semanticsLongClick(onLongClickLabel) { latestLongClick?.invoke(); true }
        } else modifier,
        interactionSource = interactions,
        enabled = enabled && !busy,
        shape = shape,
        colors = colors,
    ) {
        MonogramBusySwap(
            busy = busy,
            idle = icon,
            busyContent = {
                MonogramLoading(
                    visible = true,
                    size = MonogramLoadingInlineSize,
                    color = LocalContentColor.current,
                    status = status,
                )
            },
        )
    }
}

@Composable
fun MonogramBusyField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    password: Boolean = false,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    status: String? = null,
) {
    val transformation = if (password) {
        remember { PasswordVisualTransformation() }
    } else {
        VisualTransformation.None
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled && !busy,
        isError = isError,
        singleLine = singleLine,
        shape = shape,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        leadingIcon = leadingIcon,
        visualTransformation = transformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = keyboardActions,
        colors = colors,
        trailingIcon = {
            MonogramBusySwap(
                busy = busy,
                idle = { trailingContent?.invoke() },
                busyContent = {
                    MonogramLoading(
                        visible = true,
                        size = MonogramLoadingInlineSize,
                        color = LocalContentColor.current,
                        status = status,
                    )
                },
            )
        },
    )
}
