package org.monogram.presentation.features.webview.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.presentation.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewTopBar(
    title: String,
    displayUrl: String,
    isSecure: Boolean,
    onDismiss: () -> Unit,
    onMoreOptions: () -> Unit,
    onCertificateClick: () -> Unit
) {
    TopAppBar(
        title = {
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.clickable { onCertificateClick() }
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isSecure) Icons.Rounded.Lock else Icons.Rounded.Public,
                        contentDescription = stringResource(if (isSecure) R.string.webview_secure else R.string.webview_insecure),
                        modifier = Modifier.size(12.dp),
                        tint = if (isSecure) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = displayUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.webview_close))
            }
        },
        actions = {
            IconButton(onClick = onMoreOptions) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.webview_more_options))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}
