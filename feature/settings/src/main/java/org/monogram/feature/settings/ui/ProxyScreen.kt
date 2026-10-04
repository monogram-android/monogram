package org.monogram.feature.settings.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.monogram.core.common.Outcome
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.core.ui.components.ItemPosition
import org.monogram.core.ui.components.SettingsCard
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.loading.MonogramLoadingContained
import org.monogram.core.ui.loading.MonogramLoadingInlineSize
import org.monogram.core.ui.media.mediaViewerMotionEnabled
import org.monogram.feature.settings.R
import org.monogram.feature.settings.StoredProxy
import org.monogram.network.bridge.MtprotoTransportMode
import org.monogram.network.bridge.ProxyConfig
import org.monogram.network.bridge.ProxyType
import org.monogram.network.bridge.decodeProxySecret

internal data class ProxyScreenState(
    val type: ProxyType = ProxyType.SOCKS5,
    val host: String = "",
    val port: String = "1080",
    val username: String = "",
    val password: String = "",
    val secret: String = "",
    val transportMode: MtprotoTransportMode = MtprotoTransportMode.PADDED_INTERMEDIATE,
    val id: String = "",
    val networkScope: String = "ALWAYS",
    val enabled: Boolean = true,
    val favorite: Boolean = false,
    val latencyMs: Long? = null,
    val consecutiveFailures: Int = 0,
    val lastCheckedAt: Long? = null,
)

