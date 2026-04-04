package org.monogram.presentation.features.auth.components

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ExpressiveDefaults
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CodeInputScreen(
    phoneNumber: String,
    codeLength: Int,
    codeType: String,
    nextCodeType: String? = null,
    timeout: Int = 0,
    emailPattern: String? = null,
    onConfirm: (String) -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
    isSubmitting: Boolean
) {
    var code by remember { mutableStateOf("") }
    val maxCodeLength = if (codeLength > 0) codeLength else 5
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scrollState = rememberScrollState()

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val localClipboard = LocalClipboard.current
    val nativeClipboard = localClipboard.nativeClipboard
    var isFocused by remember { mutableStateOf(false) }
    var showPasteMenu by remember { mutableStateOf(false) }
    var isPasted by remember { mutableStateOf(false) }

    val isKeyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val isInputMode = isKeyboardVisible || isFocused

    var timeLeft by remember(timeout) { mutableIntStateOf(timeout) }
    LaunchedEffect(timeLeft) {
        if (timeLeft > 0) {
            delay(1000L)
            timeLeft -= 1
        }
    }

    LaunchedEffect(isKeyboardVisible) {
        if (!isKeyboardVisible && isFocused) {
            focusManager.clearFocus()
        }
    }

    BackHandler(enabled = isFocused) {
        focusManager.clearFocus()
    }

    val iconSize by animateDpAsState(
        targetValue = if (isInputMode) 0.dp else 80.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "iconSize"
    )
    val iconAlpha by animateFloatAsState(
        targetValue = if (isInputMode) 0f else 1f,
        animationSpec = tween(durationMillis = 300),
        label = "iconAlpha"
    )
    val topSpacerHeight by animateDpAsState(
        targetValue = if (isInputMode) 0.dp else 32.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "topSpacerHeight"
    )
    val middleSpacerHeight by animateDpAsState(
        targetValue = if (isInputMode) 24.dp else 48.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "middleSpacerHeight"
    )

    val content: @Composable () -> Unit = {
        Spacer(modifier = Modifier.height(topSpacerHeight))

        Box(
            modifier = Modifier
                .size(iconSize)
                .alpha(iconAlpha),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .requiredSize(80.dp)
                    .graphicsLayer {
                        val scale = if (iconSize.value > 0) iconSize.value / 80f else 0f
                        scaleX = scale
                        scaleY = scale
                    },
                shape = MaterialShapes.Cookie4Sided.toShape(),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(if (isInputMode) 12.dp else 24.dp))

        Text(
            text = phoneNumber,
            style = if (isInputMode) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(if (isInputMode) 4.dp else 12.dp))

        val deliveryMessage = when {
            codeType.contains("Email", ignoreCase = true) ->
                stringResource(R.string.verification_delivery_email, emailPattern ?: "")

            codeType.contains(
                "TelegramMessage",
                ignoreCase = true
            ) -> stringResource(R.string.verification_delivery_telegram)

            codeType.contains(
                "Sms",
                ignoreCase = true
            ) -> stringResource(R.string.verification_delivery_sms)

            codeType.contains(
                "Call",
                ignoreCase = true
            ) -> stringResource(R.string.verification_delivery_call)

            else -> stringResource(R.string.verification_delivery_default)
        }

        Text(
            text = deliveryMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(middleSpacerHeight))

        Box(contentAlignment = Alignment.Center) {
            BasicTextField(
                value = code,
                onValueChange = {
                    isPasted = (it.length - code.length) > 1

                    if (it.length <= maxCodeLength && it.all { char -> char.isDigit() }) {
                        code = it
                        if (code.length == maxCodeLength) {
                            onConfirm(code)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (code.length == maxCodeLength) {
                            onConfirm(code)
                        } else {
                            focusManager.clearFocus()
                        }
                    }
                ),
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { isFocused = it.isFocused },
                decorationBox = { }
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    },
                    onLongClick = {
                        if (nativeClipboard.hasPrimaryClip()) {
                            showPasteMenu = true
                        }
                    }
                )
            ) {
                repeat(maxCodeLength) { index ->
                    val char = code.getOrNull(index)?.toString() ?: ""
                    val isBoxFocused = code.length == index && isFocused

                    AnimatedOtpBox(
                        index = index,
                        char = char,
                        isBoxFocused = isBoxFocused,
                        isPasted = isPasted
                    )
                }
            }

            DropdownMenu(
                expanded = showPasteMenu,
                onDismissRequest = { showPasteMenu = false },
                offset = DpOffset(0.dp, 0.dp)
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.paste_action)) },
                    onClick = {
                        val pastedText = nativeClipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        val digits = pastedText.filter { it.isDigit() }.take(maxCodeLength)
                        if (digits.isNotEmpty()) {
                            isPasted = true
                            code = digits
                            if (code.length == maxCodeLength) {
                                onConfirm(code)
                            }
                        }
                        showPasteMenu = false
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(middleSpacerHeight))

        if (isSubmitting) {
            LoadingIndicator(modifier = Modifier.size(32.dp))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onConfirm(code) },
                    shapes = ExpressiveDefaults.extraLargeButtonShapes(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = code.length == maxCodeLength
                ) {
                    Text(
                        stringResource(R.string.confirm_button),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }

                if (timeLeft > 0) {
                    val minutes = timeLeft / 60
                    val seconds = timeLeft % 60
                    Text(
                        text = stringResource(
                            R.string.resend_code_timer,
                            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                } else if (nextCodeType != null) {
                    TextButton(
                        onClick = onResend,
                        shapes = ExpressiveDefaults.largeButtonShapes(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val resendText = when {
                            nextCodeType.contains(
                                "Sms",
                                ignoreCase = true
                            ) -> stringResource(R.string.resend_via_sms)

                            nextCodeType.contains(
                                "Call",
                                ignoreCase = true
                            ) -> stringResource(R.string.resend_via_call)

                            else -> stringResource(R.string.resend_code)
                        }
                        Text(resendText)
                    }
                }

                TextButton(
                    onClick = onBack,
                    shapes = ExpressiveDefaults.largeButtonShapes(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.wrong_number))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { focusManager.clearFocus() },
        color = MaterialTheme.colorScheme.background
    ) {
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    content()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    content()
                }
            }
        }
    }

    LaunchedEffect(isPasted) {
        if (isPasted) {
            val totalDelay = (maxCodeLength * PASTE_CASCADE_DELAY_MS) + SCALE_ANIMATION_DURATION_MS
            delay(totalDelay)
            isPasted = false
        }
    }
}

