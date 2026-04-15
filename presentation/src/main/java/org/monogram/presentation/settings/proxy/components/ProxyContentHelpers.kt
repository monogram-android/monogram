package org.monogram.presentation.settings.proxy

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.ProxyModel
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.domain.repository.ProxyNetworkMode
import org.monogram.domain.repository.ProxyNetworkRule
import org.monogram.domain.repository.ProxyNetworkType
import org.monogram.domain.repository.ProxySmartSwitchMode
import org.monogram.domain.repository.ProxySortMode
import org.monogram.domain.repository.ProxyUnavailableFallback
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal fun itemPosition(index: Int, total: Int): ItemPosition = when {
    total <= 1 -> ItemPosition.STANDALONE
    index == 0 -> ItemPosition.TOP
    index == total - 1 -> ItemPosition.BOTTOM
    else -> ItemPosition.MIDDLE
}

@Composable
internal fun DropdownSelectionTrailing(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.widthIn(max = 180.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            imageVector = Icons.Rounded.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun StyledDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = DpOffset(x = 0.dp, y = 8.dp),
        shape = RoundedCornerShape(22.dp),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 220.dp, max = 320.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                content = content
            )
        }
    }
}

internal fun networkModeIcon(mode: ProxyNetworkMode): ImageVector = when (mode) {
    ProxyNetworkMode.DIRECT -> Icons.Rounded.LinkOff
    ProxyNetworkMode.BEST_PROXY -> Icons.Rounded.Bolt
    ProxyNetworkMode.LAST_USED -> Icons.Rounded.History
    ProxyNetworkMode.SPECIFIC_PROXY -> Icons.Rounded.Tune
}

internal fun sortModeIcon(mode: ProxySortMode): ImageVector = when (mode) {
    ProxySortMode.ACTIVE_FIRST -> Icons.Rounded.CheckCircle
    ProxySortMode.LOWEST_PING -> Icons.Rounded.Speed
    ProxySortMode.SERVER_NAME -> Icons.Rounded.Language
    ProxySortMode.PROXY_TYPE -> Icons.Rounded.Tune
    ProxySortMode.STATUS -> Icons.Rounded.Info
}

internal fun fallbackIcon(fallback: ProxyUnavailableFallback): ImageVector = when (fallback) {
    ProxyUnavailableFallback.BEST_PROXY -> Icons.Rounded.Bolt
    ProxyUnavailableFallback.DIRECT -> Icons.Rounded.LinkOff
    ProxyUnavailableFallback.KEEP_CURRENT -> Icons.Rounded.Pause
}

@StringRes
internal fun networkTitleRes(networkType: ProxyNetworkType): Int = when (networkType) {
    ProxyNetworkType.WIFI -> R.string.proxy_network_wifi
    ProxyNetworkType.MOBILE -> R.string.proxy_network_mobile
    ProxyNetworkType.VPN -> R.string.proxy_network_vpn
    ProxyNetworkType.OTHER -> R.string.proxy_network_other
}

@StringRes
internal fun networkModeLabelRes(mode: ProxyNetworkMode): Int = when (mode) {
    ProxyNetworkMode.DIRECT -> R.string.proxy_network_mode_direct
    ProxyNetworkMode.BEST_PROXY -> R.string.proxy_network_mode_best
    ProxyNetworkMode.LAST_USED -> R.string.proxy_network_mode_last_used
    ProxyNetworkMode.SPECIFIC_PROXY -> R.string.proxy_network_mode_specific
}

@StringRes
internal fun networkRuleSubtitleRes(rule: ProxyNetworkRule): Int = when (rule.mode) {
    ProxyNetworkMode.DIRECT -> R.string.proxy_network_mode_direct_subtitle
    ProxyNetworkMode.BEST_PROXY -> R.string.proxy_network_mode_best_subtitle
    ProxyNetworkMode.LAST_USED -> R.string.proxy_network_mode_last_used_subtitle
    ProxyNetworkMode.SPECIFIC_PROXY -> R.string.proxy_network_mode_specific_subtitle
}

@StringRes
internal fun sortModeLabelRes(mode: ProxySortMode): Int = when (mode) {
    ProxySortMode.ACTIVE_FIRST -> R.string.proxy_sort_mode_active_first
    ProxySortMode.LOWEST_PING -> R.string.proxy_sort_mode_lowest_ping
    ProxySortMode.SERVER_NAME -> R.string.proxy_sort_mode_server_name
    ProxySortMode.PROXY_TYPE -> R.string.proxy_sort_mode_proxy_type
    ProxySortMode.STATUS -> R.string.proxy_sort_mode_status
}

@StringRes
internal fun fallbackLabelRes(fallback: ProxyUnavailableFallback): Int = when (fallback) {
    ProxyUnavailableFallback.BEST_PROXY -> R.string.proxy_fallback_best_proxy
    ProxyUnavailableFallback.DIRECT -> R.string.proxy_fallback_direct
    ProxyUnavailableFallback.KEEP_CURRENT -> R.string.proxy_fallback_keep_current
}

@StringRes
internal fun smartSwitchModeLabelRes(mode: ProxySmartSwitchMode): Int = when (mode) {
    ProxySmartSwitchMode.BEST_PING -> R.string.smart_switch_mode_best_ping
    ProxySmartSwitchMode.RANDOM_AVAILABLE -> R.string.smart_switch_mode_random_available
}

internal fun proxyToDeepLink(proxy: ProxyModel): String {
    fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    return when (val type = proxy.type) {
        is ProxyTypeModel.Mtproto ->
            "tg://proxy?server=${encode(proxy.server)}&port=${proxy.port}&secret=${encode(type.secret)}"

        is ProxyTypeModel.Socks5 -> buildString {
            append("tg://socks?server=${encode(proxy.server)}&port=${proxy.port}")
            if (type.username.isNotBlank()) append("&user=${encode(type.username)}")
            if (type.password.isNotBlank()) append("&pass=${encode(type.password)}")
        }

        is ProxyTypeModel.Http -> buildString {
            append("tg://http?server=${encode(proxy.server)}&port=${proxy.port}")
            if (type.username.isNotBlank()) append("&user=${encode(type.username)}")
            if (type.password.isNotBlank()) append("&pass=${encode(type.password)}")
        }
    }
}
