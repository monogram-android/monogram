package org.monogram.feature.auth.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.ui.components.toUiMessage
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.auth.AuthStore
import org.monogram.feature.auth.R

private val AuthFloodWait = Regex("FLOOD_WAIT_(\\d+)")

internal fun floodWaitSeconds(error: AuthStore.Error?): Int {
    val type = (error as? AuthStore.Error.Rpc)?.error?.type ?: return 0
    return AuthFloodWait.find(type)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

@Composable
internal fun AuthInlineError(message: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(animationSpec = tween(180)) +
            expandVertically(animationSpec = tween(220, easing = AuthExpressiveEasing)),
        exit = fadeOut(animationSpec = tween(120)) +
            shrinkVertically(animationSpec = tween(180, easing = AuthExpressiveEasing)),
        modifier = modifier,
    ) {
        Text(
            text = message.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AuthTestTags.ERROR),
        )
    }
}

@Composable
internal fun errorText(error: AuthStore.Error): String = when (error) {
    AuthStore.Error.PhoneRequired -> stringResource(R.string.auth_error_phone_required)
    AuthStore.Error.CodeRequired -> stringResource(R.string.auth_error_code_required)
    AuthStore.Error.PasswordRequired -> stringResource(R.string.auth_error_password_required)
    is AuthStore.Error.Rpc -> when {
        error.error.type.contains("AUTH_RESTART") ->
            stringResource(R.string.auth_error_restart)
        error.error.type == "PHONE_NUMBER_UNOCCUPIED" ->
            stringResource(R.string.auth_error_no_account)
        error.error.type == "SEND_CODE_UNAVAILABLE" ->
            stringResource(R.string.auth_error_code_unavailable)
        error.error.type.contains("PASSWORD_HASH_INVALID") ->
            stringResource(R.string.auth_error_bad_password)
        error.error.type.contains("SRP_ID_INVALID") ->
            stringResource(R.string.auth_error_srp_expired)
        error.error.kind == TelegramError.Kind.Network ->
            stringResource(R.string.auth_error_network)
        else -> error.error.toUiMessage()
    }
}

@Preview(showBackground = true, name = "Error")
@Composable
private fun AuthErrorPreview() {
    MonogramTheme(dynamicColor = false) {
        AuthScreen(
            state = AuthStore.State(error = AuthStore.Error.PhoneRequired),
            onIntent = {},
        )
    }
}
