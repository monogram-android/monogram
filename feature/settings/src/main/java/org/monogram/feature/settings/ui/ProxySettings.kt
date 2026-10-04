package org.monogram.feature.settings.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.monogram.core.common.Outcome
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.loading.MonogramLoadingHeroSize
import org.monogram.feature.settings.ProxyCheckQueue
import org.monogram.feature.settings.ProxySettingsStore
import org.monogram.feature.settings.R
import org.monogram.feature.settings.SettingsComponent
import org.monogram.feature.settings.StoredProxy
import org.monogram.network.bridge.MtprotoTransportMode
import org.monogram.network.bridge.ProxyConfig

private const val DIRECT = ""

private class ProxyRefs(
    val component: SettingsComponent,
    val context: Context,
) {
    var profiles: List<ProxyScreenState> = emptyList()
    var activeKey: String? = null
    var desired: ProxyScreenState? = null
    var connectedKey: String? = null
    val pending = HashMap<String, ProxyScreenState>()
    var report: (String) -> Unit = {}
}

private data class ProxyConnect(val error: String?, val latencyMs: Long?)

@Composable
internal fun ProxySettings(
    component: SettingsComponent,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val refs = remember(component, context) { ProxyRefs(component, context) }
    var ready by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf<List<ProxyScreenState>>(emptyList()) }
    var activeKey by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var statusError by rememberSaveable { mutableStateOf(false) }
    var sessionJobs by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }
    val failedLabel = stringResource(R.string.settings_proxy_test_failed)
    refs.report = { message ->
        status = message
        statusError = true
    }
    val pingSlots = remember { Semaphore(3) }
    val queue = remember(refs, scope) {
        ProxyCheckQueue(
            scope = scope,
            check = { null },
            restore = { restoreDesired(refs) },
        )
    }
    val probes by queue.checking.collectAsStateWithLifecycle()

    fun sync() {
        profiles = refs.profiles
        activeKey = refs.activeKey
    }

    fun enqueue(profile: ProxyScreenState, onLatency: ((Long?) -> Unit)? = null): Boolean {
        val key = profile.profileKey()
        if (key in busy) return false
        busy = busy + key
        scope.launch {
            val latency = try {
                pingSlots.withPermit { probeProxy(refs.component, profile) }
            } finally {
                busy = busy - key
            }
            recordPing(refs, key, latency)
            sync()
            onLatency?.invoke(latency)
        }
        return true
    }

    LaunchedEffect(refs) {
        val snapshot = ProxySettingsStore.loadSnapshot(refs.context)
        refs.profiles = snapshot.profiles.map(::stateFromStored)
        refs.activeKey = snapshot.activeProfileKey?.takeIf { key ->
            refs.profiles.any { it.profileKey() == key }
        }
        refs.desired = refs.profiles.firstOrNull { it.profileKey() == refs.activeKey }
        refs.connectedKey = refs.activeKey ?: DIRECT
        sync()
        if (ProxySettingsStore.consumeStartupFailure(refs.context)) {
            status = failedLabel
            statusError = true
        }
        ready = true
    }

    if (!ready) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MonogramLoading(size = MonogramLoadingHeroSize)
        }
    } else {
        ProxyScreen(
            profiles = profiles,
            activeKey = activeKey,
            checking = probes + busy,
            headerBusy = sessionJobs > 0,
            status = status,
            statusError = statusError,
            modifier = modifier,
            onUse = onUse@{ profile ->
                val key = profile.profileKey()
                if (key == refs.activeKey || key in busy) return@onUse
                busy = busy + key
                sessionJobs += 1
                scope.launch {
                    val result = try {
                        queue.exclusive { activate(refs, profile) }
                    } finally {
                        busy = busy - key
                        sessionJobs -= 1
                        sync()
                    }
                    if (result.error == null) {
                        status = null
                        statusError = false
                        enqueue(profile)
                    } else {
                        status = result.error
                        statusError = true
                    }
                }
            },
            onCheckAll = { refs.profiles.forEach { enqueue(it) } },
            onDisable = {
                sessionJobs += 1
                scope.launch {
                    val error = try {
                        queue.exclusive {
                            val previousDesired = refs.desired
                            val previousKey = refs.activeKey
                            refs.desired = null
                            refs.activeKey = null
                            refs.connectedKey = null
                            val failure = restoreDesired(refs, reportFailure = false)
                            if (failure != null) {
                                refs.desired = previousDesired
                                refs.activeKey = previousKey
                            } else {
                                persist(refs)
                            }
                            failure
                        }
                    } finally {
                        sessionJobs -= 1
                        sync()
                    }
                    if (error == null) {
                        status = null
                        statusError = false
                    } else {
                        status = error
                        statusError = true
                    }
                }
            },
            onSave = { draft, originalKey, done ->
                val busyKey = originalKey ?: draft.profileKey()
                busy = busy + busyKey
                sessionJobs += 1
                scope.launch {
                    val result = try {
                        queue.exclusive { saveProfile(refs, draft, originalKey) }
                    } finally {
                        busy = busy - busyKey
                        sessionJobs -= 1
                        sync()
                    }
                    if (result.error == null) {
                        status = null
                        statusError = false
                        enqueue(draft)
                    } else {
                        status = result.error
                        statusError = true
                    }
                    done(result.error)
                }
            },
            onDelete = { profile ->
                val key = profile.profileKey()
                val wasActive = refs.activeKey == key
                refs.profiles = refs.profiles.filterNot { it.profileKey() == key }
                if (wasActive) {
                    refs.activeKey = null
                    refs.desired = null
                    refs.connectedKey = null
                }
                persist(refs)
                sync()
                if (wasActive) {
                    sessionJobs += 1
                    scope.launch {
                        try {
                            queue.exclusive { restoreDesired(refs) }
                        } finally {
                            sessionJobs -= 1
                        }
                    }
                }
            },
            onImport = { config, done ->
                sessionJobs += 1
                scope.launch {
                    val result = try {
                        queue.exclusive { saveProfile(refs, stateFromConfig(config), null) }
                    } finally {
                        sessionJobs -= 1
                        sync()
                    }
                    if (result.error == null) {
                        status = null
                        statusError = false
                        enqueue(stateFromConfig(config))
                    } else {
                        status = result.error
                        statusError = true
                    }
                    done(result.error)
                }
            },
            onTestImported = { config, done -> enqueue(stateFromConfig(config), done) },
        )
    }
}

