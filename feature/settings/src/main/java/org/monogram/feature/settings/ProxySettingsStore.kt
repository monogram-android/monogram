package org.monogram.feature.settings

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.network.bridge.MtprotoTransportMode
import org.monogram.network.bridge.ProxyConfig
import org.monogram.network.bridge.ProxyType

data class StoredProxy(
    val kind: String,
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val secret: ByteArray,
    val transportMode: String = "padded_intermediate",
    val id: String = "",
    val networkScope: String = "ALWAYS",
    val enabled: Boolean = true,
    val favorite: Boolean = false,
    val lastLatencyMs: Long? = null,
    val consecutiveFailures: Int = 0,
    val lastCheckedAt: Long? = null,
) {
    val profileKey: String
        get() = id.ifBlank { listOf(kind, host, port, username.orEmpty(), secret.contentHashCode(), transportMode).joinToString("\u001e") }

    override fun toString(): String = "StoredProxy([REDACTED])"
}
data class ProxyStoreSnapshot(
    val profiles: List<StoredProxy>,
    val activeProfileKey: String?,
)

object ProxySettingsStore {
    private const val preferences = "proxy_settings"
    private const val valueKey = "encrypted_config"
    private const val activeKey = "active_profile"
    private const val startupFailureKey = "startup_failure"
    private const val keyAlias = "monogram_proxy_config"
    private const val separator = "\u001f"

    fun load(context: Context): StoredProxy? = loadSnapshot(context).let { snapshot ->
        snapshot.profiles.firstOrNull { it.profileKey == snapshot.activeProfileKey }
    }

