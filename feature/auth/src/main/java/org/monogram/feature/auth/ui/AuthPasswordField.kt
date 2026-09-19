package org.monogram.feature.auth.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.auth.AuthStore
import org.monogram.feature.auth.R

@Composable
internal fun AuthPasswordField(
    value: String,
    visible: Boolean,
    enabled: Boolean,
    isError: Boolean,
    focus: FocusRequester,
    onValueChange: (String) -> Unit,
    onToggleVisible: () -> Unit,
    onPaste: () -> Unit,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val toggle = stringResource(
        if (visible) R.string.auth_hide_password else R.string.auth_show_password,
    )
    val pasteLabel = stringResource(R.string.auth_paste_password)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .testTag(AuthTestTags.PASSWORD_FIELD),
        label = { Text(stringResource(R.string.auth_password_label)) },
        isError = isError,
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            Row {
                IconButton(
                    onClick = onPaste,
                    enabled = enabled,
                    modifier = Modifier
                        .testTag(AuthTestTags.PASTE_PASSWORD)
                        .semantics { contentDescription = pasteLabel },
                ) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = pasteLabel)
                }
                IconButton(
                    onClick = onToggleVisible,
                    enabled = enabled,
                    modifier = Modifier
                        .testTag(AuthTestTags.TOGGLE_PASSWORD)
                        .semantics { contentDescription = toggle },
                ) {
                    Icon(
                        imageVector = if (visible) {
                            Icons.Outlined.VisibilityOff
                        } else {
                            Icons.Outlined.Visibility
                        },
                        contentDescription = toggle,
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onImeAction() }),
        enabled = enabled,
    )
}

@Preview(showBackground = true, name = "Password")
@Composable
private fun AuthPasswordPreview() {
    MonogramTheme(dynamicColor = false) {
        AuthScreen(
            state = AuthStore.State(
                phone = "+49151",
                phase = AuthStore.Phase.PasswordEntry,
            ),
            onIntent = {},
        )
    }
}
