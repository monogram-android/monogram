@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.settings.proxy

import android.content.ClipData
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.ProxyModel
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.domain.proxy.MtprotoSecretNormalizer
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsTextField
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyAddEditSheet(
    proxy: ProxyModel?,
    onDismiss: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onSave: (String, Int, ProxyTypeModel) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val clipboard = LocalClipboard.current

    var server by remember { mutableStateOf(proxy?.server ?: "") }
    var port by remember { mutableStateOf(proxy?.port?.toString() ?: "") }
    var type by remember {
        mutableStateOf(
            when (proxy?.type) {
                is ProxyTypeModel.Socks5 -> "SOCKS5"
                is ProxyTypeModel.Http -> "HTTP"
                else -> "MTProto"
            }
        )
    }

    var secret by remember {
        mutableStateOf(
            (proxy?.type as? ProxyTypeModel.Mtproto)?.secret ?: ""
        )
    }
    var username by remember {
        mutableStateOf(
            when (val t = proxy?.type) {
                is ProxyTypeModel.Socks5 -> t.username
                is ProxyTypeModel.Http -> t.username
                else -> ""
            }
        )
    }
    var password by remember {
        mutableStateOf(
            when (val t = proxy?.type) {
                is ProxyTypeModel.Socks5 -> t.password
                is ProxyTypeModel.Http -> t.password
                else -> ""
            }
        )
    }

    val normalizedMtprotoSecret = remember(secret) { MtprotoSecretNormalizer.normalize(secret) }
    val portNumber = port.toIntOrNull()
    val isServerValid = server.isNotBlank()
    val isPortValid = portNumber != null && portNumber in 1..65535
    val isSecretValid = type != "MTProto" || normalizedMtprotoSecret != null

    val currentProxyType = remember(type, normalizedMtprotoSecret, secret, username, password) {
        when (type) {
            "MTProto" -> ProxyTypeModel.Mtproto(normalizedMtprotoSecret ?: secret.trim())
            "SOCKS5" -> ProxyTypeModel.Socks5(username, password)
            else -> ProxyTypeModel.Http(
                username = username,
                password = password,
                httpOnly = (proxy?.type as? ProxyTypeModel.Http)?.httpOnly ?: false
            )
        }
    }

    val isInputValid = isServerValid && isPortValid && isSecretValid

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = if (proxy == null) stringResource(R.string.new_proxy_title) else stringResource(
                    R.string.edit_proxy_title
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = when (type) {
                    "MTProto" -> "Use for Telegram proxy links with a valid secret."
                    "SOCKS5" -> "Best when the server uses optional username and password authentication."
                    else -> "Use for standard HTTP proxy servers."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                Modifier
                    .selectableGroup()
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(50)
                    )
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf("MTProto", "SOCKS5", "HTTP").forEach { text ->
                    val selected = text == type
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .selectable(
                                selected = selected,
                                onClick = { type = text },
                                role = Role.RadioButton
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            SettingsTextField(
                value = server,
                onValueChange = { server = it },
                placeholder = stringResource(R.string.server_address_placeholder),
                icon = Icons.Rounded.Language,
                position = ItemPosition.TOP,
                singleLine = true
            )

            SettingsTextField(
                value = port,
                onValueChange = { if (it.all { char -> char.isDigit() }) port = it },
                placeholder = stringResource(R.string.port_placeholder),
                icon = Icons.Rounded.Numbers,
                position = ItemPosition.BOTTOM,
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (!isServerValid) {
                Text(
                    text = "Server address is required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            if (port.isNotBlank() && !isPortValid) {
                Text(
                    text = "Port must be between 1 and 65535.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            AnimatedContent(targetState = type, label = "TypeFields") { targetType ->
                Column {
                    when (targetType) {
                        "MTProto" -> {
                            SettingsTextField(
                                value = secret,
                                onValueChange = { secret = it },
                                placeholder = stringResource(R.string.secret_hex_placeholder),
                                icon = Icons.Rounded.Key,
                                position = ItemPosition.STANDALONE,
                                singleLine = true
                            )
                        }

                        "SOCKS5", "HTTP" -> {
                            SettingsTextField(
                                value = username,
                                onValueChange = { username = it },
                                placeholder = stringResource(R.string.username_optional_placeholder),
                                icon = Icons.Rounded.Person,
                                position = ItemPosition.TOP,
                                singleLine = true
                            )
                            SettingsTextField(
                                value = password,
                                onValueChange = { password = it },
                                placeholder = stringResource(R.string.password_optional_placeholder),
                                icon = Icons.Rounded.Password,
                                position = ItemPosition.BOTTOM,
                                singleLine = true
                            )
                        }
                    }
                }
            }

            if (type == "MTProto" && secret.isNotBlank() && !isSecretValid) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Enter a valid MTProto secret.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (proxy != null) {
                Spacer(modifier = Modifier.height(20.dp))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        MenuOptionRow(
                            icon = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            title = stringResource(
                                if (isFavorite) R.string.proxy_action_remove_favorite else R.string.proxy_action_set_favorite
                            ),
                            iconTint = if (isFavorite) Color(0xFFFFB300) else MaterialTheme.colorScheme.primary,
                            onClick = onToggleFavorite
                        )
                        MenuOptionRow(
                            icon = Icons.Rounded.ContentCopy,
                            title = stringResource(R.string.proxy_action_copy_link),
                            onClick = {
                                val link = proxyToDeepLink(proxy)
                                clipboard.nativeClipboard.setPrimaryClip(
                                    ClipData.newPlainText("", AnnotatedString(link))
                                )
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.proxy_link_copied),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        MenuOptionRow(
                            icon = Icons.Rounded.Delete,
                            title = stringResource(R.string.proxy_action_delete),
                            textColor = MaterialTheme.colorScheme.error,
                            iconTint = MaterialTheme.colorScheme.error,
                            onClick = onDelete
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        val p = port.toIntOrNull() ?: 443
                        onSave(server, p, currentProxyType)
                    },
                    enabled = isInputValid,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        if (proxy == null) stringResource(R.string.add_proxy_button) else stringResource(
                            R.string.save_changes_button
                        ),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