    fun loadSnapshot(context: Context): ProxyStoreSnapshot {
        val profiles = loadAll(context)
        val preferences = context.getSharedPreferences(preferences, Context.MODE_PRIVATE)
        val active = if (preferences.contains(activeKey)) {
            preferences.getString(activeKey, null)
                ?.takeIf { key -> profiles.any { it.profileKey == key } }
        } else {
            profiles.firstOrNull()?.profileKey
        }
        return ProxyStoreSnapshot(profiles, active)
    }
    fun loadAll(context: Context): List<StoredProxy> = runCatching {
        val encoded = context.getSharedPreferences(preferences, Context.MODE_PRIVATE)
            .getString(valueKey, null) ?: return@runCatching emptyList()
        val plaintext = decrypt(encoded)
        if (plaintext.trimStart().startsWith("[")) {
            val array = JSONArray(plaintext)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                parseProxy(
                    listOf(
                        item.getString("kind"),
                        item.getString("host"),
                        item.getString("port"),
                        item.optString("username"),
                        item.optString("password"),
                        item.getString("secret"),
                        item.optString("transportMode", "padded_intermediate"),
                        item.optString("id"),
                        item.optString("networkScope", "ALWAYS"),
                        item.optBoolean("enabled", true).toString(),
                        item.optBoolean("favorite", false).toString(),
                        item.optString("lastLatencyMs"),
                        item.optString("consecutiveFailures", "0"),
                        item.optString("lastCheckedAt"),
                    ),
                )
            }
        } else {
            listOf(parseProxy(plaintext.split(separator)))
        }
    }.getOrElse {
        AppLog.warn("proxy", "stored proxy settings unavailable")
        emptyList()
    }


    fun saveProfiles(context: Context, profiles: List<ProxyProfile>, activeId: String? = null) {
        saveAll(context, profiles.map { profile ->
            StoredProxy(
                kind = profile.config.type.name,
                host = profile.config.host,
                port = profile.config.port,
                username = profile.config.username,
                password = profile.config.password,
                secret = profile.config.secret,
                transportMode = profile.transportMode.name.lowercase(),
                id = profile.id,
                networkScope = profile.networkScope.name,
                enabled = profile.enabled,
                favorite = profile.favorite,
                lastLatencyMs = profile.lastLatencyMs,
                consecutiveFailures = profile.consecutiveFailures,
                lastCheckedAt = profile.lastCheckedAt,
            )
        }, activeId)
    }
    fun save(context: Context, proxy: StoredProxy) {
        saveAll(context, listOf(proxy), proxy.profileKey)
    }

    fun saveAll(context: Context, proxies: List<StoredProxy>, activeProfileKey: String? = null) {
        val payload = JSONArray().apply {
            proxies.forEach { proxy ->
                put(JSONObject().apply {
                    put("kind", proxy.kind)
                    put("host", proxy.host)
                    put("port", proxy.port)
                    put("username", proxy.username.orEmpty())
                    put("password", proxy.password.orEmpty())
                    put("secret", Base64.encodeToString(proxy.secret, Base64.NO_WRAP))
                    put("transportMode", proxy.transportMode)
                    put("id", proxy.id)
                    put("networkScope", proxy.networkScope)
                    put("enabled", proxy.enabled)
                    put("favorite", proxy.favorite)
                    proxy.lastLatencyMs?.let { put("lastLatencyMs", it) }
                    put("consecutiveFailures", proxy.consecutiveFailures)
                    proxy.lastCheckedAt?.let { put("lastCheckedAt", it) }
                })
            }
        }.toString()
        context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit()
            .putString(valueKey, encrypt(payload))
            .putString(activeKey, activeProfileKey ?: "")
            .apply()
    }

    fun remove(context: Context, proxy: StoredProxy) {
        val snapshot = loadSnapshot(context)
        val remaining = snapshot.profiles.filterNot { it.profileKey == proxy.profileKey }
        saveAll(
            context,
            remaining,
            snapshot.activeProfileKey.takeIf { it != proxy.profileKey },
        )
    }

    fun setActive(context: Context, profile: StoredProxy?) {
        context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit()
            .putString(activeKey, profile?.profileKey ?: "")
            .apply()
    }

    fun clearActive(context: Context) = setActive(context, null)

    fun markStartupFailure(context: Context) {
        context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit()
            .putBoolean(startupFailureKey, true)
            .apply()
    }

    fun consumeStartupFailure(context: Context): Boolean {
        val preferences = context.getSharedPreferences(preferences, Context.MODE_PRIVATE)
        val pending = preferences.getBoolean(startupFailureKey, false)
        if (pending) preferences.edit().remove(startupFailureKey).apply()
        return pending
    }

    fun clear(context: Context) {
        context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit()
            .remove(valueKey)
            .remove(activeKey)
            .remove(startupFailureKey)
            .apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder(
                keyAlias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
        return String(cipher.doFinal(payload.copyOfRange(12, payload.size)), StandardCharsets.UTF_8)
    }

    private fun parseProxy(payload: List<String>): StoredProxy {
        require(payload.size in 6..14)
        return StoredProxy(
            kind = payload[0],
            host = payload[1],
            port = payload[2].toInt(),
            username = payload[3].ifEmpty { null },
            password = payload[4].ifEmpty { null },
            secret = Base64.decode(payload[5], Base64.NO_WRAP),
            transportMode = payload.getOrNull(6).orEmpty().ifEmpty { "padded_intermediate" },
            id = payload.getOrNull(7).orEmpty(),
            networkScope = payload.getOrNull(8).orEmpty().ifEmpty { "ALWAYS" },
            enabled = payload.getOrNull(9)?.toBooleanStrictOrNull() ?: true,
            favorite = payload.getOrNull(10)?.toBooleanStrictOrNull() ?: false,
            lastLatencyMs = payload.getOrNull(11)?.toLongOrNull(),
            consecutiveFailures = payload.getOrNull(12)?.toIntOrNull() ?: 0,
            lastCheckedAt = payload.getOrNull(13)?.toLongOrNull(),
        ).also { proxy ->
            val type = ProxyType.valueOf(proxy.kind)
            require(type != ProxyType.NONE)
            val mode = MtprotoTransportMode.valueOf(proxy.transportMode.uppercase())
            require(type != ProxyType.MTPROTO || mode != MtprotoTransportMode.HTTP)
            require(ProxyConfig(type, proxy.host, proxy.port, proxy.username, proxy.password, proxy.secret).validate() is Outcome.Ok)
        }
    }
}





