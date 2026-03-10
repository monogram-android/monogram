package org.monogram.presentation.features.auth

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.presentation.features.auth.components.AuthErrorDialog
import org.monogram.presentation.features.auth.components.CodeInputScreen
import org.monogram.presentation.features.auth.components.PasswordInputScreen
import org.monogram.presentation.features.auth.components.PhoneInputScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthContent(component: AuthComponent) {
    val model by component.model.subscribeAsState()
    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val maxContentWidth = if (isTablet && isLandscape) 1000.dp else 600.dp

    val isCustomBackHandlingEnabled = model.authState is AuthComponent.AuthState.InputCode || model.authState is AuthComponent.AuthState.InputPassword

    BackHandler(enabled = isCustomBackHandlingEnabled) {
        component.onBackToPhone()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CenterAlignedTopAppBar(
                        modifier = Modifier.widthIn(max = maxContentWidth),
                        title = {
                            Text(
                                text = when (model.authState) {
                                    is AuthComponent.AuthState.InputPhone -> "Your Phone"
                                    is AuthComponent.AuthState.InputCode -> "Verification"
                                    is AuthComponent.AuthState.InputPassword -> "Password"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        navigationIcon = {
                            if (model.authState is AuthComponent.AuthState.InputCode || model.authState is AuthComponent.AuthState.InputPassword) {
                                IconButton(onClick = component::onBackToPhone) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = component::onProxyClicked) {
                                Icon(
                                    imageVector = Icons.Rounded.SettingsEthernet,
                                    contentDescription = "Proxy Settings",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(top = padding.calculateTopPadding())
                .imePadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            val contentModifier = Modifier
                .widthIn(max = maxContentWidth)
                .fillMaxWidth()

            AnimatedContent(
                targetState = model.authState,
                transitionSpec = {
                    val direction = if (targetState.index > initialState.index) {
                        AnimatedContentTransitionScope.SlideDirection.Start
                    } else {
                        AnimatedContentTransitionScope.SlideDirection.End
                    }
                    slideIntoContainer(
                        towards = direction,
                        animationSpec = tween(400)
                    ) + fadeIn(animationSpec = tween(400)) togetherWith
                            slideOutOfContainer(
                                towards = direction,
                                animationSpec = tween(400)
                            ) + fadeOut(animationSpec = tween(400))
                },
                label = "AuthTransition"
            ) { targetState ->
                Box(modifier = contentModifier) {
                    when (targetState) {
                        is AuthComponent.AuthState.InputPhone -> PhoneInputScreen(
                            onConfirm = component::onPhoneEntered,
                            isSubmitting = model.isSubmitting
                        )

                        is AuthComponent.AuthState.InputCode -> CodeInputScreen(
                            phoneNumber = model.phoneNumber ?: "",
                            codeLength = targetState.codeLength,
                            codeType = targetState.codeType,
                            nextCodeType = targetState.nextCodeType,
                            timeout = targetState.timeout,
                            onConfirm = component::onCodeEntered,
                            onResend = component::onResendCode,
                            onBack = component::onBackToPhone,
                            isSubmitting = model.isSubmitting
                        )

                        is AuthComponent.AuthState.InputPassword -> PasswordInputScreen(
                            onConfirm = component::onPasswordEntered,
                            isSubmitting = model.isSubmitting
                        )
                    }
                }
            }

            if (model.error != null) {
                AuthErrorDialog(
                    message = model.error!!,
                    onDismiss = component::dismissError
                )
            }
        }
    }
}

private val AuthComponent.AuthState.index: Int
    get() = when (this) {
        is AuthComponent.AuthState.InputPhone -> 1
        is AuthComponent.AuthState.InputCode -> 2
        is AuthComponent.AuthState.InputPassword -> 3
    }
