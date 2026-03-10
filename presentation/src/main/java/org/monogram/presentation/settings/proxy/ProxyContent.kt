package org.monogram.presentation.settings.proxy

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.domain.models.ProxyModel
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.presentation.features.chats.chatList.components.SettingsTextField
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsSwitchTile
import org.monogram.presentation.core.ui.SettingsTile

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProxyContent(component: ProxyComponent) {
    val state by component.state.subscribeAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

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
                title = { Text("Proxy Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = component::onBackClicked) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = component::onPingAll) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh Pings")
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
                text = { Text("Add Proxy") }
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
                SectionHeader("Connection")
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    AnimatedVisibility(
                        visible = !state.isTelegaProxyEnabled,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        SettingsSwitchTile(
                            icon = Icons.Rounded.Bolt,
                            title = "Smart Switching",
                            subtitle = "Automatically use the fastest proxy",
                            checked = state.isAutoBestProxyEnabled,
                            iconColor = Color(0xFFAF52DE),
                            position = ItemPosition.TOP,
                            onCheckedChange = component::onAutoBestProxyToggled
                        )
                    }

                    SettingsSwitchTile(
                        icon = Icons.Rounded.Public,
                        title = "Prefer IPv6",
                        subtitle = "Use IPv6 when available",
                        checked = state.preferIpv6,
                        iconColor = Color(0xFF34A853),
                        position = if (state.isTelegaProxyEnabled) ItemPosition.TOP else ItemPosition.MIDDLE,
                        onCheckedChange = component::onPreferIpv6Toggled
                    )

                    val isDirect = !state.isTelegaProxyEnabled && state.proxies.none { it.isEnabled }
                    SettingsTile(
                        icon = Icons.Rounded.LinkOff,
                        title = "Disable Proxy",
                        subtitle = if (isDirect) "Connected directly" else "Switch to direct connection",
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
                AnimatedVisibility(
                    visible = state.isRussianNumber,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column {
                        SectionHeader(
                            text = "Telega Proxy",
                            subtitle = "Community-provided proxies. Use at your own risk. More info: t.me/telegaru",
                            onSubtitleClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/telegaru"))
                                context.startActivity(intent)
                            }
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            SettingsSwitchTile(
                                icon = Icons.Rounded.CloudDownload,
                                title = "Enable Telega Proxy",
                                subtitle = "Auto-fetch and switch to best",
                                checked = state.isTelegaProxyEnabled,
                                iconColor = Color(0xFF0088CC),
                                position = if (state.isTelegaProxyEnabled && state.telegaProxies.isNotEmpty()) ItemPosition.TOP else ItemPosition.STANDALONE,
                                onCheckedChange = component::onTelegaProxyToggled
                            )

                            AnimatedVisibility(
                                visible = state.isTelegaProxyEnabled,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                SettingsTile(
                                    icon = Icons.Rounded.Refresh,
                                    title = "Refresh List",
                                    subtitle = "Fetch latest community proxies",
                                    iconColor = Color(0xFF0088CC),
                                    position = ItemPosition.BOTTOM,
                                    onClick = { component.onFetchTelegaProxies() },
                                    trailingContent = {
                                        if (state.isFetchingExternal) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp
                                            )
                                        }
                                    }
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = state.isTelegaProxyEnabled && state.telegaProxies.isNotEmpty(),
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Spacer(modifier = Modifier.height(8.dp))
                                state.telegaProxies.forEachIndexed { index, proxy ->
                                    val position = when {
                                        state.telegaProxies.size == 1 -> ItemPosition.STANDALONE
                                        index == 0 -> ItemPosition.TOP
                                        index == state.telegaProxies.size - 1 -> ItemPosition.BOTTOM
                                        else -> ItemPosition.MIDDLE
                                    }

                                    ProxyItem(
                                        proxy = proxy,
                                        position = position,
                                        onClick = { component.onProxyClicked(proxy) },
                                        onLongClick = { component.onProxyLongClicked(proxy) },
                                        onRefreshPing = { component.onPingProxy(proxy.id) }
                                    )
                                }
                            }
                        }
                    }
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
                        text = "Your Proxies",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row {
                        IconButton(onClick = { component.onClearUnavailableProxies() }) {
                            Icon(
                                Icons.Rounded.DeleteSweep,
                                "Clear Offline",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { component.onRemoveAllProxies() }) {
                            Icon(Icons.Rounded.DeleteForever, "Remove All", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            itemsIndexed(state.proxies, key = { _, it -> it.id }) { index, proxy ->
                val position = when {
                    state.proxies.size == 1 -> ItemPosition.STANDALONE
                    index == 0 -> ItemPosition.TOP
                    index == state.proxies.size - 1 -> ItemPosition.BOTTOM
                    else -> ItemPosition.MIDDLE
                }

                SwipeToDeleteContainer(
                    onDelete = { component.onRemoveProxy(proxy.id) }
                ) {
                    ProxyItem(
                        proxy = proxy,
                        position = position,
                        onClick = { component.onProxyClicked(proxy) },
                        onLongClick = { component.onProxyLongClicked(proxy) },
                        onRefreshPing = { component.onPingProxy(proxy.id) }
                    )
                }
            }

            if (state.proxies.isEmpty() && (!state.isTelegaProxyEnabled || state.telegaProxies.isEmpty()) && !state.isLoading) {
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
                            "No proxies added",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    if (state.isAddingProxy || state.proxyToEdit != null) {
        ProxyAddEditSheet(
            proxy = state.proxyToEdit,
            onDismiss = component::onDismissAddEdit,
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
            title = { Text("Delete Proxy") },
            text = { Text("Are you sure you want to delete the proxy ${proxy.server}?") },
            confirmButton = {
                TextButton(
                    onClick = component::onConfirmDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = component::onDismissDeleteConfirmation) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProxyItem(
    proxy: ProxyModel,
    position: ItemPosition,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRefreshPing: () -> Unit
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
                Text(
                    text = proxy.server,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = typeName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
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
            }

            Column(horizontalAlignment = Alignment.End) {
                ProxyPingIndicator(
                    ping = proxy.ping,
                    isChecking = proxy.ping == null,
                )

                IconButton(onClick = onRefreshPing, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = "Refresh",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                        contentDescription = "Delete",
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
    onSave: (String, Int, ProxyTypeModel) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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

    val currentProxyType = remember(type, secret, username, password) {
        when (type) {
            "MTProto" -> ProxyTypeModel.Mtproto(secret)
            "SOCKS5" -> ProxyTypeModel.Socks5(username, password)
            else -> ProxyTypeModel.Http(username, password, false)
        }
    }

    val isInputValid = server.isNotBlank() && port.isNotBlank() && (type != "MTProto" || secret.isNotBlank())

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
                text = if (proxy == null) "New Proxy" else "Edit Proxy",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                Modifier
                    .selectableGroup()
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf("MTProto", "SOCKS5", "HTTP").forEach { text ->
                    val selected = (text == type)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
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
                placeholder = "Server Address",
                icon = Icons.Rounded.Language,
                position = ItemPosition.TOP,
                singleLine = true
            )

            SettingsTextField(
                value = port,
                onValueChange = { if (it.all { char -> char.isDigit() }) port = it },
                placeholder = "Port",
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
                                placeholder = "Secret (Hex)",
                                icon = Icons.Rounded.Key,
                                position = ItemPosition.STANDALONE,
                                singleLine = true
                            )
                        }

                        "SOCKS5", "HTTP" -> {
                            SettingsTextField(
                                value = username,
                                onValueChange = { username = it },
                                placeholder = "Username (Optional)",
                                icon = Icons.Rounded.Person,
                                position = ItemPosition.TOP,
                                singleLine = true
                            )
                            SettingsTextField(
                                value = password,
                                onValueChange = { password = it },
                                placeholder = "Password (Optional)",
                                icon = Icons.Rounded.Password,
                                position = ItemPosition.BOTTOM,
                                singleLine = true
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

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
                Text(if (proxy == null) "Add Proxy" else "Save Changes", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