private suspend fun activate(refs: ProxyRefs, profile: ProxyScreenState): ProxyConnect {
    val key = profile.profileKey()
    var committed = false
    try {
        val connected = connectProfile(refs.component, profile)
        if (connected.error == null) {
            refs.connectedKey = key
            refs.profiles = refs.profiles.map { if (it.profileKey() == key) profile else it }
            refs.activeKey = key
            refs.desired = profile
            persist(refs)
            committed = true
        } else {
            refs.profiles = refs.profiles.map {
                if (it.profileKey() == key) it.withPing(null, System.currentTimeMillis()) else it
            }
            persist(refs)
        }
        return connected
    } finally {
        if (!committed) {
            refs.connectedKey = null
            restoreDesired(refs, reportFailure = false)
        }
    }
}

private suspend fun saveProfile(
    refs: ProxyRefs,
    draft: ProxyScreenState,
    originalKey: String?,
): ProxyConnect {
    var committed = false
    try {
        val connected = connectProfile(refs.component, draft)
        if (connected.error == null) {
            val updated = draft
            val key = updated.profileKey()
            refs.connectedKey = key
            refs.profiles = refs.profiles.filterNot {
                it.profileKey() == key || (originalKey != null && it.profileKey() == originalKey)
            } + updated
            refs.activeKey = key
            refs.desired = updated
            persist(refs)
            committed = true
        }
        return connected
    } finally {
        if (!committed) {
            refs.connectedKey = null
            restoreDesired(refs, reportFailure = false)
        }
    }
}

private suspend fun probeProxy(component: SettingsComponent, profile: ProxyScreenState): Long? =
    when (val result = component.pingProxy(profile.toProxyConfig())) {
        is Outcome.Ok -> result.value
        is Outcome.Err -> null
    }

private suspend fun connectProfile(component: SettingsComponent, state: ProxyScreenState): ProxyConnect {
    val started = SystemClock.elapsedRealtime()
    when (val mode = component.setTransportMode(state.transportMode)) {
        is Outcome.Err -> return ProxyConnect(mode.message, null)
        is Outcome.Ok -> Unit
    }
    when (val configured = component.configureProxy(state.toProxyConfig())) {
        is Outcome.Err -> return ProxyConnect(configured.message, null)
        is Outcome.Ok -> Unit
    }
    return when (val connected = component.testProxyConnection()) {
        is Outcome.Ok -> ProxyConnect(null, (SystemClock.elapsedRealtime() - started).coerceAtLeast(0))
        is Outcome.Err -> ProxyConnect(connected.message, null)
    }
}

private suspend fun restoreDesired(refs: ProxyRefs, reportFailure: Boolean = true): String? {
    val desired = refs.desired
    val wanted = desired?.profileKey()
    if (wanted != null && wanted == refs.connectedKey) return null
    if (desired == null) {
        if (refs.connectedKey == DIRECT) return null
        return when (val cleared = refs.component.clearProxy()) {
            is Outcome.Err -> {
                refs.connectedKey = null
                if (reportFailure) refs.report(cleared.message)
                cleared.message
            }
            is Outcome.Ok -> {
                refs.connectedKey = DIRECT
                null
            }
        }
    }
    val result = connectProfile(refs.component, desired)
    return if (result.error == null) {
        refs.connectedKey = wanted
        null
    } else {
        refs.connectedKey = null
        if (reportFailure) refs.report(result.error)
        result.error
    }
}

private fun recordPing(refs: ProxyRefs, key: String, latency: Long?) {
    val current = refs.profiles.firstOrNull { it.profileKey() == key } ?: return
    val updated = current.withPing(latency, System.currentTimeMillis())
    refs.profiles = refs.profiles.map { if (it.profileKey() == key) updated else it }
    if (refs.activeKey == key) refs.desired = updated
    persist(refs)
}

private fun persist(refs: ProxyRefs) {
    ProxySettingsStore.saveAll(
        refs.context,
        refs.profiles.map(::storedFromState),
        refs.activeKey,
    )
}

private fun stateFromConfig(config: ProxyConfig) = stateFromStored(
    StoredProxy(
        kind = config.type.name,
        host = config.host,
        port = config.port,
        username = config.username,
        password = config.password,
        secret = config.secret,
        transportMode = MtprotoTransportMode.PADDED_INTERMEDIATE.name.lowercase(),
    ),
)
