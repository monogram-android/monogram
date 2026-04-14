@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.settings.proxy

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import kotlinx.coroutines.launch
import org.monogram.domain.repository.ProxyNetworkMode
import org.monogram.domain.repository.ProxyNetworkRule
import org.monogram.domain.repository.ProxyNetworkType
import org.monogram.domain.repository.ProxySmartSwitchMode
import org.monogram.domain.repository.ProxySortMode
import org.monogram.domain.repository.ProxyUnavailableFallback
import org.monogram.domain.repository.defaultProxyNetworkMode
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.IntegratedQRScanner
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsSwitchTile
import org.monogram.presentation.core.ui.SettingsTile
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow
import org.monogram.presentation.features.viewers.components.ViewerSettingsDropdown

private enum class ProxyTab(
    val titleRes: Int,
    val icon: ImageVector
) {
    Proxy(
        titleRes = R.string.proxy_tab_proxy,
        icon = Icons.Rounded.Language
    ),
    DcPing(
        titleRes = R.string.proxy_tab_dc_ping,
        icon = Icons.Rounded.Bolt
    ),
    Settings(
        titleRes = R.string.proxy_tab_settings,
        icon = Icons.Rounded.Tune
    )
}

private data class DcDescriptor(
    val id: Int,
    val name: String,
    val location: String
)

private val DcCatalog = listOf(
    DcDescriptor(id = 1, name = "Pluto", location = "Miami"),
    DcDescriptor(id = 2, name = "Venus", location = "Amsterdam"),
    DcDescriptor(id = 3, name = "Aurora", location = "Miami"),
    DcDescriptor(id = 4, name = "Vesta", location = "Amsterdam"),
    DcDescriptor(id = 5, name = "Flora", location = "Singapore")
)

