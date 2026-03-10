package org.monogram.presentation.core.util

import android.content.Context
import android.content.SharedPreferences
import org.monogram.domain.repository.BotPreferencesProvider

class BotPreferences(context: Context) : BotPreferencesProvider {
    private val webappPermsPrefs: SharedPreferences =
        context.getSharedPreferences("webapp_permissions", Context.MODE_PRIVATE)
    private val webappStoragePrefs: SharedPreferences =
        context.getSharedPreferences("webapp_storage", Context.MODE_PRIVATE)
    private val webappBiometryPrefs: SharedPreferences =
        context.getSharedPreferences("webapp_biometry", Context.MODE_PRIVATE)

    override fun getWebappPermission(botId: Long, permission: String): Boolean {
        return webappPermsPrefs.getBoolean("${botId}_$permission", false)
    }

    override fun setWebappPermission(botId: Long, permission: String, granted: Boolean) {
        webappPermsPrefs.edit().putBoolean("${botId}_$permission", granted).apply()
    }

    override fun isWebappPermissionRequested(botId: Long, permission: String): Boolean {
        return webappPermsPrefs.contains("${botId}_$permission")
    }

    override fun saveWebappData(key: String, value: String) {
        webappStoragePrefs.edit().putString(key, value).apply()
    }

    override fun getWebappData(key: String): String? {
        return webappStoragePrefs.getString(key, null)
    }

    override fun getWebappData(keys: List<String>): Map<String, String?> {
        return keys.associateWith { webappStoragePrefs.getString(it, null) }
    }

    override fun deleteWebappData(key: String) {
        webappStoragePrefs.edit().remove(key).apply()
    }

    override fun deleteWebappData(keys: List<String>) {
        val editor = webappStoragePrefs.edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
    }

    override fun getWebappDataKeys(): List<String> {
        return webappStoragePrefs.all.keys.toList()
    }

    override fun getWebappBiometryDeviceId(botId: Long): String? {
        return webappBiometryPrefs.getString("device_id_$botId", null)
    }

    override fun saveWebappBiometryDeviceId(botId: Long, deviceId: String) {
        webappBiometryPrefs.edit().putString("device_id_$botId", deviceId).apply()
    }

    override fun isWebappBiometryAccessRequested(): Boolean {
        return webappBiometryPrefs.getBoolean("access_requested", false)
    }

    override fun setWebappBiometryAccessRequested(requested: Boolean) {
        webappBiometryPrefs.edit().putBoolean("access_requested", requested).apply()
    }
}
