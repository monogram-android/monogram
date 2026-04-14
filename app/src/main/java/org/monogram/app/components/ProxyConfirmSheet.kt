package org.monogram.app.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.app.R
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.presentation.root.RootComponent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyConfirmSheet(root: RootComponent) {
    val proxyConfirmState by root.proxyToConfirm.collectAsState()
    if (proxyConfirmState.server != null) {
        ModalBottomSheet(
            onDismissRequest = root::dismissProxyConfirm,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = stringResource(R.string.proxy_details),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                Text(
                    text = stringResource(R.string.proxy_add_connect),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        DetailRow(stringResource(R.string.proxy_server), proxyConfirmState.server!!)
                        DetailRow(stringResource(R.string.proxy_port), proxyConfirmState.port!!.toString())
                        val typeName = when (proxyConfirmState.type) {
                            is ProxyTypeModel.Mtproto -> "MTProto"
                            is ProxyTypeModel.Socks5 -> "SOCKS5"
                            is ProxyTypeModel.Http -> "HTTP"
                            else -> stringResource(R.string.proxy_unknown)
                        }
                        DetailRow(stringResource(R.string.proxy_type), typeName)

                        val ping = proxyConfirmState.ping
                        val (statusText, statusColor) = when {
                            proxyConfirmState.isChecking -> "Checking..." to MaterialTheme.colorScheme.onSurfaceVariant
                            ping == null -> "Unknown" to MaterialTheme.colorScheme.onSurfaceVariant
                            ping < 0L -> "Offline" to MaterialTheme.colorScheme.error
                            else -> "${ping} ms" to Color(0xFF34A853)
                        }
                        DetailRow("Status", statusText, statusColor)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = root::dismissProxyConfirm,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Button(
                        onClick = {
                            root.confirmProxy(
                                proxyConfirmState.server!!,
                                proxyConfirmState.port!!,
                                proxyConfirmState.type!!,
                            )
                        },
                        enabled = !proxyConfirmState.isChecking && proxyConfirmState.ping != -1L,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.connect),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = valueColor,
        )
    }
}
