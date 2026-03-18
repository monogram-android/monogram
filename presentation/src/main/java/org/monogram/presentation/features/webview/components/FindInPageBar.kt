package org.monogram.presentation.features.webview.components

import android.webkit.WebView
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.features.chats.chatList.components.SettingsTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindInPageBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    activeMatchIndex: Int,
    webView: WebView?,
    onClose: () -> Unit
) {
    TopAppBar(
        title = {
            SettingsTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.webview_find_placeholder),
                icon = Icons.Rounded.Search,
                position = ItemPosition.STANDALONE,
                singleLine = true,
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (matchCount > 0) {
                            Text(
                                "${activeMatchIndex + 1}/$matchCount",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                        IconButton(onClick = { webView?.findNext(false) }) {
                            Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.webview_find_previous))
                        }
                        IconButton(onClick = { webView?.findNext(true) }) {
                            Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.webview_find_next))
                        }
                    }
                }
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, stringResource(R.string.webview_find_close))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        )
    )
}
