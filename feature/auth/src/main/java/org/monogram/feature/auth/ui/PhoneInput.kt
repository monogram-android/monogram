package org.monogram.feature.auth.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.monogram.core.common.Country
import org.monogram.core.common.CountryManager
import org.monogram.feature.auth.R
import java.util.Locale

object PhoneInputTags {
    const val FIELD = "phone_input_field"
    const val COUNTRY = "phone_input_country"
}

@Composable
fun PhoneInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
) {
    val context = LocalContext.current
    val defaultIso = remember(context) {
        CountryManager.resolveIso(CountryManager.simIso(context), Locale.getDefault().country)
    }
    var pickedIso by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerVisible by rememberSaveable { mutableStateOf(false) }

    val digits = value.filter { it.isDigit() }
    val picked = CountryManager.countryForIso(pickedIso)
    val fallback = picked ?: CountryManager.countryForIso(defaultIso)
    val country = CountryManager.countryForTypedPhone(digits, fallback)
        ?: fallback
        ?: CountryManager.countries().firstOrNull()
        ?: return

    val display = CountryManager.formatAsYouType(value, country)
    val example = remember(country.iso, placeholder) {
        CountryManager.exampleNumber(country.iso)
            ?.removePrefix("+${country.code}")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: placeholder
    }
    var caret by rememberSaveable { mutableStateOf(display.length) }
    val field = TextFieldValue(
        text = display,
        selection = TextRange(caret.coerceIn(0, display.length)),
    )
    val dialCodeLabel = stringResource(R.string.auth_dial_code)
    val countryLabel = stringResource(R.string.auth_country_selected, country.name)

    OutlinedTextField(
        value = field,
        onValueChange = { edit ->
            val editCaret = edit.selection.end.coerceIn(0, edit.text.length)
            val previous = value.filter { it.isDigit() }
            val injectedBefore = (display.count { it.isDigit() } - previous.length).coerceAtLeast(0)
            val typed = edit.text.filter { it.isDigit() }
            val caretDigits = (digitsBeforeCaret(edit.text, editCaret) - injectedBefore)
                .coerceIn(0, (typed.length - injectedBefore).coerceAtLeast(0))
            val separatorDeleted = typed == previous && edit.text.length < display.length
            val digits = if (separatorDeleted) dropDigitBefore(previous, caretDigits) else typed
            val nextValue = CountryManager.phoneValueForTypedDigits(digits, fallback)
            val nextDigitIndex = if (separatorDeleted) maxOf(caretDigits - 1, 0) else caretDigits
            onValueChange(nextValue)
            val storedDigits = nextValue.filter { it.isDigit() }
            val nextCountry = CountryManager.countryForTypedPhone(storedDigits, fallback) ?: country
            val nextDisplay = CountryManager.formatAsYouType(nextValue, nextCountry)
            val injected = (nextDisplay.count { it.isDigit() } - storedDigits.length).coerceAtLeast(0)
            caret = caretAfterDigit(nextDisplay, injected + nextDigitIndex)
        },
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
            .testTag(PhoneInputTags.FIELD),
        label = { Text(label) },
        placeholder = { Text(example) },
        prefix = {
            if (display.isBlank()) {
                Text(
                    text = "+${country.code}",
                    modifier = Modifier.semantics { contentDescription = dialCodeLabel },
                )
            }
        },
        leadingIcon = {
            Text(
                text = country.flagEmoji,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .testTag(PhoneInputTags.COUNTRY)
                    .clickable(enabled = enabled) { pickerVisible = true }
                    .semantics { contentDescription = countryLabel }
                    .padding(horizontal = 4.dp),
            )
        },
        isError = isError,
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Phone,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onDone = { onImeAction() }),
        enabled = enabled,
    )

    if (pickerVisible) {
        CountryPicker(
            selectedIso = country.iso,
            onDismiss = { pickerVisible = false },
            onSelect = { target ->
                pickedIso = target.iso
                pickerVisible = false
                onValueChange("+${target.code}")
                caret = Int.MAX_VALUE
            },
        )
    }
}

@Composable
private fun CountryPicker(
    selectedIso: String,
    onDismiss: () -> Unit,
    onSelect: (Country) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val all = remember { CountryManager.countries() }
    val matches = remember(query, all) {
        val target = query.trim()
        if (target.isEmpty()) {
            all
        } else {
            val codeQuery = target.filter { it.isDigit() }
            all.filter { country ->
                country.name.contains(target, ignoreCase = true) ||
                    country.iso.contains(target, ignoreCase = true) ||
                    (codeQuery.isNotEmpty() && country.code.startsWith(codeQuery))
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auth_country_picker)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.auth_country_search_hint)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                )
                if (matches.isNotEmpty()) {
                    HorizontalDivider()
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(matches, key = { it.iso }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(item) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = item.flagEmoji, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (item.iso == selectedIso) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "+${item.code}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.auth_cd_back))
            }
        },
    )
}

internal fun digitsBeforeCaret(text: String, caret: Int): Int {
    val clamped = caret.coerceIn(0, text.length)
    return text.take(clamped).count { it.isDigit() }
}

internal fun caretAfterDigit(formatted: String, digitIndex: Int): Int {
    if (formatted.isEmpty() || digitIndex <= 0) return 0
    var seen = 0
    formatted.forEachIndexed { index, char ->
        if (char.isDigit()) {
            seen += 1
            if (seen == digitIndex) return index + 1
        }
    }
    return formatted.length
}

internal fun dropDigitBefore(digits: String, digitIndex: Int): String {
    if (digitIndex <= 0 || digitIndex > digits.length) return digits
    return digits.removeRange(digitIndex - 1, digitIndex)
}
