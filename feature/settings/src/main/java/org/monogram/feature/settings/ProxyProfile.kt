package org.monogram.feature.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.network.bridge.MtprotoTransportMode
import org.monogram.network.bridge.ProxyConfig
import org.monogram.network.bridge.ProxyType

/** Metadata for a saved proxy; credentials remain inside the encrypted store. */
enum class ProxyNetworkScope { ALWAYS, WIFI_ONLY, MOBILE_ONLY, NEVER_ON_VPN }
enum class ProxyHealth { DISABLED, BLOCKED_BY_VPN, TESTING, HEALTHY, UNHEALTHY }

data class ProxyProfile(
    val id: String,
    val config: ProxyConfig,
    val transportMode: MtprotoTransportMode,
    val networkScope: ProxyNetworkScope = ProxyNetworkScope.ALWAYS,
    val enabled: Boolean = true,
    val favorite: Boolean = false,
    val lastLatencyMs: Long? = null,
    val consecutiveFailures: Int = 0,
    val lastCheckedAt: Long? = null,
) {
    val health: ProxyHealth
        get() = when {
            !enabled -> ProxyHealth.DISABLED
            consecutiveFailures > 0 && lastCheckedAt != null -> ProxyHealth.UNHEALTHY
            lastLatencyMs != null -> ProxyHealth.HEALTHY
            else -> ProxyHealth.UNHEALTHY
        }

    fun isAllowedOn(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return networkScope == ProxyNetworkScope.ALWAYS
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        if (networkScope == ProxyNetworkScope.NEVER_ON_VPN && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        return when (networkScope) {
            ProxyNetworkScope.ALWAYS, ProxyNetworkScope.NEVER_ON_VPN -> true
            ProxyNetworkScope.WIFI_ONLY -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ProxyNetworkScope.MOBILE_ONLY -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        }
    }

    fun withTestResult(latencyMs: Long?, now: Long = System.currentTimeMillis()): ProxyProfile =
        if (latencyMs != null) copy(lastLatencyMs = latencyMs, consecutiveFailures = 0, lastCheckedAt = now)
        else copy(lastLatencyMs = null, consecutiveFailures = consecutiveFailures + 1, lastCheckedAt = now)

    companion object {
        fun fromStored(stored: StoredProxy): ProxyProfile = ProxyProfile(
            id = stored.id.ifBlank { stored.profileKey },
            config = stored.toConfig(),
            transportMode = runCatching { MtprotoTransportMode.valueOf(stored.transportMode.uppercase()) }
                .getOrDefault(MtprotoTransportMode.PADDED_INTERMEDIATE),
            networkScope = runCatching { ProxyNetworkScope.valueOf(stored.networkScope) }
                .getOrDefault(ProxyNetworkScope.ALWAYS),
            enabled = stored.enabled,
            favorite = stored.favorite,
            lastLatencyMs = stored.lastLatencyMs,
            consecutiveFailures = stored.consecutiveFailures,
            lastCheckedAt = stored.lastCheckedAt,
        )

    }
}

private fun StoredProxy.toConfig() = ProxyConfig(
    type = runCatching { ProxyType.valueOf(kind) }.getOrDefault(ProxyType.SOCKS5),
    host = host,
    port = port,
    username = username,
    password = password,
    secret = secret,
)

class ProxyProfileManager(internal val context: Context) {
    fun profiles(): List<ProxyProfile> = ProxySettingsStore.loadSnapshot(context).profiles.map(ProxyProfile::fromStored)

    fun save(profile: ProxyProfile, activate: Boolean = false) {
        val current = profiles().filterNot { it.id == profile.id } + profile
        ProxySettingsStore.saveProfiles(context, current, if (activate) profile.id else activeId())
    }

    fun activate(profile: ProxyProfile) = ProxySettingsStore.saveProfiles(context, profiles(), profile.id)
    fun disable() = ProxySettingsStore.clearActive(context)
    fun active(): ProxyProfile? = profiles().firstOrNull { it.id == activeId() }
    fun activeId(): String? = ProxySettingsStore.loadSnapshot(context).activeProfileKey

    fun remove(profile: ProxyProfile) = ProxySettingsStore.saveProfiles(context, profiles().filterNot { it.id == profile.id }, activeId()?.takeIf { it != profile.id })

    fun record(profile: ProxyProfile, latencyMs: Long?) = save(profile.withTestResult(latencyMs), activate = activeId() == profile.id)

    suspend fun selectFastest(
        candidates: List<ProxyProfile> = profiles(),
        maxConcurrent: Int = 3,
        timeoutMs: Long = 8_000,
        test: suspend (ProxyProfile) -> Long?,
    ): ProxyProfile? = coroutineScope {
        val semaphore = Semaphore(maxConcurrent.coerceIn(1, candidates.size.coerceAtLeast(1)))
        candidates.filter { it.enabled && it.isAllowedOn(context) }.map { profile ->
            async {
                semaphore.withPermit {
                    val latency = withTimeoutOrNull(timeoutMs) { test(profile) }
                    profile to latency
                }
            }
        }.awaitAll().mapNotNull { (profile, latency) -> latency?.let { profile.withTestResult(it) } }
            .minWithOrNull(compareBy<ProxyProfile> { it.lastLatencyMs ?: Long.MAX_VALUE }.thenByDescending { it.favorite })
    }
}


data class ProxyProfileFilter(
    val query: String = "",
    val types: Set<ProxyType> = emptySet(),
    val scopes: Set<ProxyNetworkScope> = emptySet(),
    val favoritesOnly: Boolean = false,
    val enabledOnly: Boolean = false,
    val health: Set<ProxyHealth> = emptySet(),
)

fun Iterable<ProxyProfile>.filterProfiles(filter: ProxyProfileFilter): List<ProxyProfile> = filter {
    val query = filter.query.trim().lowercase()
    (query.isEmpty() || it.config.host.lowercase().contains(query) || it.config.type.name.lowercase().contains(query)) &&
        (filter.types.isEmpty() || it.config.type in filter.types) &&
        (filter.scopes.isEmpty() || it.networkScope in filter.scopes) &&
        (!filter.favoritesOnly || it.favorite) &&
        (!filter.enabledOnly || it.enabled) &&
        (filter.health.isEmpty() || it.health in filter.health)
}

