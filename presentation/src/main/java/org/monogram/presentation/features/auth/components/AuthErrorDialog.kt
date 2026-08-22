package org.monogram.presentation.features.auth.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.domain.repository.AuthError
import org.monogram.presentation.R

@Composable
fun AuthErrorDialog(
    error: AuthError,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenProxy: () -> Unit
) {
    val isNetworkTimeout = error is AuthError.NetworkTimeout
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(text = stringResource(R.string.auth_error_title))
        },
        text = {
            val errorText = when (error) {
                AuthError.NetworkTimeout -> stringResource(R.string.auth_network_unreachable_error)
                AuthError.InvalidCode -> stringResource(R.string.auth_phone_code_invalid_error)
                AuthError.InvalidPassword -> stringResource(R.string.auth_password_hash_invalid)
                AuthError.CodeExpired -> stringResource(R.string.auth_code_expired_error)
                AuthError.SignUpRequired -> stringResource(R.string.auth_signup_required_error)
                is AuthError.PaidCodeRequired -> stringResource(R.string.auth_signup_required_error)
                is AuthError.RateLimited -> stringResource(
                    R.string.auth_rate_limited_error,
                    error.retryAfterSeconds ?: 0
                )
                AuthError.Unexpected -> stringResource(R.string.unexpected_error)
            }
            Text(text = errorText)
        },
        confirmButton = {
            Button(
                onClick = if (isNetworkTimeout) onRetry else onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isNetworkTimeout) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = if (isNetworkTimeout) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    stringResource(
                        if (isNetworkTimeout) R.string.auth_retry_button else R.string.dismiss_button
                    )
                )
            }
        },
        dismissButton = if (isNetworkTimeout) {
            {
                TextButton(
                    onClick = onOpenProxy,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.proxy_settings_title))
                }
            }
        } else {
            null
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
