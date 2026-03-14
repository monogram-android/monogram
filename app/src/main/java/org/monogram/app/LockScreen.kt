package org.monogram.app

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import org.monogram.presentation.root.RootComponent
import java.util.concurrent.Executors

@Composable
fun LockScreen(root: RootComponent) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    
    var passcode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var attempts by remember { mutableIntStateOf(0) }
    val isBiometricEnabled by root.isBiometricEnabled.collectAsState()

    val shakeOffset = remember { Animatable(0f) }

    val isKeyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val isInputMode = isKeyboardVisible || isFocused

    LaunchedEffect(isKeyboardVisible) {
        if (!isKeyboardVisible && isFocused) {
            focusManager.clearFocus()
        }
    }

    BackHandler(enabled = isFocused) {
        focusManager.clearFocus()
    }

    val unlockTitle = stringResource(R.string.lock_unlock_title)
    val unlockSubtitle = stringResource(R.string.lock_unlock_subtitle)
    val usePasscode = stringResource(R.string.lock_use_passcode)

    val authenticateBiometric = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val executor = Executors.newSingleThreadExecutor()
            val biometricPrompt = BiometricPrompt(
                context as FragmentActivity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        root.unlockWithBiometrics()
                    }
                }
            )

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(unlockTitle)
                .setSubtitle(unlockSubtitle)
                .setNegativeButtonText(usePasscode)
                .build()

            biometricPrompt.authenticate(promptInfo)
        }
    }

    LaunchedEffect(Unit) {
        if (isBiometricEnabled) {
            authenticateBiometric()
        }
    }

    LaunchedEffect(error) {
        if (error) {
            repeat(6) { index ->
                shakeOffset.animateTo(
                    targetValue = if (index % 2 == 0) 15f else -15f,
                    animationSpec = tween(durationMillis = 50)
                )
            }
            shakeOffset.animateTo(0f)
        }
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
        PasswordContent(
            isInputMode = isInputMode,
            passcode = passcode,
            onPasscodeChange = {
                if (it.length <= 4) {
                    passcode = it
                    error = false
                    if (it.length == 4) {
                        if (!root.unlock(it)) {
                            error = true
                            passcode = ""
                            attempts++
                            if (attempts >= 5) {
                                root.logout()
                            }
                        } else {
                            attempts = 0
                        }
                    }
                }
            },
            error = error,
            shakeOffset = shakeOffset.value,
            focusRequester = focusRequester,
            onFocusChanged = { isFocused = it },
            isBiometricEnabled = isBiometricEnabled,
            onBiometricClick = authenticateBiometric
        )
    }
}

@Composable
private fun PasswordContent(
    isInputMode: Boolean,
    passcode: String,
    onPasscodeChange: (String) -> Unit,
    error: Boolean,
    shakeOffset: Float,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    isBiometricEnabled: Boolean,
    onBiometricClick: () -> Unit
) {
    val logoSize by animateDpAsState(
        targetValue = if (isInputMode) 0.dp else 100.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "logoSize"
    )
    val logoAlpha by animateFloatAsState(
        targetValue = if (isInputMode) 0f else 1f,
        animationSpec = tween(durationMillis = 300),
        label = "logoAlpha"
    )

    val biometricSize by animateDpAsState(
        targetValue = if (isInputMode) 0.dp else 72.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "biometricSize"
    )
    val biometricAlpha by animateFloatAsState(
        targetValue = if (isInputMode) 0f else 1f,
        animationSpec = tween(durationMillis = 300),
        label = "biometricAlpha"
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(logoSize)
                .alpha(logoAlpha),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .requiredSize(100.dp)
                    .graphicsLayer {
                        val currentSize = logoSize.value
                        val scale = if (currentSize > 0) currentSize / 100f else 0f
                        scaleX = scale
                        scaleY = scale
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(topSpacerHeight))

        AnimatedContent(
            targetState = isInputMode,
            transitionSpec = {
                (fadeIn(animationSpec = tween(220, delayMillis = 90)) +
                        scaleIn(initialScale = 0.92f, animationSpec = tween(220, delayMillis = 90)))
                    .togetherWith(fadeOut(animationSpec = tween(90)))
            },
            label = "Title"
        ) { inputMode ->
            Text(
                text = stringResource(R.string.lock_enter_passcode),
                style = if (inputMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        AnimatedVisibility(
            visible = !isInputMode,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.lock_messages_protected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(middleSpacerHeight))

        OutlinedTextField(
            value = passcode,
            onValueChange = onPasscodeChange,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier
                .width(220.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChanged(it.isFocused) }
                .graphicsLayer { translationX = shakeOffset },
            singleLine = true,
            isError = error,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                errorBorderColor = MaterialTheme.colorScheme.error
            ),
            textStyle = androidx.compose.ui.text.TextStyle(
                textAlign = TextAlign.Center,
                fontSize = 28.sp,
                letterSpacing = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        )

        Box(modifier = Modifier.height(32.dp), contentAlignment = Alignment.Center) {
            @Suppress("RemoveRedundantQualifierName")
            androidx.compose.animation.AnimatedVisibility(
                visible = error,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(
                    text = stringResource(R.string.lock_invalid_passcode),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isBiometricEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Box(
                modifier = Modifier
                    .size(biometricSize)
                    .alpha(biometricAlpha),
                contentAlignment = Alignment.Center
            ) {
                FilledTonalIconButton(
                    onClick = onBiometricClick,
                    modifier = Modifier
                        .requiredSize(72.dp)
                        .graphicsLayer {
                            val currentSize = biometricSize.value
                            val scale = if (currentSize > 0) currentSize / 72f else 0f
                            scaleX = scale
                            scaleY = scale
                        },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Fingerprint,
                        contentDescription = stringResource(R.string.lock_biometric_unlock),
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1.5f))
    }
}