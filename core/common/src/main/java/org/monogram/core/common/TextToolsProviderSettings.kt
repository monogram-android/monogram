package org.monogram.core.common

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores optional text-tool provider settings without exposing API keys in preferences. */
data class TextToolsProviderConfig(
    val provider: String = "telegram",
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val apiKey: String = "",
) {
    val isCustom: Boolean get() = provider == "openai-compatible"

    override fun toString(): String =
        "TextToolsProviderConfig(provider=$provider, baseUrl=$baseUrl, model=$model, apiKey=[REDACTED])"
}

object TextToolsProviderSettings {
    private const val preferencesName = "text_tools_provider"
    private const val payloadKey = "encrypted_configs"
    private const val keyAlias = "monogram_text_tools_provider"

    @Volatile
    private var context: Context? = null

    @Volatile
    private var activeAccountId: Long = 0

    fun install(applicationContext: Context) {
        context = applicationContext.applicationContext
    }

    fun setActiveAccount(accountId: Long) {
        activeAccountId = accountId
    }

    fun current(): TextToolsProviderConfig = load(activeAccountId)

    fun activeAccount(): Long = activeAccountId

    @Synchronized
    fun load(accountId: Long): TextToolsProviderConfig {
        val app = context ?: return TextToolsProviderConfig()
        return runCatching {
            val encoded =
                app
                    .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                    .getString(payloadKey, null) ?: return@runCatching TextToolsProviderConfig()
            val rows = JSONArray(decrypt(encoded))
            (0 until rows.length()).firstNotNullOfOrNull { index ->
                val row = rows.getJSONObject(index)
                if (row.optLong("account", Long.MIN_VALUE) == accountId) {
                    TextToolsProviderConfig(
                        provider = row.optString("provider", "telegram"),
                        baseUrl = row.optString("baseUrl", "https://api.openai.com/v1"),
                        model = row.optString("model", "gpt-4o-mini"),
                        apiKey = row.optString("apiKey"),
                    )
                } else {
                    null
                }
            } ?: TextToolsProviderConfig()
        }.getOrElse { TextToolsProviderConfig(provider = "openai-compatible") }
    }

    @Synchronized
    fun save(
        accountId: Long,
        config: TextToolsProviderConfig,
    ) {
        val app = checkNotNull(context) { "Text tools settings are not initialized" }
        val encoded = app.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .getString(payloadKey, null)
        val rows = encoded?.let { JSONArray(decrypt(it)) } ?: JSONArray()
        var replaced = false
        for (index in 0 until rows.length()) {
            if (rows.getJSONObject(index).optLong("account", Long.MIN_VALUE) == accountId) {
                rows.put(index, config.toJson(accountId))
                replaced = true
                break
            }
        }
        if (!replaced) rows.put(config.toJson(accountId))
        app
            .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(payloadKey, encrypt(rows.toString()))
            .apply()
    }

    @Synchronized
    fun clear(accountId: Long) {
        val app = context ?: return
        val encoded =
            app
                .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                .getString(payloadKey, null) ?: return
        val old = runCatching { JSONArray(decrypt(encoded)) }.getOrNull() ?: return
        val rows = JSONArray()
        for (index in 0 until old.length()) {
            if (old.getJSONObject(index).optLong("account", Long.MIN_VALUE) != accountId) rows.put(
                old.get(index)
            )
        }
        app
            .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(payloadKey, encrypt(rows.toString()))
            .apply()
    }

    private fun TextToolsProviderConfig.toJson(accountId: Long) =
        JSONObject().apply {
            put("account", accountId)
            put("provider", provider)
            put("baseUrl", baseUrl)
            put("model", model)
            put("apiKey", apiKey)
        }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator
            .getInstance("AES", "AndroidKeyStore")
            .apply {
                init(
                    android.security.keystore.KeyGenParameterSpec
                        .Builder(
                            keyAlias,
                            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(
            cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)),
            Base64.NO_WRAP
        )
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
        return String(cipher.doFinal(payload.copyOfRange(12, payload.size)), StandardCharsets.UTF_8)
    }
}
