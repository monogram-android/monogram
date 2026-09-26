package org.monogram.core.common.push

import org.monogram.core.models.PushTokenType

/** UnifiedPush Web Push JSON (token_type=10), else simple-push endpoint (token_type=4). */
fun webPushRegistration(endpoint: String, p256dh: String?, auth: String?): Pair<PushTokenType, String> {
    if (p256dh.isNullOrBlank() || auth.isNullOrBlank()) {
        return PushTokenType.Simple to endpoint
    }
    val json = buildString {
        append("{\"endpoint\":")
        append(jsonStringLiteral(endpoint))
        append(",\"keys\":{\"p256dh\":")
        append(jsonStringLiteral(p256dh))
        append(",\"auth\":")
        append(jsonStringLiteral(auth))
        append("}}")
    }
    return PushTokenType.WebPush to json
}

private fun jsonStringLiteral(value: String): String =
    buildString(value.length + 2) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(ch)
            }
        }
        append('"')
    }