@Composable
private fun DcPingRow(
    dc: DcDescriptor,
    ping: Long?,
    error: String?,
    isChecking: Boolean,
) {
    val statusColor = when {
        isChecking -> MaterialTheme.colorScheme.primary
        ping == null || ping < 0L -> MaterialTheme.colorScheme.error
        ping < 250L -> Color(0xFF2E7D32)
        ping < 700L -> Color(0xFFF9A825)
        else -> MaterialTheme.colorScheme.error
    }

    val statusText = when {
        isChecking -> stringResource(R.string.proxy_checking)
        ping == null || ping < 0L -> stringResource(R.string.proxy_offline)
        else -> stringResource(R.string.proxy_ping_format, ping.toInt())
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "DC${dc.id} ${dc.name}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = error ?: dc.location,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = statusColor.copy(alpha = 0.16f)
            ) {
                Text(
                    text = statusText,
                    color = statusColor,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProxyContent(component: ProxyComponent) {
    val state by component.state.subscribeAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val activeProxy = state.proxies.firstOrNull { it.isEnabled }
    val prioritizedVisibleProxies = remember(state.visibleProxies) {
        state.visibleProxies.sortedByDescending { it.isEnabled }
    }
    var expandedNetworkMenu by remember { mutableStateOf<ProxyNetworkType?>(null) }
    var smartSwitchModeMenuExpanded by remember { mutableStateOf(false) }
    var smartSwitchCheckIntervalMenuExpanded by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var fallbackMenuExpanded by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(ProxyTab.Proxy) }
    val smartSwitchCheckIntervalOptions = remember { listOf(1, 2, 5, 10, 15, 30, 60) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showQrScanner = true
        }
    }

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(component.exportProxiesJson())
                }
            }.onSuccess {
                uiScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.proxy_export_success))
                }
            }.onFailure {
                uiScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.proxy_export_failed))
                }
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()
                    ?.use { it.readText() }.orEmpty()
            }.onSuccess { json ->
                component.importProxiesJson(json)
            }.onFailure {
                uiScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.proxy_import_failed))
                }
            }
        }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            component.onDismissToast()
        }
    }

    LaunchedEffect(state.isAutoBestProxyEnabled) {
        if (!state.isAutoBestProxyEnabled) {
            smartSwitchModeMenuExpanded = false
            smartSwitchCheckIntervalMenuExpanded = false
        }
    }

    fun startQrScanWithPermission() {
        when (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)) {
            PackageManager.PERMISSION_GRANTED -> showQrScanner = true
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun importFromClipboard() {
        val clip = clipboard.nativeClipboard.primaryClip
        val rawText = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).text?.toString().orEmpty()
        } else {
            ""
        }
        component.importProxiesFromText(rawText)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.proxy_settings_header),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (showQrScanner) showQrScanner = false else component.onBackClicked()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back)
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                if (selectedTab == ProxyTab.DcPing) component.onPingDatacenters()
                                else component.onPingAll()
                            }
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.refresh_pings_cd)
                            )
                        }
                        IconButton(onClick = { showTopMenu = true }) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.more_options_cd)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background
                    )
                )

                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .selectableGroup()
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ProxyTab.entries.forEach { tab ->
                        val selected = selectedTab == tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .selectable(
                                    selected = selected,
                                    onClick = { selectedTab = tab },
                                    role = Role.Tab
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(tab.titleRes),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == ProxyTab.Proxy) {
                ExtendedFloatingActionButton(
                    onClick = { component.onAddProxyClicked() },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.add_proxy_button)) }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (selectedTab == ProxyTab.Proxy) {
                item {
                    ProxyConnectionSummaryCard(
                        activeProxy = activeProxy,
                        checkingProxyIds = state.checkingProxyIds,
                        proxyErrors = state.proxyErrors,
                        isAutoBestProxyEnabled = state.isAutoBestProxyEnabled,
                        proxyCount = state.proxies.size,
                        onRefresh = component::onPingAll,
                        onPrimaryAction = {
                            if (activeProxy != null) {
                                component.onDisableProxy()
                            } else {
                                component.onAddProxyClicked()
                            }
                        }
                    )
                }
            }

            if (selectedTab == ProxyTab.Settings) {
            item {
                SectionHeader(stringResource(R.string.connection_section_header))
            }
            item {
                SettingsSwitchTile(
                    icon = Icons.Rounded.Bolt,
                    title = stringResource(R.string.smart_switching_title),
                    subtitle = stringResource(R.string.smart_switching_subtitle),
                    checked = state.isAutoBestProxyEnabled,
                    iconColor = Color(0xFFAF52DE),
                    position = ItemPosition.TOP,
                    onCheckedChange = component::onAutoBestProxyToggled
                )

                Box {
                    SettingsTile(
                        icon = Icons.Rounded.SwapHoriz,
                        title = stringResource(R.string.smart_switch_mode_title),
                        subtitle = stringResource(R.string.smart_switch_mode_subtitle),
                        iconColor = Color(0xFF7E57C2),
                        position = ItemPosition.MIDDLE,
                        onClick = { smartSwitchModeMenuExpanded = true },
                        enabled = state.isAutoBestProxyEnabled,
                        trailingContent = {
                            DropdownSelectionTrailing(
                                text = stringResource(
                                    smartSwitchModeLabelRes(state.smartSwitchMode)
                                )
                            )
                        }
                    )

                    StyledDropdownMenu(
                        expanded = smartSwitchModeMenuExpanded,
                        onDismissRequest = { smartSwitchModeMenuExpanded = false }
                    ) {
                        ProxySmartSwitchMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Bolt,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                text = { Text(stringResource(smartSwitchModeLabelRes(mode))) },
                                trailingIcon = {
                                    if (state.smartSwitchMode == mode) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                },
                                onClick = {
                                    component.onSmartSwitchModeChanged(mode)
                                    smartSwitchModeMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                Box {
                    SettingsTile(
                        icon = Icons.Rounded.Refresh,
                        title = stringResource(R.string.smart_switch_check_interval_title),
                        subtitle = stringResource(R.string.smart_switch_check_interval_subtitle),
                        iconColor = Color(0xFF1E88E5),
                        position = ItemPosition.MIDDLE,
                        onClick = { smartSwitchCheckIntervalMenuExpanded = true },
                        enabled = state.isAutoBestProxyEnabled,
                        trailingContent = {
                            DropdownSelectionTrailing(
                                text = stringResource(
                                    R.string.smart_switch_check_interval_format,
                                    state.smartSwitchAutoCheckIntervalMinutes
                                )
                            )
                        }
                    )

                    StyledDropdownMenu(
                        expanded = smartSwitchCheckIntervalMenuExpanded,
                        onDismissRequest = { smartSwitchCheckIntervalMenuExpanded = false }
                    ) {
                        smartSwitchCheckIntervalOptions.forEach { minutes ->
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                text = {
                                    Text(
                                        stringResource(
                                            R.string.smart_switch_check_interval_format,
                                            minutes
                                        )
                                    )
                                },
                                trailingIcon = {
                                    if (state.smartSwitchAutoCheckIntervalMinutes == minutes) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                },
                                onClick = {
                                    component.onSmartSwitchAutoCheckIntervalChanged(minutes)
                                    smartSwitchCheckIntervalMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                SettingsSwitchTile(
                    icon = Icons.Rounded.Public,
                    title = stringResource(R.string.prefer_ipv6_title),
                    subtitle = stringResource(R.string.prefer_ipv6_subtitle),
                    checked = state.preferIpv6,
                    iconColor = Color(0xFF34A853),
                    position = ItemPosition.MIDDLE,
                    onCheckedChange = component::onPreferIpv6Toggled
                )

                val isDirect = state.proxies.none { it.isEnabled }
                SettingsTile(
                    icon = Icons.Rounded.LinkOff,
                    title = stringResource(R.string.disable_proxy_title),
                    subtitle = if (isDirect) stringResource(R.string.connected_directly_subtitle) else stringResource(
                        R.string.switch_to_direct_subtitle
                    ),
                    iconColor = if (isDirect) Color(0xFF34A853) else MaterialTheme.colorScheme.error,
                    position = ItemPosition.BOTTOM,
                    onClick = { component.onDisableProxy() },
                    trailingContent = {
                        if (isDirect) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color(0xFF34A853)
                            )
                        }
                    }
                )
            }

            item {
                SectionHeader(
                    text = stringResource(R.string.proxy_network_rules_header),
                    subtitle = stringResource(R.string.proxy_network_rules_subtitle)
                )
            }

            item {
                ProxyNetworkType.entries.forEachIndexed { index, networkType ->
                    val rule = state.proxyNetworkRules[networkType] ?: ProxyNetworkRule(
                        defaultProxyNetworkMode(networkType)
                    )
                    val position = itemPosition(index, ProxyNetworkType.entries.size)
                    Box {
                        SettingsTile(
                            icon = Icons.Rounded.Wifi,
                            title = stringResource(networkTitleRes(networkType)),
                            subtitle = stringResource(networkRuleSubtitleRes(rule)),
                            iconColor = Color(0xFF1E88E5),
                            position = position,
                            onClick = { expandedNetworkMenu = networkType },
                            trailingContent = {
                                DropdownSelectionTrailing(
                                    text = stringResource(
                                        networkModeLabelRes(rule.mode)
                                    )
                                )
                            }
                        )

                        StyledDropdownMenu(
                            expanded = expandedNetworkMenu == networkType,
                            onDismissRequest = { expandedNetworkMenu = null }
                        ) {
                            ProxyNetworkMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            imageVector = networkModeIcon(mode),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    text = { Text(stringResource(networkModeLabelRes(mode))) },
                                    trailingIcon = {
                                        if (rule.mode == mode) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    },
                                    onClick = {
                                        component.onProxyNetworkModeChanged(networkType, mode)
                                        expandedNetworkMenu = null
                                    }
                                )
                            }
                            if (state.proxies.isNotEmpty()) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                                state.proxies.forEach { proxy ->
                                    DropdownMenuItem(
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Rounded.Tune,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        },
                                        text = {
                                            Text(
                                                stringResource(
                                                    R.string.proxy_specific_target_format,
                                                    proxy.server,
                                                    proxy.port
                                                )
                                            )
                                        },
                                        trailingIcon = {
                                            if (rule.mode == ProxyNetworkMode.SPECIFIC_PROXY && rule.specificProxyId == proxy.id) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        },
                                        onClick = {
                                            component.onSpecificProxyForNetworkSelected(
                                                networkType,
                                                proxy.id
                                            )
                                            expandedNetworkMenu = null
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                SectionHeader(stringResource(R.string.proxy_list_behavior_header))
            }

            item {
                Box {
                    SettingsTile(
                        icon = Icons.AutoMirrored.Rounded.Sort,
                        title = stringResource(R.string.proxy_sort_mode_title),
                        subtitle = stringResource(R.string.proxy_sort_mode_subtitle),
                        iconColor = Color(0xFFF9A825),
                        position = ItemPosition.TOP,
                        onClick = { sortMenuExpanded = true },
                        trailingContent = {
                            DropdownSelectionTrailing(
                                text = stringResource(
                                    sortModeLabelRes(
                                        state.proxySortMode
                                    )
                                )
                            )
                        }
                    )

                    StyledDropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false }
                    ) {
                        ProxySortMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = sortModeIcon(mode),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                text = { Text(stringResource(sortModeLabelRes(mode))) },
                                trailingIcon = {
                                    if (state.proxySortMode == mode) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                },
                                onClick = {
                                    component.onProxySortModeChanged(mode)
                                    sortMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                Box {
                    SettingsTile(
                        icon = Icons.Rounded.SwapHoriz,
                        title = stringResource(R.string.proxy_unavailable_fallback_title),
                        subtitle = stringResource(R.string.proxy_unavailable_fallback_subtitle),
                        iconColor = Color(0xFF6A1B9A),
                        position = ItemPosition.MIDDLE,
                        onClick = { fallbackMenuExpanded = true },
                        trailingContent = {
                            DropdownSelectionTrailing(
                                text = stringResource(
                                    fallbackLabelRes(
                                        state.proxyUnavailableFallback
                                    )
                                )
                            )
                        }
                    )

                    StyledDropdownMenu(
                        expanded = fallbackMenuExpanded,
                        onDismissRequest = { fallbackMenuExpanded = false }
                    ) {
                        ProxyUnavailableFallback.entries.forEach { fallback ->
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = fallbackIcon(fallback),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                text = { Text(stringResource(fallbackLabelRes(fallback))) },
                                trailingIcon = {
                                    if (state.proxyUnavailableFallback == fallback) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                },
                                onClick = {
                                    component.onProxyUnavailableFallbackChanged(fallback)
                                    fallbackMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                SettingsSwitchTile(
                    icon = Icons.Rounded.VisibilityOff,
                    title = stringResource(R.string.hide_offline_proxies_title),
                    subtitle = stringResource(R.string.hide_offline_proxies_subtitle),
                    checked = state.hideOfflineProxies,
                    iconColor = Color(0xFF00897B),
                    position = ItemPosition.BOTTOM,
                    onCheckedChange = component::onHideOfflineProxiesToggled
                )
            }
            }

            if (selectedTab == ProxyTab.DcPing) {
                val activeProxyForDc = state.proxies.firstOrNull { it.isEnabled }
                val dcOrder = DcCatalog.sortedBy { it.id }
                val readyResults = dcOrder.mapNotNull { dc ->
                    state.dcPingByDcId[dc.id]?.takeIf { it >= 0L }
                }
                val successCount = dcOrder.count { dc ->
                    (state.dcPingByDcId[dc.id] ?: -1L) >= 0L
                }
                val averagePing =
                    if (readyResults.isNotEmpty()) readyResults.average().toLong() else null
                item {
                    SectionHeader(
                        text = stringResource(R.string.proxy_dc_ping_header),
                        subtitle = if (activeProxyForDc != null) {
                            stringResource(
                                R.string.proxy_dc_ping_route_proxy,
                                activeProxyForDc.server,
                                activeProxyForDc.port
                            )
                        } else {
                            stringResource(R.string.proxy_dc_ping_route_direct)
                        }
                    )
                }

                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.proxy_dc_ping_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface
                                ) {
                                    Column(
                                        modifier = Modifier.padding(
                                            horizontal = 12.dp,
                                            vertical = 10.dp
                                        ),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.proxy_dc_ping_reachable),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "$successCount/${dcOrder.size}",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface
                                ) {
                                    Column(
                                        modifier = Modifier.padding(
                                            horizontal = 12.dp,
                                            vertical = 10.dp
                                        ),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.proxy_dc_ping_average),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = averagePing?.let {
                                                stringResource(
                                                    R.string.proxy_ping_format,
                                                    it.toInt()
                                                )
                                            } ?: "—",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                            OutlinedButton(
                                onClick = component::onPingDatacenters,
                                enabled = !state.isDcTesting,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (state.isDcTesting) {
                                    LoadingIndicator(modifier = Modifier.size(18.dp))
                                } else {
                                    Text(stringResource(R.string.proxy_dc_ping_run))
                                }
                            }
                        }
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        dcOrder.forEach { dc ->
                            val ping = state.dcPingByDcId[dc.id]
                            val error = state.dcPingErrorsByDcId[dc.id]
                            val isChecking =
                                state.isDcTesting && !state.dcPingByDcId.containsKey(dc.id)
                            DcPingRow(
                                dc = dc,
                                ping = ping,
                                error = error,
                                isChecking = isChecking
                            )
                        }
                    }
                }
            }

            if (selectedTab == ProxyTab.Proxy) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, bottom = 8.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (state.proxies.isNotEmpty()) {
                            "${stringResource(R.string.your_proxies_header)} (${prioritizedVisibleProxies.size})"
                        } else {
                            stringResource(R.string.your_proxies_header)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

                itemsIndexed(prioritizedVisibleProxies, key = { _, it -> it.id }) { index, proxy ->
                val position = when {
                    prioritizedVisibleProxies.size == 1 -> ItemPosition.STANDALONE
                    index == 0 -> ItemPosition.TOP
                    index == prioritizedVisibleProxies.size - 1 -> ItemPosition.BOTTOM
                    else -> ItemPosition.MIDDLE
                }

                SwipeToDeleteContainer(
                    onDelete = { component.onRemoveProxy(proxy.id) }
                ) {
                    ProxyItem(
                        proxy = proxy,
                        errorMessage = state.proxyErrors[proxy.id],
                        isChecking = proxy.id in state.checkingProxyIds,
                        isFavorite = state.favoriteProxyId == proxy.id,
                        position = position,
                        onClick = { component.onProxyClicked(proxy) },
                        onLongClick = { component.onProxyLongClicked(proxy) },
                        onRefreshPing = { component.onPingProxy(proxy.id) },
                        onOpenMenu = { component.onEditProxyClicked(proxy) }
                    )
                }
            }

                if (prioritizedVisibleProxies.isEmpty() && !state.isLoading) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Rounded.Language,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(if (state.proxies.isEmpty()) R.string.no_proxies_label else R.string.no_proxies_match_filter_label),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = component::onAddProxyClicked) {
                            Text(stringResource(R.string.add_proxy_button))
                        }
                    }
                }
                }
            }
        }
    }

    if (showTopMenu) {
        Popup(
            onDismissRequest = { showTopMenu = false },
            properties = PopupProperties(focusable = true)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showTopMenu = false }
            ) {
                var isVisible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { isVisible = true }

                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) + scaleIn(
                        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        initialScale = 0.8f,
                        transformOrigin = TransformOrigin(1f, 0f)
                    ),
                    exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) + scaleOut(
                        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                        targetScale = 0.9f,
                        transformOrigin = TransformOrigin(1f, 0f)
                    ),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 56.dp, end = 16.dp)
                ) {
                    ViewerSettingsDropdown {
                        MenuOptionRow(
                            icon = Icons.Rounded.ContentPaste,
                            title = stringResource(R.string.proxy_paste_from_clipboard_action),
                            onClick = {
                                showTopMenu = false
                                importFromClipboard()
                            }
                        )
                        MenuOptionRow(
                            icon = Icons.Rounded.QrCodeScanner,
                            title = stringResource(R.string.proxy_scan_qr_action),
                            onClick = {
                                showTopMenu = false
                                startQrScanWithPermission()
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        MenuOptionRow(
                            icon = Icons.Rounded.Upload,
                            title = stringResource(R.string.proxy_export_action),
                            onClick = {
                                showTopMenu = false
                                exportLauncher.launch("monogram_proxies.json")
                            }
                        )
                        MenuOptionRow(
                            icon = Icons.Rounded.Download,
                            title = stringResource(R.string.proxy_import_action),
                            onClick = {
                                showTopMenu = false
                                importLauncher.launch(arrayOf("application/json", "text/plain"))
                            }
                        )
                        if (state.proxies.isNotEmpty()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            MenuOptionRow(
                                icon = Icons.Rounded.DeleteSweep,
                                title = stringResource(R.string.clear_offline_cd),
                                onClick = {
                                    showTopMenu = false
                                    component.onClearUnavailableProxies()
                                },
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            MenuOptionRow(
                                icon = Icons.Rounded.DeleteForever,
                                title = stringResource(R.string.remove_all_cd),
                                onClick = {
                                    showTopMenu = false
                                    component.onRemoveAllProxies()
                                },
                                iconTint = MaterialTheme.colorScheme.error,
                                textColor = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    if (showQrScanner) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            IntegratedQRScanner(
                onCodeDetected = { code ->
                    showQrScanner = false
                    component.importProxiesFromText(code)
                },
                onBackClicked = { showQrScanner = false }
            )
        }
    }

    if (state.isAddingProxy || state.proxyToEdit != null) {
        ProxyAddEditSheet(
            proxy = state.proxyToEdit,
            onDismiss = component::onDismissAddEdit,
            isFavorite = state.proxyToEdit?.id == state.favoriteProxyId,
            onToggleFavorite = {
                state.proxyToEdit?.let { component.onToggleFavoriteProxy(it.id) }
            },
            onDelete = {
                state.proxyToEdit?.let {
                    component.onRemoveProxy(it.id)
                    component.onDismissAddEdit()
                }
            },
            onSave = { server, port, type ->
                if (state.proxyToEdit != null) {
                    component.onEditProxy(state.proxyToEdit!!.id, server, port, type)
                } else {
                    component.onAddProxy(server, port, type)
                }
            }
        )
    }

    state.proxyToDelete?.let { proxy ->
        AlertDialog(
            onDismissRequest = component::onDismissDeleteConfirmation,
            title = { Text(stringResource(R.string.delete_proxy_title)) },
            text = { Text(stringResource(R.string.delete_proxy_confirmation_format, proxy.server)) },
            confirmButton = {
                TextButton(
                    onClick = component::onConfirmDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = component::onDismissDeleteConfirmation) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

    if (state.showClearOfflineConfirmation) {
        AlertDialog(
            onDismissRequest = component::onDismissMassDeleteDialogs,
            title = { Text(stringResource(R.string.clear_offline_title)) },
            text = { Text(stringResource(R.string.clear_offline_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = component::onConfirmClearUnavailableProxies,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = component::onDismissMassDeleteDialogs) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

    if (state.showRemoveAllConfirmation) {
        AlertDialog(
            onDismissRequest = component::onDismissMassDeleteDialogs,
            title = { Text(stringResource(R.string.remove_all_proxies_title)) },
            text = { Text(stringResource(R.string.remove_all_proxies_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = component::onConfirmRemoveAllProxies,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = component::onDismissMassDeleteDialogs) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

}