@Composable
private fun AnimatedOtpBox(
    index: Int,
    char: String,
    isBoxFocused: Boolean,
    isPasted: Boolean
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isBoxFocused) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "backgroundColor"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isBoxFocused) {
            MaterialTheme.colorScheme.primary
        } else {
            Color.Transparent
        },
        label = "borderColor"
    )

    Surface(
        modifier = Modifier.size(width = 50.dp, height = 64.dp),
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor,
        border = BorderStroke(2.dp, borderColor)
    ) {
        Box(contentAlignment = Alignment.Center) {
            val delayMillis = if (isPasted) (index * PASTE_CASCADE_DELAY_MS).toInt() else 0

            AnimatedVisibility(
                visible = char.isNotEmpty(),
                enter = scaleIn(
                    initialScale = 0.5f,
                    animationSpec = tween(
                        durationMillis = 400,
                        delayMillis = delayMillis,
                        easing = EaseOutBack
                    )
                ) + fadeIn(
                    animationSpec = tween(
                        durationMillis = 300,
                        delayMillis = delayMillis
                    )
                ),
                exit = scaleOut() + fadeOut()
            ) {
                Text(
                    text = char,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private const val PASTE_CASCADE_DELAY_MS = 50L
private const val SCALE_ANIMATION_DURATION_MS = 400L