internal fun ProxyScreenState.withPing(latencyMs: Long?, now: Long): ProxyScreenState =
    if (latencyMs != null) {
        copy(latencyMs = latencyMs, consecutiveFailures = 0, lastCheckedAt = now)
    } else {
        copy(latencyMs = null, consecutiveFailures = consecutiveFailures + 1, lastCheckedAt = now)
    }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ProxyScreen(
    profiles: List<ProxyScreenState>,
    activeKey: String?,
    checking: Set<String>,
    headerBusy: Boolean,
    status: String?,
    statusError: Boolean,
    onUse: (ProxyScreenState) -> Unit,
    onCheckAll: () -> Unit,
    onDisable: () -> Unit,
    onSave: (ProxyScreenState, String?, (String?) -> Unit) -> Unit,
    onDelete: (ProxyScreenState) -> Unit,
    onPasteAdd: suspend () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editorNonce by rememberSaveable { mutableIntStateOf(0) }
    var editingKey by rememberSaveable { mutableStateOf("") }
    val active = profiles.firstOrNull { it.profileKey() == activeKey }
    val motion = mediaViewerMotionEnabled()
    val actionShapes = ButtonDefaults.shapes(
        shape = CircleShape,
        pressedShape = if (motion) RoundedCornerShape(16.dp) else CircleShape,
    )
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val openEditor = { profile: ProxyScreenState? ->
        editingKey = profile?.profileKey().orEmpty()
        editorNonce += 1
        editorOpen = true
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = navBottom + 16.dp),
        ) {
            ProxyStatus(
                active = active,
                busy = headerBusy || (active != null && active.profileKey() in checking),
                onDisable = if (active != null) onDisable else null,
                disableEnabled = !headerBusy,
            )
            if (status != null) {
                Text(
                    status,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (statusError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_proxy_saved_list),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (profiles.isNotEmpty()) {
                    TextButton(
                        onClick = onCheckAll,
                        enabled = profiles.any { it.profileKey() !in checking },
                    ) {
                        Text(
                            stringResource(R.string.settings_proxy_check_all),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (profiles.isEmpty()) {
                Text(
                    stringResource(R.string.settings_proxy_empty),
                    modifier = Modifier.padding(bottom = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(Modifier.selectableGroup()) {
                    profiles.forEachIndexed { index, profile ->
                        ProxyRow(
                            profile = profile,
                            position = proxyPosition(index, profiles.size),
                            active = profile.profileKey() == activeKey,
                            checking = profile.profileKey() in checking,
                            onUse = { onUse(profile) },
                            onEdit = { openEditor(profile) },
                        )
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val addMod = Modifier.weight(1f).heightIn(min = 48.dp)
                val addClick = { openEditor(null) }
                if (profiles.isEmpty()) {
                    Button(onClick = addClick, shapes = actionShapes, modifier = addMod) { AddProxyLabel() }
                } else {
                    FilledTonalButton(onClick = addClick, shapes = actionShapes, modifier = addMod) {
                        AddProxyLabel()
                    }
                }
                OutlinedButton(
                    onClick = { scope.launch { onPasteAdd() } },
                    shapes = actionShapes,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_proxy_add_clipboard),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (editorOpen) {
            val seed = profiles.firstOrNull { it.profileKey() == editingKey } ?: ProxyScreenState()
            val canDelete = editingKey.isNotEmpty() && profiles.any { it.profileKey() == editingKey }
            AppModalSheet(onDismissRequest = { editorOpen = false }) {
                key(editorNonce) {
                    ProxyEditor(
                        initial = seed,
                        editing = canDelete,
                        shapes = actionShapes,
                        motion = motion,
                        onDismiss = { editorOpen = false },
                        onSave = onSave,
                        onDelete = {
                            profiles.firstOrNull { it.profileKey() == editingKey }?.let(onDelete)
                            editorOpen = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddProxyLabel() {
    Icon(Icons.Outlined.Add, contentDescription = null)
    Spacer(Modifier.size(8.dp))
    Text(stringResource(R.string.settings_proxy_add))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProxyStatus(
    active: ProxyScreenState?,
    busy: Boolean,
    onDisable: (() -> Unit)?,
    disableEnabled: Boolean,
) {
    val title = stringResource(
        if (active == null) R.string.settings_proxy_direct_title else R.string.settings_proxy_in_use,
    )
    val endpoint = active?.let { "${proxyTypeLabel(it.type)} · ${it.host}:${it.port}" }
    val failed = active != null && !busy && active.latencyMs == null && active.consecutiveFailures > 0
    val detail = when {
        busy -> stringResource(R.string.settings_proxy_checking)
        active == null -> stringResource(R.string.settings_proxy_direct_body)
        active.latencyMs != null -> stringResource(R.string.settings_proxy_ping_result, active.latencyMs)
        failed -> stringResource(R.string.settings_proxy_unavailable)
        else -> stringResource(R.string.settings_proxy_not_checked)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = if (active == null) Icons.Outlined.Public else Icons.Outlined.VpnKey,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleLargeEmphasized)
                if (endpoint != null) {
                    Text(
                        endpoint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (busy) {
                MonogramLoadingContained(
                    size = 48.dp,
                    status = stringResource(R.string.settings_proxy_checking),
                )
            } else if (onDisable != null) {
                TextButton(onClick = onDisable, enabled = disableEnabled) {
                    Text(stringResource(R.string.settings_proxy_disable))
                }
            }
        }
    }
}

@Composable
private fun ProxyRow(
    profile: ProxyScreenState,
    position: ItemPosition,
    active: Boolean,
    checking: Boolean,
    onUse: () -> Unit,
    onEdit: () -> Unit,
) {
    val endpoint = "${profile.host}:${profile.port}"
    val subtitle = buildString {
        append(proxyTypeLabel(profile.type))
        if (profile.transportMode == MtprotoTransportMode.HTTP) {
            append(" · ")
            append(stringResource(R.string.settings_proxy_transport_http))
        }
        append(" · ")
        append(proxyPingLabel(profile, checking))
    }
    val subtitleColor = if (active) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else if (!checking && profile.latencyMs == null && profile.consecutiveFailures > 0) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    SettingsCard(position = position, selected = active) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp)
                    .selectable(selected = active, role = Role.RadioButton, onClick = onUse)
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp).alpha(if (active) 1f else 0f),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        endpoint,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (checking) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    MonogramLoading(
                        size = MonogramLoadingInlineSize,
                        status = stringResource(R.string.settings_proxy_checking),
                    )
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.settings_proxy_edit_named, endpoint),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProxyEditor(
    initial: ProxyScreenState,
    editing: Boolean,
    shapes: androidx.compose.material3.ButtonShapes,
    motion: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProxyScreenState, String?, (String?) -> Unit) -> Unit,
    onDelete: () -> Unit,
) {
    var typeName by rememberSaveable { mutableStateOf(initial.type.name) }
    var host by rememberSaveable { mutableStateOf(initial.host) }
    var port by rememberSaveable { mutableStateOf(initial.port) }
    var portEdited by rememberSaveable { mutableStateOf(initial.port != initial.type.defaultPort()) }
    var username by rememberSaveable { mutableStateOf(initial.username) }
    var password by rememberSaveable { mutableStateOf(initial.password) }
    var secret by rememberSaveable { mutableStateOf(initial.secret) }
    var transportName by rememberSaveable { mutableStateOf(initial.transportMode.name) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var secretVisible by rememberSaveable { mutableStateOf(false) }
    var hostError by remember { mutableStateOf<String?>(null) }
    var portError by remember { mutableStateOf<String?>(null) }
    var authError by remember { mutableStateOf<String?>(null) }
    var secretError by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val originalKey = rememberSaveable(editing) {
        if (editing) initial.profileKey() else ""
    }.ifEmpty { null }
    val selected = ProxyType.valueOf(typeName)
    val selectedTransport = MtprotoTransportMode.valueOf(transportName)
    val sheetMax = (LocalConfiguration.current.screenHeightDp * 0.75f).dp
    LaunchedEffect(selected) {
        if (selected == ProxyType.MTPROTO && selectedTransport == MtprotoTransportMode.HTTP) {
            transportName = MtprotoTransportMode.PADDED_INTERMEDIATE.name
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = sheetMax)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
    ) {
        val reveal = fadeIn(tween(if (motion) 180 else 0)) +
            expandVertically(tween(if (motion) 220 else 0))
        val conceal = fadeOut(tween(if (motion) 120 else 0)) +
            shrinkVertically(tween(if (motion) 180 else 0))
        Text(
            stringResource(if (editing) R.string.settings_proxy_edit else R.string.settings_proxy_add),
            style = MaterialTheme.typography.titleLarge,
        )
        if (confirmDelete) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.settings_proxy_remove_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.settings_proxy_remove_body, "${initial.host}:${initial.port}"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.settings_proxy_remove))
                }
                TextButton(
                    onClick = { confirmDelete = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.settings_proxy_cancel))
                }
            }
            return@Column
        }
        Text(
            stringResource(R.string.settings_proxy_type),
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.titleSmall,
        )
        ProxyChoiceGroup(
            labels = listOf(ProxyType.SOCKS5, ProxyType.HTTP, ProxyType.HTTPS, ProxyType.MTPROTO)
                .map { proxyTypeLabel(it) },
            selected = listOf(ProxyType.SOCKS5, ProxyType.HTTP, ProxyType.HTTPS, ProxyType.MTPROTO)
                .indexOf(selected),
            onSelect = { index ->
                val candidate = listOf(ProxyType.SOCKS5, ProxyType.HTTP, ProxyType.HTTPS, ProxyType.MTPROTO)[index]
                if (!portEdited) port = candidate.defaultPort()
                typeName = candidate.name
            },
        )
        AnimatedVisibility(visible = selected != ProxyType.MTPROTO, enter = reveal, exit = conceal) {
            Column {
                Text(
                    stringResource(R.string.settings_proxy_transport),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                ProxyChoiceGroup(
                    labels = listOf(
                        stringResource(R.string.settings_proxy_transport_padded),
                        stringResource(R.string.settings_proxy_transport_http),
                    ),
                    selected = if (selectedTransport == MtprotoTransportMode.HTTP) 1 else 0,
                    onSelect = { index ->
                        transportName = if (index == 0) {
                            MtprotoTransportMode.PADDED_INTERMEDIATE.name
                        } else {
                            MtprotoTransportMode.HTTP.name
                        }
                    },
                )
            }
        }
        Row(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it; hostError = null; formError = null },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.settings_proxy_host)) },
                supportingText = { Text(hostError ?: stringResource(R.string.settings_proxy_host_hint)) },
                isError = hostError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5); portEdited = true; portError = null },
                modifier = Modifier.width(112.dp),
                label = { Text(stringResource(R.string.settings_proxy_port)) },
                supportingText = portError?.let { message -> { Text(message) } },
                isError = portError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        }
        AnimatedContent(
            targetState = selected == ProxyType.MTPROTO,
            modifier = Modifier.padding(top = 4.dp),
            transitionSpec = {
                fadeIn(tween(if (motion) 180 else 0)) togetherWith fadeOut(tween(if (motion) 120 else 0))
            },
            label = "proxyAuthentication",
        ) { mtproto ->
            if (mtproto) {
                OutlinedTextField(
                value = secret,
                onValueChange = { secret = it; secretError = null; formError = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_proxy_secret)) },
                supportingText = secretError?.let { message -> { Text(message) } },
                isError = secretError != null,
                visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { secretVisible = !secretVisible }) {
                        Icon(
                            if (secretVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = stringResource(
                                if (secretVisible) R.string.settings_proxy_hide_secret else R.string.settings_proxy_show_secret,
                            ),
                        )
                    }
                },
                singleLine = true,
            )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                value = username,
                onValueChange = { username = it; authError = null; formError = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_proxy_username)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; authError = null; formError = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_proxy_password)) },
                supportingText = authError?.let { message -> { Text(message) } },
                isError = authError != null,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = stringResource(
                                if (passwordVisible) {
                                    R.string.settings_proxy_hide_password
                                } else {
                                    R.string.settings_proxy_show_password
                                },
                            ),
                        )
                    }
                },
                    singleLine = true,
                )
                }
            }
        }
        formError?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        val hostRequired = stringResource(R.string.settings_proxy_host_required)
        val portInvalid = stringResource(R.string.settings_proxy_port_invalid)
        val credentialsIncomplete = stringResource(R.string.settings_proxy_credentials_incomplete)
        val secretInvalid = stringResource(R.string.settings_proxy_secret_invalid)
        Button(
            onClick = {
                hostError = null
                portError = null
                authError = null
                secretError = null
                formError = null
                val draft = initial.copy(
                    type = selected,
                    host = host.trim(),
                    port = port,
                    username = username,
                    password = password,
                    secret = secret.trim(),
                    transportMode = selectedTransport,
                )
                if (draft.host.isBlank()) {
                    hostError = hostRequired
                    return@Button
                }
                when (val valid = draft.toProxyConfig().validate()) {
                    is Outcome.Ok -> {
                        saving = true
                        onSave(draft, originalKey) { failure ->
                            saving = false
                            if (failure == null) onDismiss() else formError = failure
                        }
                    }
                    is Outcome.Err -> when (valid.proxyField()) {
                        ProxyFieldError.HOST -> hostError = hostRequired
                        ProxyFieldError.PORT -> portError = portInvalid
                        ProxyFieldError.CREDENTIALS -> authError = credentialsIncomplete
                        ProxyFieldError.SECRET -> secretError = secretInvalid
                        ProxyFieldError.OTHER -> formError = valid.message
                    }
                }
            },
            enabled = !saving,
            shapes = shapes,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 56.dp),
        ) {
            if (saving) {
                MonogramLoading(
                    size = MonogramLoadingInlineSize,
                    status = stringResource(R.string.settings_proxy_checking),
                )
            } else {
                Text(stringResource(R.string.settings_proxy_save))
            }
        }
        if (editing) {
            TextButton(
                onClick = { confirmDelete = true },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(R.string.settings_proxy_remove),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private enum class ProxyFieldError { HOST, PORT, CREDENTIALS, SECRET, OTHER }

private fun Outcome.Err.proxyField(): ProxyFieldError = when {
    "port" in message -> ProxyFieldError.PORT
    "credential" in message -> ProxyFieldError.CREDENTIALS
    "secret" in message || "MTProto" in message -> ProxyFieldError.SECRET
    "host" in message -> ProxyFieldError.HOST
    else -> ProxyFieldError.OTHER
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProxyChoiceGroup(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    enabled: (Int) -> Boolean = { true },
) {
    Row(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val itemEnabled = enabled(index)
            ToggleButton(
                checked = selected == index,
                onCheckedChange = { if (itemEnabled) onSelect(index) },
                enabled = itemEnabled,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics { role = Role.RadioButton },
                shapes = when {
                    labels.size == 1 -> ToggleButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight)
                    index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    index == labels.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.colors(
                    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun proxyTypeLabel(type: ProxyType): String = when (type) {
    ProxyType.SOCKS5 -> stringResource(R.string.settings_proxy_type_socks5)
    ProxyType.HTTP -> stringResource(R.string.settings_proxy_type_http)
    ProxyType.HTTPS -> stringResource(R.string.settings_proxy_type_https)
    ProxyType.MTPROTO -> stringResource(R.string.settings_proxy_type_mtproto)
    ProxyType.NONE -> stringResource(R.string.settings_proxy_type_none)
}

@Composable
private fun proxyPingLabel(profile: ProxyScreenState, checking: Boolean): String {
    val latency = profile.latencyMs
    return when {
        checking -> stringResource(R.string.settings_proxy_checking)
        latency != null -> stringResource(R.string.settings_proxy_latency, latency)
        profile.consecutiveFailures > 0 -> stringResource(R.string.settings_proxy_unavailable)
        else -> stringResource(R.string.settings_proxy_not_checked)
    }
}

private fun proxyPosition(index: Int, count: Int): ItemPosition = when {
    count <= 1 -> ItemPosition.STANDALONE
    index == 0 -> ItemPosition.TOP
    index == count - 1 -> ItemPosition.BOTTOM
    else -> ItemPosition.MIDDLE
}

private fun ProxyType.defaultPort(): String = when (this) {
    ProxyType.SOCKS5 -> "1080"
    ProxyType.HTTP -> "8080"
    ProxyType.HTTPS, ProxyType.MTPROTO -> "443"
    ProxyType.NONE -> "1080"
}

internal fun ProxyScreenState.toProxyConfig(): ProxyConfig = ProxyConfig(
    type = type,
    host = host.trim(),
    port = port.toIntOrNull() ?: 0,
    username = username.takeIf { type != ProxyType.MTPROTO && it.isNotBlank() },
    password = password.takeIf { type != ProxyType.MTPROTO && it.isNotBlank() },
    secret = if (type == ProxyType.MTPROTO) {
        decodeProxySecret(secret.trim()) ?: byteArrayOf(0)
    } else {
        byteArrayOf()
    },
)

internal fun stateFromStored(proxy: StoredProxy) = ProxyScreenState(
    type = runCatching { ProxyType.valueOf(proxy.kind) }.getOrDefault(ProxyType.SOCKS5),
    host = proxy.host,
    port = proxy.port.toString(),
    username = proxy.username.orEmpty(),
    password = proxy.password.orEmpty(),
    secret = proxy.secret.joinToString("") { byte -> "%02x".format(byte) },
    transportMode = runCatching { MtprotoTransportMode.valueOf(proxy.transportMode.uppercase()) }
        .getOrDefault(MtprotoTransportMode.PADDED_INTERMEDIATE),
    id = proxy.id,
    networkScope = proxy.networkScope,
    enabled = proxy.enabled,
    favorite = proxy.favorite,
    latencyMs = proxy.lastLatencyMs,
    consecutiveFailures = proxy.consecutiveFailures,
    lastCheckedAt = proxy.lastCheckedAt,
)

internal fun storedFromState(state: ProxyScreenState) = StoredProxy(
    kind = state.type.name,
    host = state.host.trim(),
    port = state.port.toIntOrNull() ?: 0,
    username = state.username.takeIf { state.type != ProxyType.MTPROTO && it.isNotBlank() },
    password = state.password.takeIf { state.type != ProxyType.MTPROTO && it.isNotBlank() },
    secret = if (state.type == ProxyType.MTPROTO) {
        decodeProxySecret(state.secret.trim()) ?: byteArrayOf(0)
    } else {
        byteArrayOf()
    },
    transportMode = state.transportMode.name.lowercase(),
    id = state.id,
    networkScope = state.networkScope,
    enabled = state.enabled,
    favorite = state.favorite,
    lastLatencyMs = state.latencyMs,
    consecutiveFailures = state.consecutiveFailures,
    lastCheckedAt = state.lastCheckedAt,
)

internal fun ProxyScreenState.profileKey(): String = storedFromState(this).profileKey

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ProxyScreenLightPreview() {
    MaterialTheme { ProxyScreenPreviewContent() }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ProxyScreenDarkPreview() {
    MaterialTheme { ProxyScreenPreviewContent() }
}

@Composable
private fun ProxyScreenPreviewContent() {
    val active = ProxyScreenState(host = "proxy.example", port = "1080", latencyMs = 42, lastCheckedAt = 1)
    val other = ProxyScreenState(
        type = ProxyType.MTPROTO,
        host = "mt.example",
        port = "443",
        secret = "11".repeat(16),
        consecutiveFailures = 2,
        lastCheckedAt = 1,
    )
    ProxyScreen(
        profiles = listOf(active, other),
        activeKey = active.profileKey(),
        checking = setOf(other.profileKey()),
        headerBusy = false,
        status = null,
        statusError = false,
        onUse = {},
        onCheckAll = {},
        onDisable = {},
        onSave = { _, _, done -> done(null) },
        onDelete = {},
    )
}
