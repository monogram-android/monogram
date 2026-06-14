package org.monogram.presentation.features.webapp

import org.json.JSONArray
import org.json.JSONObject

internal class MiniAppResponseEmitter(
    private val dispatch: (String, JSONObject?) -> Unit
) {
    fun emit(eventType: String, payloadBuilder: JSONObject.() -> Unit = {}) {
        dispatch(eventType, JSONObject().apply(payloadBuilder))
    }

    fun emitCustomMethodResult(reqId: String, result: Any?) {
        emit("custom_method_invoked") {
            put("req_id", reqId)
            putNullable("result", result)
        }
    }

    fun emitCustomMethodError(reqId: String, error: String) {
        emit("custom_method_invoked") {
            put("req_id", reqId)
            put("error", error)
        }
    }

    fun emitStorageFailure(eventType: String, reqId: String, error: String) {
        emit(eventType) {
            put("req_id", reqId)
            put("error", error)
        }
    }

    companion object {
        fun JSONObject.putNullable(key: String, value: Any?) {
            put(
                key,
                when (value) {
                    null -> JSONObject.NULL
                    is Map<*, *> -> JSONObject(value)
                    is Iterable<*> -> JSONArray(value.toList())
                    else -> value
                }
            )
        }
    }
}
