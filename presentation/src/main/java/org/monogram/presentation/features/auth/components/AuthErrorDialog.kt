package org.monogram.presentation.features.auth.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.domain.repository.AUTH_NETWORK_TIMEOUT_ERROR
import org.monogram.presentation.R

@Composable
fun AuthErrorDialog(
    message: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenProxy: () -> Unit
) {
    val isNetworkTimeout = message == AUTH_NETWORK_TIMEOUT_ERROR
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
            val errorText = when (message) {
                AUTH_NETWORK_TIMEOUT_ERROR -> stringResource(R.string.auth_network_unreachable_error)
                "PHONE_CODE_INVALID" -> stringResource(R.string.auth_phone_code_invalid_error)
                "PASSWORD_HASH_INVALID" -> stringResource(R.string.auth_password_hash_invalid)
                else -> stringResource(R.string.unexpected_error)
            }
            val details = message.trim()
            val dialogText = if (!isNetworkTimeout && details.isNotEmpty() && details != errorText) {
                "$errorText\n\n$details"
            } else {
                errorText
            }
            Text(text = dialogText)
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
