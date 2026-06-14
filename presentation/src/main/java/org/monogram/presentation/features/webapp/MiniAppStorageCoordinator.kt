package org.monogram.presentation.features.webapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.monogram.domain.repository.BotPreferencesProvider
import org.monogram.domain.repository.WebAppRepository
import org.monogram.presentation.features.webapp.MiniAppResponseEmitter.Companion.putNullable

internal class MiniAppStorageCoordinator(
    private val botUserId: Long,
    private val botPreferences: BotPreferencesProvider,
    private val webAppRepository: WebAppRepository,
    private val secureStorage: MiniAppSecureStorage?,
    private val scope: CoroutineScope,
    private val emitter: MiniAppResponseEmitter
) {
    fun handleDeviceStorageSave(reqId: String, key: String, value: String) {
        if (!isValidKey(key, "device_storage_failed", reqId)) return
        botPreferences.saveWebappData(key, value)
        emitter.emit("device_storage_key_saved") { put("req_id", reqId) }
    }

    fun handleDeviceStorageGet(reqId: String, key: String) {
        if (!isValidKey(key, "device_storage_failed", reqId)) return
        emitter.emit("device_storage_key_received") {
            put("req_id", reqId)
            putNullable("value", botPreferences.getWebappData(key))
        }
    }

    fun handleDeviceStorageDelete(reqId: String, key: String) {
        if (!isValidKey(key, "device_storage_failed", reqId)) return
        botPreferences.deleteWebappData(key)
        emitter.emit("device_storage_key_removed") { put("req_id", reqId) }
    }

    fun handleDeviceStorageClear(reqId: String) {
        val keys = botPreferences.getWebappDataKeys().filterNot { it.startsWith(CLOUD_PREFIX) }
        botPreferences.deleteWebappData(keys)
        emitter.emit("device_storage_cleared") { put("req_id", reqId) }
    }

    fun handleSecureStorageSave(reqId: String, key: String, value: String) {
        if (!isValidKey(key, "secure_storage_failed", reqId)) return
        val storage = secureStorage ?: return emitSecureStorageUnavailable(reqId)
        storage.save(key, value)
        emitter.emit("secure_storage_key_saved") { put("req_id", reqId) }
    }

    fun handleSecureStorageGet(reqId: String, key: String) {
        if (!isValidKey(key, "secure_storage_failed", reqId)) return
        val storage = secureStorage ?: return emitSecureStorageUnavailable(reqId)
        emitter.emit("secure_storage_key_received") {
            put("req_id", reqId)
            putNullable("value", storage.get(key))
        }
    }

    fun handleSecureStorageDelete(reqId: String, key: String) {
        if (!isValidKey(key, "secure_storage_failed", reqId)) return
        val storage = secureStorage ?: return emitSecureStorageUnavailable(reqId)
        storage.delete(key)
        emitter.emit("secure_storage_key_removed") { put("req_id", reqId) }
    }

    fun handleSecureStorageRestore(reqId: String, key: String) {
        if (!isValidKey(key, "secure_storage_failed", reqId)) return
        val storage = secureStorage ?: return emitSecureStorageUnavailable(reqId)
        emitter.emit("secure_storage_key_restored") {
            put("req_id", reqId)
            putNullable("value", storage.get(key))
        }
    }

    fun handleSecureStorageClear(reqId: String) {
        val storage = secureStorage ?: return emitSecureStorageUnavailable(reqId)
        storage.delete(storage.getKeys())
        emitter.emit("secure_storage_cleared") { put("req_id", reqId) }
    }

    fun handleCustomMethod(
        reqId: String,
        method: String,
        paramsJson: JSONObject,
        requestedContactProvider: () -> String?,
        onRequestedContactConsumed: () -> Unit
    ) {
        when (method) {
            "getRequestedContact" -> {
                val contact = requestedContactProvider()
                emitter.emitCustomMethodResult(reqId, contact ?: "")
                if (contact != null) {
                    onRequestedContactConsumed()
                }
            }

            "saveDeviceStorageValue" -> onBooleanResult(reqId) {
                val key = paramsJson.optString("key")
                if (key.isEmpty()) return@onBooleanResult CustomMethodFailure("INVALID_PARAMS")
                botPreferences.saveWebappData(key, paramsJson.optString("value"))
                true
            }

            "getDeviceStorageValue" -> emitter.emitCustomMethodResult(
                reqId,
                botPreferences.getWebappData(paramsJson.optString("key"))
            )

            "getDeviceStorageValues" -> {
                val keys = getKeysList(paramsJson, "keys")
                emitter.emitCustomMethodResult(reqId, botPreferences.getWebappData(keys))
            }

            "deleteDeviceStorageValue" -> onBooleanResult(reqId) {
                botPreferences.deleteWebappData(paramsJson.optString("key"))
                true
            }

            "deleteDeviceStorageValues" -> onBooleanResult(reqId) {
                botPreferences.deleteWebappData(getKeysList(paramsJson, "keys"))
                true
            }

            "getDeviceStorageKeys" -> {
                val keys =
                    botPreferences.getWebappDataKeys().filterNot { it.startsWith(CLOUD_PREFIX) }
                emitter.emitCustomMethodResult(reqId, JSONArray(keys))
            }

            "saveSecureStorageValue" -> onBooleanResult(reqId) {
                val key = paramsJson.optString("key")
                if (key.isEmpty()) return@onBooleanResult CustomMethodFailure("INVALID_PARAMS")
                val storage =
                    secureStorage ?: return@onBooleanResult CustomMethodFailure("UNAVAILABLE")
                storage.save(key, paramsJson.optString("value"))
                true
            }

            "getSecureStorageValue" -> {
                val storage = secureStorage
                if (storage == null) {
                    emitter.emitCustomMethodError(reqId, "UNAVAILABLE")
                } else {
                    emitter.emitCustomMethodResult(reqId, storage.get(paramsJson.optString("key")))
                }
            }

            "getSecureStorageValues" -> {
                val storage = secureStorage
                if (storage == null) {
                    emitter.emitCustomMethodError(reqId, "UNAVAILABLE")
                } else {
                    emitter.emitCustomMethodResult(
                        reqId,
                        storage.get(getKeysList(paramsJson, "keys"))
                    )
                }
            }

            "deleteSecureStorageValue" -> onBooleanResult(reqId) {
                val storage =
                    secureStorage ?: return@onBooleanResult CustomMethodFailure("UNAVAILABLE")
                storage.delete(paramsJson.optString("key"))
                true
            }

            "deleteSecureStorageValues" -> onBooleanResult(reqId) {
                val storage =
                    secureStorage ?: return@onBooleanResult CustomMethodFailure("UNAVAILABLE")
                storage.delete(getKeysList(paramsJson, "keys"))
                true
            }

            "getSecureStorageKeys" -> {
                val storage = secureStorage
                if (storage == null) {
                    emitter.emitCustomMethodError(reqId, "UNAVAILABLE")
                } else {
                    emitter.emitCustomMethodResult(reqId, JSONArray(storage.getKeys()))
                }
            }

            "saveStorageValue" -> scope.launch {
                runCloudRequest(reqId) {
                    webAppRepository.saveCloudStorageValue(
                        botUserId = botUserId,
                        key = paramsJson.optString("key"),
                        value = paramsJson.optString("value")
                    )
                }
            }

            "getStorageValue" -> scope.launch {
                runCloudRequest(reqId) {
                    webAppRepository.getCloudStorageValue(botUserId, paramsJson.optString("key"))
                }
            }

            "getStorageValues" -> scope.launch {
                runCloudRequest(reqId) {
                    webAppRepository.getCloudStorageValues(
                        botUserId,
                        getKeysList(paramsJson, "keys")
                    )
                }
            }

            "deleteStorageValue" -> scope.launch {
                runCloudRequest(reqId) {
                    webAppRepository.deleteCloudStorageValue(botUserId, paramsJson.optString("key"))
                }
            }

            "deleteStorageValues" -> scope.launch {
                runCloudRequest(reqId) {
                    webAppRepository.deleteCloudStorageValues(
                        botUserId,
                        getKeysList(paramsJson, "keys")
                    )
                }
            }

            "getStorageKeys" -> scope.launch {
                runCloudRequest(reqId) {
                    JSONArray(webAppRepository.getCloudStorageKeys(botUserId))
                }
            }

            else -> emitter.emitCustomMethodError(reqId, "METHOD_NOT_IMPLEMENTED")
        }
    }

    private inline fun onBooleanResult(reqId: String, block: () -> Any) {
        when (val result = block()) {
            is CustomMethodFailure -> emitter.emitCustomMethodError(reqId, result.error)
            else -> emitter.emitCustomMethodResult(reqId, result)
        }
    }

    private suspend inline fun runCloudRequest(
        reqId: String,
        crossinline block: suspend () -> Any?
    ) {
        runCatching {
            block()
        }.onSuccess { result ->
            emitter.emitCustomMethodResult(reqId, result)
        }.onFailure {
            emitter.emitCustomMethodError(reqId, "UNAVAILABLE")
        }
    }

    private fun isValidKey(key: String, failureEvent: String, reqId: String): Boolean {
        if (key.isNotEmpty()) return true
        emitter.emitStorageFailure(failureEvent, reqId, "KEY_INVALID")
        return false
    }

    private fun emitSecureStorageUnavailable(reqId: String) {
        emitter.emitStorageFailure("secure_storage_failed", reqId, "UNAVAILABLE")
    }

    private fun getKeysList(json: JSONObject, key: String): List<String> {
        val arr = json.optJSONArray(key) ?: return emptyList()
        return List(arr.length()) { arr.getString(it) }
    }

    private data class CustomMethodFailure(val error: String)

    private companion object {
        private const val CLOUD_PREFIX = "cloud_"
    }
}
