package org.monogram.presentation.features.webapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.repository.BotPreferencesProvider
import org.monogram.domain.repository.WebAppRepository

class MiniAppStorageCoordinatorTest {
    @Test
    fun `device storage get emits json null and req_id`() {
        val events = mutableListOf<Pair<String, JSONObject?>>()
        val coordinator = MiniAppStorageCoordinator(
            botUserId = 10L,
            botPreferences = FakeBotPreferences(),
            webAppRepository = FakeWebAppRepository(),
            secureStorage = null,
            scope = CoroutineScope(Dispatchers.Unconfined),
            emitter = MiniAppResponseEmitter { type, payload -> events += type to payload }
        )

        coordinator.handleDeviceStorageGet("abc", "missing")

        val (eventType, payload) = events.single()
        assertEquals("device_storage_key_received", eventType)
        assertEquals("abc", payload?.getString("req_id"))
        assertTrue(payload?.isNull("value") == true)
    }

    @Test
    fun `custom method missing secure storage emits unavailable error`() {
        val events = mutableListOf<Pair<String, JSONObject?>>()
        val coordinator = MiniAppStorageCoordinator(
            botUserId = 10L,
            botPreferences = FakeBotPreferences(),
            webAppRepository = FakeWebAppRepository(),
            secureStorage = null,
            scope = CoroutineScope(Dispatchers.Unconfined),
            emitter = MiniAppResponseEmitter { type, payload -> events += type to payload }
        )

        coordinator.handleCustomMethod(
            reqId = "req-1",
            method = "getSecureStorageValue",
            paramsJson = JSONObject().put("key", "token"),
            requestedContactProvider = { null },
            onRequestedContactConsumed = {}
        )

        val (eventType, payload) = events.single()
        assertEquals("custom_method_invoked", eventType)
        assertEquals("req-1", payload?.getString("req_id"))
        assertEquals("UNAVAILABLE", payload?.getString("error"))
    }

    private class FakeBotPreferences : BotPreferencesProvider {
        private val data = linkedMapOf<String, String>()

        override fun getWebappPermission(botId: Long, permission: String): Boolean = false
        override fun setWebappPermission(botId: Long, permission: String, granted: Boolean) = Unit
        override fun isWebappPermissionRequested(botId: Long, permission: String): Boolean = false
        override fun saveWebappData(key: String, value: String) {
            data[key] = value
        }

        override fun getWebappData(key: String): String? = data[key]
        override fun getWebappData(keys: List<String>): Map<String, String?> =
            keys.associateWith { data[it] }

        override fun deleteWebappData(key: String) {
            data.remove(key)
        }

        override fun deleteWebappData(keys: List<String>) {
            keys.forEach(data::remove)
        }

        override fun getWebappDataKeys(): List<String> = data.keys.toList()
        override fun getWebappBiometryDeviceId(botId: Long): String? = null
        override fun saveWebappBiometryDeviceId(botId: Long, deviceId: String) = Unit
        override fun isWebappBiometryAccessRequested(): Boolean = false
        override fun setWebappBiometryAccessRequested(requested: Boolean) = Unit
    }

    private class FakeWebAppRepository : WebAppRepository {
        override suspend fun openWebApp(
            chatId: Long,
            botUserId: Long,
            url: String,
            themeParams: org.monogram.domain.models.webapp.ThemeParams?
        ) = null

        override suspend fun closeWebApp(launchId: Long) = Unit
        override suspend fun sendWebAppResult(launchId: Long, queryId: String) = Unit
        override suspend fun saveCloudStorageValue(
            botUserId: Long,
            key: String,
            value: String
        ): Boolean = true

        override suspend fun getCloudStorageValue(botUserId: Long, key: String): String? = null
        override suspend fun getCloudStorageValues(
            botUserId: Long,
            keys: List<String>
        ): Map<String, String?> = emptyMap()

        override suspend fun deleteCloudStorageValue(botUserId: Long, key: String): Boolean = true
        override suspend fun deleteCloudStorageValues(
            botUserId: Long,
            keys: List<String>
        ): Boolean = true

        override suspend fun getCloudStorageKeys(botUserId: Long): List<String> = emptyList()
    }
}
