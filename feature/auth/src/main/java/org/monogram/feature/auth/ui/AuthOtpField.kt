package org.monogram.feature.auth.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.auth.AuthStore
import org.monogram.feature.auth.R

@Composable
internal fun AuthOtpField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isError: Boolean,
    modifier: Modifier = Modifier,
    codeLength: Int = AuthCodeLength,
) {
    val focus = remember { FocusRequester() }
    val label = stringResource(R.string.auth_code_label)
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .testTag(AuthTestTags.CODE_FIELD)
            .semantics { contentDescription = label },
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(codeLength) { index ->
                    val digit = value.getOrNull(index)?.toString().orEmpty()
                    val focused = value.length == index ||
                        (value.length == codeLength && index == codeLength - 1)
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clickable(enabled = enabled) { focus.requestFocus() },
                        shape = MaterialTheme.shapes.large,
                        color = if (isError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        contentColor = if (isError) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        tonalElevation = if (focused) 2.dp else 0.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = digit,
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Preview(showBackground = true, name = "Code")
@Composable
private fun AuthCodePreview() {
    MonogramTheme(dynamicColor = false) {
        AuthScreen(
            state = AuthStore.State(
                phone = "+49151",
                code = "12",
                phase = AuthStore.Phase.CodeEntry("+49151", "hash", "app"),
            ),
            onIntent = {},
        )
    }
}

@Preview(showBackground = true, name = "Code 6 digits")
@Composable
private fun AuthCode6Preview() {
    MonogramTheme(dynamicColor = false) {
        AuthScreen(
            state = AuthStore.State(
                phone = "+49151",
                code = "17209",
                phase = AuthStore.Phase.CodeEntry("+49151", "hash", "app", codeLength = 6),
            ),
            onIntent = {},
        )
    }
}
