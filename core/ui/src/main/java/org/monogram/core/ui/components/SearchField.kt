package org.monogram.core.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.loading.MonogramLoadingInlineSize

@Composable
fun SearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    placeholder: String,
    closeLabel: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    busy: Boolean = false,
    busyLabel: String? = null,
) {
    TextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        singleLine = true,
        placeholder = { Text(placeholder) },
        interactionSource = remember { MutableInteractionSource() },
        trailingIcon = {
            when {
                busy -> MonogramLoading(
                    visible = true,
                    size = MonogramLoadingInlineSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    status = busyLabel,
                )
                query.isNotEmpty() -> IconButton(onClick = { onQueryChanged("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = closeLabel)
                }
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    )
}
