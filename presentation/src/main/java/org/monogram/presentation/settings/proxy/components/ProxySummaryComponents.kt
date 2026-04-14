package org.monogram.presentation.settings.proxy

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.ProxyModel
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.presentation.R

@Composable
internal fun ProxyConnectionSummaryCard(
    activeProxy: ProxyModel?,
    checkingProxyIds: Set<Int>,
    proxyErrors: Map<Int, String>,
    isAutoBestProxyEnabled: Boolean,
    proxyCount: Int,
    onRefresh: () -> Unit,
    onPrimaryAction: () -> Unit
) {
    val ping = activeProxy?.ping
    val isChecking = activeProxy?.id in checkingProxyIds
    val errorMessage = activeProxy?.id?.let(proxyErrors::get)
    val offlineLabel = stringResource(R.string.proxy_offline)
    val detailErrorMessage = errorMessage
        ?.takeIf { it.isNotBlank() }
        ?.takeUnless { it.equals(offlineLabel, ignoreCase = true) }
    val statusText = when {
        isChecking -> stringResource(R.string.proxy_checking)
        !errorMessage.isNullOrBlank() -> stringResource(R.string.proxy_offline)
        ping != null && ping >= 0L -> stringResource(R.string.proxy_ping_format, ping.toInt())
        activeProxy != null -> stringResource(R.string.proxy_enabled)
        else -> stringResource(R.string.proxy_network_mode_direct)
    }
    val subtitle = when {
        !detailErrorMessage.isNullOrBlank() -> detailErrorMessage
        activeProxy != null -> buildString {
            append(
                when (activeProxy.type) {
                    is ProxyTypeModel.Mtproto -> "MTProto"
                    is ProxyTypeModel.Socks5 -> "SOCKS5"
                    is ProxyTypeModel.Http -> "HTTP"
                }
            )
            append(" • ")
            append(activeProxy.server)
            append(':')
            append(activeProxy.port)
        }

        proxyCount == 0 -> stringResource(R.string.no_proxies_label)
        isAutoBestProxyEnabled -> stringResource(R.string.smart_switching_subtitle)
        else -> stringResource(R.string.proxy_network_mode_direct_subtitle)
    }
    val containerColor by animateColorAsState(
        targetValue = if (activeProxy != null) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "summaryContainerColor"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        shape = RoundedCornerShape(22.dp),
        color = containerColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = if (activeProxy != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (activeProxy != null) Icons.Rounded.CheckCircle else Icons.Rounded.Language,
                        contentDescription = null,
                        tint = if (activeProxy != null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = activeProxy?.server
                            ?: if (proxyCount == 0) {
                                stringResource(R.string.no_proxies_label)
                            } else {
                                stringResource(R.string.connected_directly_subtitle)
                            },
                        transitionSpec = {
                            fadeIn() + scaleIn(initialScale = 0.96f) togetherWith fadeOut() + scaleOut(
                                targetScale = 0.96f
                            )
                        },
                        label = "summaryTitle"
                    ) { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                AnimatedContent(
                    targetState = statusText,
                    transitionSpec = {
                        fadeIn() + scaleIn(initialScale = 0.9f) togetherWith fadeOut() + scaleOut(
                            targetScale = 0.9f
                        )
                    },
                    label = "statusPillTransition"
                ) { animatedStatus ->
                    ProxyStatusPill(
                        text = animatedStatus,
                        backgroundColor = when {
                            isChecking -> MaterialTheme.colorScheme.surfaceVariant
                            !errorMessage.isNullOrBlank() -> MaterialTheme.colorScheme.errorContainer
                            activeProxy != null -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = when {
                            !errorMessage.isNullOrBlank() -> MaterialTheme.colorScheme.onErrorContainer
                            activeProxy != null -> MaterialTheme.colorScheme.onPrimary
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.refresh_list_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = 12.sp
                    )
                }

                Button(
                    onClick = onPrimaryAction,
                    modifier = Modifier.weight(1f),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding
                ) {
                    Icon(
                        imageVector = if (activeProxy != null) Icons.Rounded.LinkOff else Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (activeProxy != null) {
                            stringResource(R.string.disable_proxy_title)
                        } else {
                            stringResource(R.string.add_proxy_button)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
internal fun ProxyStatusPill(
    text: String,
    backgroundColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}
