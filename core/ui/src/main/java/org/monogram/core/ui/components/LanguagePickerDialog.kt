package org.monogram.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.util.Locale

fun languageDisplayName(code: String, locale: Locale = Locale.getDefault()): String =
    Locale.forLanguageTag(code).getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }

@Composable
fun LanguagePickerDialog(
    title: String,
    searchLabel: String,
    emptyLabel: String,
    doneLabel: String,
    selected: Set<String>,
    multiple: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { LanguagePickerContent(searchLabel, emptyLabel, selected, multiple, onSelect) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(doneLabel) } },
    )
}

@Composable
fun LanguagePickerSheet(
    title: String,
    searchLabel: String,
    emptyLabel: String,
    doneLabel: String,
    clearSearchLabel: String,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AppModalSheet(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                        style = MaterialTheme.typography.titleLargeEmphasized,
                    )
                    FilledTonalIconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = doneLabel)
                    }
                }
                LanguagePickerContent(
                    searchLabel, emptyLabel, setOf(selected), multiple = false, onSelect = onSelect,
                    clearSearchLabel = clearSearchLabel,
                )
            }
        }
    }
}

@Composable
private fun LanguagePickerContent(
    searchLabel: String,
    emptyLabel: String,
    selected: Set<String>,
    multiple: Boolean,
    onSelect: (String) -> Unit,
    clearSearchLabel: String? = null,
) {
    var search by rememberSaveable { mutableStateOf("") }
    val locale = Locale.getDefault()
    // Keep the ordering stable while toggling multiple rows.
    val initiallySelected = remember { selected }
    val languages = remember(locale, initiallySelected) {
        Locale.getISOLanguages().asSequence()
            // Android includes "und", which parses as the root locale, not a selectable language.
            .filter { languageDisplayName(it, locale).isNotBlank() }
            .sortedWith(compareBy<String> { it !in initiallySelected }.thenBy {
                languageDisplayName(
                    it,
                    locale
                )
            })
            .toList()
    }
    val filtered = remember(languages, locale, search) {
        val query = search.trim()
        languages.filter {
            val nativeLocale = Locale.forLanguageTag(it)
            languageDisplayName(it, locale).contains(query, ignoreCase = true) ||
                    nativeLocale.getDisplayName(nativeLocale).contains(query, ignoreCase = true) ||
                    it.contains(query, ignoreCase = true)
        }
    }
    val inSheet = clearSearchLabel != null
    val inset = if (inSheet) Modifier.padding(horizontal = 12.dp) else Modifier
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (clearSearchLabel != null) {
            SearchField(
                query = search,
                onQueryChanged = { search = it },
                placeholder = searchLabel,
                closeLabel = clearSearchLabel,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        } else {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                label = { Text(searchLabel) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (filtered.isEmpty()) {
            Text(emptyLabel, modifier = inset.padding(vertical = 12.dp))
        } else LazyColumn(
            Modifier
                .weight(1f, fill = false)
                .heightIn(max = 360.dp)
                .then(inset)
                .then(if (multiple) Modifier else Modifier.selectableGroup()),
            verticalArrangement = Arrangement.spacedBy(if (inSheet) ListItemDefaults.SegmentedGap else 0.dp),
        ) {
            itemsIndexed(filtered, key = { _, code -> code }) { index, code ->
                val checked = code in selected
                if (inSheet) {
                    SegmentedListItem(
                        selected = checked,
                        onClick = { onSelect(code) },
                        shapes = ListItemDefaults.segmentedShapes(index, filtered.size),
                        trailingContent = if (checked) {
                            { Icon(Icons.Outlined.Check, contentDescription = null) }
                        } else null,
                    ) { Text(languageDisplayName(code, locale)) }
                } else Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = checked,
                            role = if (multiple) Role.Checkbox else Role.RadioButton,
                            onClick = { onSelect(code) },
                        )
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (multiple) Checkbox(checked = checked, onCheckedChange = null)
                    else RadioButton(selected = checked, onClick = null)
                    Text(languageDisplayName(code, locale))
                }
            }
        }
    }
}
