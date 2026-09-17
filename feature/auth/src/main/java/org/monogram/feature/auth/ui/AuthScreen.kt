package org.monogram.feature.auth.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.monogram.core.ui.ExpressiveDefaults
import org.monogram.core.ui.loading.MonogramBusyButton
import org.monogram.core.ui.loading.MonogramLinearProgress
import org.monogram.feature.auth.AuthStore
import org.monogram.feature.auth.R
import org.monogram.feature.auth.normalizePhone

internal fun authStepOf(phase: AuthStore.Phase): Int = when (phase) {
    AuthStore.Phase.PhoneEntry, is AuthStore.Phase.Authorized -> 0
    is AuthStore.Phase.CodeEntry -> 1
    AuthStore.Phase.PasswordEntry -> 2
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AuthScreen(
    state: AuthStore.State,
    onIntent: (AuthStore.Intent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = authMotionEnabled()
    val step = authStepOf(state.phase)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val phoneFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    var localPassword by rememberSaveable(state.phase) { mutableStateOf("") }
    var passwordVisible by rememberSaveable(state.phase) { mutableStateOf(false) }
    var pasteEmpty by rememberSaveable(state.phase) { mutableStateOf(false) }
    var submittedCode by rememberSaveable { mutableStateOf("") }
    var resendSeconds by rememberSaveable(step) { mutableStateOf(0) }
    val floodSeconds = floodWaitSeconds(state.error)
    LaunchedEffect(state.phase) {
        if (state.phase !is AuthStore.Phase.CodeEntry) {
            submittedCode = ""
        }
    }
    LaunchedEffect(state.code, state.loading, state.phase) {
        val inCode = state.phase is AuthStore.Phase.CodeEntry
        if (inCode &&
            state.code.length == AuthCodeLength &&
            !state.loading &&
            state.code != submittedCode
        ) {
            submittedCode = state.code
            onIntent(AuthStore.Intent.SignIn)
        }
    }
    LaunchedEffect(step, floodSeconds) {
        resendSeconds = maxOf(if (step == 1) ResendDelaySeconds else 0, floodSeconds)
        while (resendSeconds > 0) {
            delay(1000)
            resendSeconds -= 1
        }
    }
    LaunchedEffect(step) {
        when (step) {
            0 -> phoneFocus.requestFocus()
            2 -> passwordFocus.requestFocus()
        }
    }

    val canGoBack = state.phase is AuthStore.Phase.CodeEntry ||
        state.phase is AuthStore.Phase.PasswordEntry
    val fieldError = state.error != null && !state.loading
    val otpStyle = localPassword.isNotEmpty() && localPassword.all(Char::isDigit)
    val errorMessage = state.error?.let { errorText(it) }
    val pasteMessage = if (pasteEmpty) stringResource(R.string.auth_paste_empty) else null
    val primaryEnabled = !state.loading && when (step) {
        1 -> state.code.length == AuthCodeLength
        2 -> localPassword.isNotEmpty()
        else -> normalizePhone(state.phone).isNotEmpty()
    }
    val actionLabel = stringResource(
        when (step) {
            1 -> R.string.auth_sign_in
            2 -> R.string.auth_submit_password
            else -> R.string.auth_send_code
        },
    )

    Scaffold(
        modifier = modifier.fillMaxSize().testTag(AuthTestTags.ROOT),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (canGoBack) {
                        val back = stringResource(R.string.auth_cd_back)
                        IconButton(
                            onClick = { onIntent(AuthStore.Intent.BackToPhone) },
                            enabled = !state.loading,
                            modifier = Modifier.semantics { contentDescription = back },
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = back)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MonogramBusyButton(
                        onClick = {
                            when (state.phase) {
                                is AuthStore.Phase.CodeEntry ->
                                    onIntent(AuthStore.Intent.SignIn)
                                AuthStore.Phase.PasswordEntry ->
                                    onIntent(AuthStore.Intent.SubmitPassword(localPassword))
                                else -> onIntent(AuthStore.Intent.SendCode)
                            }
                        },
                        busy = state.loadingUi.visible,
                        enabled = primaryEnabled,
                        modifier = Modifier
                            .widthIn(max = AuthFormMaxWidth)
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag(AuthTestTags.PRIMARY),
                        shapes = ExpressiveDefaults.largeButtonShapes(),
                    ) {
                        Text(actionLabel)
                    }
                }
            }
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = AuthFormMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MonogramLinearProgress(
                    visible = state.loadingUi.visible,
                    modifier = Modifier.fillMaxWidth(),
                    height = 3.dp,
                    generation = state.loadingUi.generation,
                )
                Spacer(modifier = Modifier.height(24.dp))
                AuthStepIndicator(step = step, motion = motion)
                Spacer(modifier = Modifier.height(28.dp))
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        authStepTransition(motion = motion, forward = targetState > initialState)
                    },
                    label = "auth-step",
                ) { active ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AuthStepHeader(step = active, state = state)
                        when (active) {
                            1 -> AuthOtpField(
                                value = state.code,
                                onValueChange = { raw ->
                                    onIntent(
                                        AuthStore.Intent.CodeChanged(
                                            raw.filter(Char::isDigit).take(AuthCodeLength),
                                        ),
                                    )
                                },
                                enabled = !state.loading,
                                isError = fieldError,
                            )
                            2 -> AuthPasswordField(
                                value = localPassword,
                                visible = passwordVisible,
                                otpStyle = otpStyle,
                                enabled = !state.loading,
                                isError = fieldError,
                                focus = passwordFocus,
                                onValueChange = { value ->
                                    pasteEmpty = false
                                    localPassword = value
                                    onIntent(AuthStore.Intent.PasswordChanged(value))
                                },
                                onToggleVisible = { passwordVisible = !passwordVisible },
                                onPaste = {
                                    scope.launch {
                                        val text = clipboard.getClipEntry()
                                            ?.clipData
                                            ?.takeIf { it.itemCount > 0 }
                                            ?.getItemAt(0)
                                            ?.text
                                            ?.toString()
                                            .orEmpty()
                                        val pasted = pastedPassword(text)
                                        pasteEmpty = pasted.isEmpty()
                                        if (pasted.isNotEmpty()) {
                                            localPassword = pasted
                                            onIntent(AuthStore.Intent.PasswordChanged(pasted))
                                        }
                                    }
                                },
                                onImeAction = {
                                    if (primaryEnabled) {
                                        onIntent(AuthStore.Intent.SubmitPassword(localPassword))
                                    }
                                },
                            )
                            else -> AuthPhoneStep(
                                state = state,
                                focus = phoneFocus,
                                enabled = !state.loading,
                                isError = fieldError && state.error is AuthStore.Error.PhoneRequired,
                                onIntent = onIntent,
                                primaryEnabled = primaryEnabled,
                            )
                        }
                        AuthInlineError(message = errorMessage ?: pasteMessage)
                        if (active == 1) {
                            AuthResendAction(
                                seconds = resendSeconds,
                                enabled = !state.loading,
                                onResend = { onIntent(AuthStore.Intent.ResendCode) },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AuthStepIndicator(step: Int, motion: Boolean, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.auth_step_indicator, step + 1, AuthStepCount)
    Row(
        modifier = modifier
            .testTag(AuthTestTags.STEP_INDICATOR)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(AuthStepCount) { index ->
            val active = index == step
            val width by animateDpAsState(
                targetValue = if (active) 24.dp else 8.dp,
                animationSpec = if (motion) tween(260, easing = AuthExpressiveEasing) else snap(),
                label = "auth-step-dot",
            )
            Box(
                modifier = Modifier
                    .width(width)
                    .height(8.dp)
                    .background(
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = RoundedCornerShape(percent = 50),
                    ),
            )
        }
    }
}

@Composable
private fun AuthStepHeader(step: Int, state: AuthStore.State) {
    val title = stringResource(
        when (step) {
            1 -> R.string.auth_code_title
            2 -> R.string.auth_password_title
            else -> R.string.auth_title
        },
    )
    val subtitle = if (step == 1) {
        val code = state.phase as? AuthStore.Phase.CodeEntry
        if (code != null) codeSentMessage(code) else stringResource(R.string.auth_code_title)
    } else if (step == 2) {
        stringResource(R.string.auth_password_prompt)
    } else {
        stringResource(R.string.auth_subtitle)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AuthTestTags.TITLE),
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AuthResendAction(
    seconds: Int,
    enabled: Boolean,
    onResend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = if (seconds > 0) {
        stringResource(R.string.auth_resend_in, seconds)
    } else {
        stringResource(R.string.auth_resend_code)
    }
    TextButton(
        onClick = onResend,
        enabled = enabled && seconds == 0,
        modifier = modifier.testTag(AuthTestTags.RESEND),
    ) {
        Text(label)
    }
}

private fun authStepTransition(motion: Boolean, forward: Boolean): ContentTransform {
    if (!motion) {
        return fadeIn(animationSpec = tween(0)) togetherWith fadeOut(animationSpec = tween(0))
    }
    val direction = if (forward) 1 else -1
    return (
        slideInHorizontally(animationSpec = tween(320, easing = AuthExpressiveEasing)) {
            it / 6 * direction
        } + fadeIn(animationSpec = tween(220))
        ) togetherWith (
        slideOutHorizontally(animationSpec = tween(220, easing = AuthExpressiveEasing)) {
            -it / 6 * direction
        } + fadeOut(animationSpec = tween(140))
        )
}

@Composable
private fun authMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }
}

@Composable
private fun codeSentMessage(phase: AuthStore.Phase.CodeEntry): String {
    val phone = normalizePhone(phase.phone).ifEmpty { phase.phone }
    return when (phase.codeType) {
        "app" -> stringResource(R.string.auth_code_sent_telegram)
        "sms", "sms_word", "sms_phrase", "fragment", "firebase" ->
            stringResource(R.string.auth_code_sent_sms, phone)
        "call", "flash_call", "missed_call" ->
            stringResource(R.string.auth_code_sent_call, phone)
        else -> stringResource(R.string.auth_code_sent_to, phone)
    }
}
