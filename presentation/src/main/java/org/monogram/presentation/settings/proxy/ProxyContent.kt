@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.settings.proxy

import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.domain.models.ProxyModel
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.domain.proxy.MtprotoSecretNormalizer
import org.monogram.domain.repository.ProxyNetworkMode
import org.monogram.domain.repository.ProxyNetworkRule
import org.monogram.domain.repository.ProxyNetworkType
import org.monogram.domain.repository.ProxySortMode
import org.monogram.domain.repository.ProxyUnavailableFallback
import org.monogram.domain.repository.defaultProxyNetworkMode
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsSwitchTile
import org.monogram.presentation.core.ui.SettingsTile
import org.monogram.presentation.features.chats.chatList.components.SettingsTextField
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow
import org.monogram.presentation.features.viewers.components.ViewerSettingsDropdown
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProxyContent(component: ProxyComponent) {
    val state by component.state.subscribeAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LocalClipboard.current
    var expandedNetworkMenu by remember { mutableStateOf<ProxyNetworkType?>(null) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var fallbackMenuExpanded by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(component.exportProxiesJson())
                }
            }.onSuccess {
                Toast.makeText(
                    context,
                    context.getString(R.string.proxy_export_success),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure {
                Toast.makeText(
                    context,
                    context.getString(R.string.proxy_export_failed),
                    Toast.LENGTH_SHORT
                ).show()
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
                Toast.makeText(
                    context,
                    context.getString(R.string.proxy_import_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            component.onDismissToast()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.proxy_settings_header),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = component::onBackClicked) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = component::onPingAll) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.refresh_pings_cd))
                    }
                    IconButton(onClick = { showTopMenu = true }) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.more_options_cd)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { component.onAddProxyClicked() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_proxy_button)) }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                SectionHeader(stringResource(R.string.connection_section_header))
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    SettingsSwitchTile(
                        icon = Icons.Rounded.Bolt,
                        title = stringResource(R.string.smart_switching_title),
                        subtitle = stringResource(R.string.smart_switching_subtitle),
                        checked = state.isAutoBestProxyEnabled,
                        iconColor = Color(0xFFAF52DE),
                        position = ItemPosition.TOP,
                        onCheckedChange = component::onAutoBestProxyToggled
                    )

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
                                Icon(Icons.Rounded.Check, contentDescription = null, tint = Color(0xFF34A853))
                            }
                        }
                    )
                }
            }

            item {
                SectionHeader(
                    text = stringResource(R.string.proxy_network_rules_header),
                    subtitle = stringResource(R.string.proxy_network_rules_subtitle)
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                ) {
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
            }

            item {
                SectionHeader(stringResource(R.string.proxy_list_behavior_header))
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Box {
                        SettingsTile(
                            icon = Icons.Rounded.Sort,
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

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, bottom = 8.dp, top = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.your_proxies_header),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (state.proxies.isNotEmpty()) {
                        Row {
                            IconButton(onClick = { component.onClearUnavailableProxies() }) {
                                Icon(
                                    Icons.Rounded.DeleteSweep,
                                    stringResource(R.string.clear_offline_cd),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { component.onRemoveAllProxies() }) {
                                Icon(
                                    Icons.Rounded.DeleteForever,
                                    stringResource(R.string.remove_all_cd),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            itemsIndexed(state.visibleProxies, key = { _, it -> it.id }) { index, proxy ->
                val position = when {
                    state.visibleProxies.size == 1 -> ItemPosition.STANDALONE
                    index == 0 -> ItemPosition.TOP
                    index == state.visibleProxies.size - 1 -> ItemPosition.BOTTOM
                    else -> ItemPosition.MIDDLE
                }

                SwipeToDeleteContainer(
                    onDelete = { component.onRemoveProxy(proxy.id) }
                ) {
                    ProxyItem(
                        proxy = proxy,
                        errorMessage = state.proxyErrors[proxy.id],
                        isFavorite = state.favoriteProxyId == proxy.id,
                        position = position,
                        onClick = { component.onProxyClicked(proxy) },
                        onLongClick = { component.onProxyLongClicked(proxy) },
                        onRefreshPing = { component.onPingProxy(proxy.id) },
                        onOpenMenu = { component.onEditProxyClicked(proxy) }
                    )
                }
            }

            if (state.visibleProxies.isEmpty() && !state.isLoading) {
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
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
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
                    }
                }
            }
        }
    }

    if (state.isAddingProxy || state.proxyToEdit != null) {
        ProxyAddEditSheet(
            proxy = state.proxyToEdit,
            onDismiss = component::onDismissAddEdit,
            onTest = component::onTestProxy,
            testPing = state.testPing,
            testError = state.testError,
            isTesting = state.isTesting,
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

private fun itemPosition(index: Int, total: Int): ItemPosition {
    return when {
        total <= 1 -> ItemPosition.STANDALONE
        index == 0 -> ItemPosition.TOP
        index == total - 1 -> ItemPosition.BOTTOM
        else -> ItemPosition.MIDDLE
    }
}

@Composable
private fun DropdownSelectionTrailing(text: String) {
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
private fun StyledDropdownMenu(
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

private fun networkModeIcon(mode: ProxyNetworkMode): ImageVector = when (mode) {
    ProxyNetworkMode.DIRECT -> Icons.Rounded.LinkOff
    ProxyNetworkMode.BEST_PROXY -> Icons.Rounded.Bolt
    ProxyNetworkMode.LAST_USED -> Icons.Rounded.History
    ProxyNetworkMode.SPECIFIC_PROXY -> Icons.Rounded.Tune
}

private fun sortModeIcon(mode: ProxySortMode): ImageVector = when (mode) {
    ProxySortMode.ACTIVE_FIRST -> Icons.Rounded.CheckCircle
    ProxySortMode.LOWEST_PING -> Icons.Rounded.Speed
    ProxySortMode.SERVER_NAME -> Icons.Rounded.Language
    ProxySortMode.PROXY_TYPE -> Icons.Rounded.Tune
    ProxySortMode.STATUS -> Icons.Rounded.Info
}

private fun fallbackIcon(fallback: ProxyUnavailableFallback): ImageVector = when (fallback) {
    ProxyUnavailableFallback.BEST_PROXY -> Icons.Rounded.Bolt
    ProxyUnavailableFallback.DIRECT -> Icons.Rounded.LinkOff
    ProxyUnavailableFallback.KEEP_CURRENT -> Icons.Rounded.Pause
}

@StringRes
private fun networkTitleRes(networkType: ProxyNetworkType): Int = when (networkType) {
    ProxyNetworkType.WIFI -> R.string.proxy_network_wifi
    ProxyNetworkType.MOBILE -> R.string.proxy_network_mobile
    ProxyNetworkType.VPN -> R.string.proxy_network_vpn
    ProxyNetworkType.OTHER -> R.string.proxy_network_other
}

@StringRes
private fun networkModeLabelRes(mode: ProxyNetworkMode): Int = when (mode) {
    ProxyNetworkMode.DIRECT -> R.string.proxy_network_mode_direct
    ProxyNetworkMode.BEST_PROXY -> R.string.proxy_network_mode_best
    ProxyNetworkMode.LAST_USED -> R.string.proxy_network_mode_last_used
    ProxyNetworkMode.SPECIFIC_PROXY -> R.string.proxy_network_mode_specific
}

@StringRes
private fun networkRuleSubtitleRes(rule: ProxyNetworkRule): Int = when (rule.mode) {
    ProxyNetworkMode.DIRECT -> R.string.proxy_network_mode_direct_subtitle
    ProxyNetworkMode.BEST_PROXY -> R.string.proxy_network_mode_best_subtitle
    ProxyNetworkMode.LAST_USED -> R.string.proxy_network_mode_last_used_subtitle
    ProxyNetworkMode.SPECIFIC_PROXY -> R.string.proxy_network_mode_specific_subtitle
}

@StringRes
private fun sortModeLabelRes(mode: ProxySortMode): Int = when (mode) {
    ProxySortMode.ACTIVE_FIRST -> R.string.proxy_sort_mode_active_first
    ProxySortMode.LOWEST_PING -> R.string.proxy_sort_mode_lowest_ping
    ProxySortMode.SERVER_NAME -> R.string.proxy_sort_mode_server_name
    ProxySortMode.PROXY_TYPE -> R.string.proxy_sort_mode_proxy_type
    ProxySortMode.STATUS -> R.string.proxy_sort_mode_status
}

@StringRes
private fun fallbackLabelRes(fallback: ProxyUnavailableFallback): Int = when (fallback) {
    ProxyUnavailableFallback.BEST_PROXY -> R.string.proxy_fallback_best_proxy
    ProxyUnavailableFallback.DIRECT -> R.string.proxy_fallback_direct
    ProxyUnavailableFallback.KEEP_CURRENT -> R.string.proxy_fallback_keep_current
}

private fun proxyToDeepLink(proxy: ProxyModel): String {
    fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    return when (val type = proxy.type) {
        is ProxyTypeModel.Mtproto -> {
            "tg://proxy?server=${encode(proxy.server)}&port=${proxy.port}&secret=${encode(type.secret)}"
        }

        is ProxyTypeModel.Socks5 -> {
            buildString {
                append("tg://socks?server=${encode(proxy.server)}&port=${proxy.port}")
                if (type.username.isNotBlank()) append("&user=${encode(type.username)}")
                if (type.password.isNotBlank()) append("&pass=${encode(type.password)}")
            }
        }

        is ProxyTypeModel.Http -> {
            buildString {
                append("tg://http?server=${encode(proxy.server)}&port=${proxy.port}")
                if (type.username.isNotBlank()) append("&user=${encode(type.username)}")
                if (type.password.isNotBlank()) append("&pass=${encode(type.password)}")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProxyItem(
    proxy: ProxyModel,
    errorMessage: String?,
    isFavorite: Boolean,
    position: ItemPosition,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRefreshPing: () -> Unit,
    onOpenMenu: () -> Unit
) {
    val typeName = when (proxy.type) {
        is ProxyTypeModel.Mtproto -> "MTProto"
        is ProxyTypeModel.Socks5 -> "SOCKS5"
        is ProxyTypeModel.Http -> "HTTP"
    }

    val isEnabled = proxy.isEnabled

    val cornerRadius = 24.dp
    val shape = when (position) {
        ItemPosition.TOP -> RoundedCornerShape(
            topStart = cornerRadius,
            topEnd = cornerRadius,
            bottomStart = 4.dp,
            bottomEnd = 4.dp
        )

        ItemPosition.MIDDLE -> RoundedCornerShape(4.dp)
        ItemPosition.BOTTOM -> RoundedCornerShape(
            bottomStart = cornerRadius,
            bottomEnd = cornerRadius,
            topStart = 4.dp,
            topEnd = 4.dp
        )

        ItemPosition.STANDALONE -> RoundedCornerShape(cornerRadius)
    }

    val backgroundColor by animateColorAsState(
        if (isEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.surfaceContainer,
        label = "bg"
    )

    Surface(
        color = backgroundColor,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isEnabled) Icons.Rounded.Check else Icons.Rounded.Language,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (isEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = proxy.server,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isFavorite) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = stringResource(R.string.proxy_action_remove_favorite),
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = typeName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Port ${proxy.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                if (!errorMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                ProxyPingIndicator(
                    ping = proxy.ping,
                    isChecking = proxy.ping == null,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRefreshPing, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.refresh_list_title),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onOpenMenu, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.more_options_cd),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeToDeleteContainer(
    onDelete: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        content()
        return
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val color by animateColorAsState(
                (if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                    MaterialTheme.colorScheme.errorContainer
                else Color.Transparent),
                label = "color"
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        },
        content = { content() }
    )
}

@Composable
private fun SectionHeader(
    text: String,
    subtitle: String? = null,
    onSubtitleClick: (() -> Unit)? = null
) {
    Column(modifier = Modifier.padding(start = 12.dp, bottom = 8.dp, top = 16.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (onSubtitleClick != null) {
                    Modifier.clickable(onClick = onSubtitleClick)
                } else {
                    Modifier
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyAddEditSheet(
    proxy: ProxyModel?,
    onDismiss: () -> Unit,
    onTest: (String, Int, ProxyTypeModel) -> Unit,
    testPing: Long?,
    testError: String?,
    isTesting: Boolean,
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

    var secret by remember { mutableStateOf((proxy?.type as? ProxyTypeModel.Mtproto)?.secret ?: "") }
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

    val currentProxyType = remember(type, normalizedMtprotoSecret, secret, username, password) {
        when (type) {
            "MTProto" -> ProxyTypeModel.Mtproto(normalizedMtprotoSecret ?: secret.trim())
            "SOCKS5" -> ProxyTypeModel.Socks5(username, password)
            else -> ProxyTypeModel.Http(username, password, false)
        }
    }

    val isInputValid =
        server.isNotBlank() && port.isNotBlank() && (type != "MTProto" || normalizedMtprotoSecret != null)

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
                text = if (proxy == null) stringResource(R.string.new_proxy_title) else stringResource(R.string.edit_proxy_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

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
                    val selected = (text == type)
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

            if (testPing != null || isTesting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.test_proxy_result),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ProxyPingIndicator(
                        ping = testPing,
                        isChecking = isTesting
                    )
                }
            }

            if (!testError.isNullOrBlank()) {
                Text(
                    text = testError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val p = port.toIntOrNull() ?: 443
                        onTest(server, p, currentProxyType)
                    },
                    enabled = isInputValid && !isTesting,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (isTesting) {
                        LoadingIndicator(
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Text(stringResource(R.string.test_proxy_button))
                    }
                }

                Button(
                    onClick = {
                        val p = port.toIntOrNull() ?: 443
                        onSave(server, p, currentProxyType)
                    },
                    enabled = isInputValid,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        if (proxy == null) stringResource(R.string.add_proxy_button) else stringResource(R.string.save_changes_button),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
