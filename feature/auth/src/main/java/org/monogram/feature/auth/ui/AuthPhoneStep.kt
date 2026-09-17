package org.monogram.feature.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.auth.AuthStore
import org.monogram.feature.auth.R

@Composable
internal fun AuthPhoneStep(
    state: AuthStore.State,
    focus: FocusRequester,
    enabled: Boolean,
    isError: Boolean,
    primaryEnabled: Boolean,
    onIntent: (AuthStore.Intent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PhoneInput(
            value = state.phone,
            onValueChange = { onIntent(AuthStore.Intent.PhoneChanged(it)) },
            label = stringResource(R.string.auth_phone_label),
            placeholder = stringResource(R.string.auth_phone_hint),
            focusRequester = focus,
            isError = isError,
            enabled = enabled,
            imeAction = ImeAction.Done,
            onImeAction = {
                if (primaryEnabled) {
                    onIntent(AuthStore.Intent.SendCode)
                }
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = state.useTestDc,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = { onIntent(AuthStore.Intent.SetTestDc(it)) },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.auth_test_server),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.auth_test_server_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.useTestDc,
                onCheckedChange = null,
                enabled = enabled,
            )
        }
    }
}

@Preview(showBackground = true, name = "Phone light")
@Preview(
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
    name = "Phone dark",
)
@Composable
private fun AuthPhonePreview() {
    MonogramTheme(dynamicColor = false) {
        AuthScreen(
            state = AuthStore.State(phone = "+49"),
            onIntent = {},
        )
    }
}
