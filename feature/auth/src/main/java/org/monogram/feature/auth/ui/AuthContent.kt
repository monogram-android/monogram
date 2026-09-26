package org.monogram.feature.auth.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import org.monogram.feature.auth.AuthComponent
import org.monogram.feature.auth.AuthStore

internal const val AuthCodeLength = 5
internal const val AuthStepCount = 3
internal const val ResendDelaySeconds = 30
internal val AuthFormMaxWidth = 440.dp
internal val AuthExpressiveEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

object AuthTestTags {
    const val ROOT = "auth_root"
    const val TITLE = "auth_title"
    const val STEP_INDICATOR = "auth_step_indicator"
    const val CODE_FIELD = "auth_code_field"
    const val PASTE_CODE = "auth_paste_code"
    const val PASSWORD_FIELD = "auth_password_field"
    const val PASTE_PASSWORD = "auth_paste_password"
    const val TOGGLE_PASSWORD = "auth_toggle_password"
    const val RESEND = "auth_resend"
    const val ERROR = "auth_error"
    const val PRIMARY = "auth_primary"
}

@Composable
fun AuthContent(component: AuthComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state.phase) {
        if (state.phase is AuthStore.Phase.Authorized) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
        component.consumeAuthorized()
    }
    AuthScreen(
        state = state,
        onIntent = component::onIntent,
        modifier = modifier,
    )
}
