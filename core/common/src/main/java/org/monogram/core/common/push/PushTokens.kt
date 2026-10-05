package org.monogram.core.common.push

import org.monogram.core.models.PushTokenType

enum class PushProviderMode {
    Auto,
    ForceFcm,
    ForceUnifiedPush,
    Off,
    ;

    companion object {
        fun fromStored(value: String?): PushProviderMode =
            entries.firstOrNull { it.name == value } ?: Auto
    }
}

enum class PushTransport { Fcm, UnifiedPush }

data class PushProviderPlan(
    val mode: PushProviderMode,
    val register: PushTransport? = null,
    val status: String? = null,
)

const val NO_PUSH_STATUS = "notifications will only arrive while Monogram is connected"
const val PLAY_SERVICES_UNAVAILABLE = "Play services unavailable"
const val SIMPLE_PUSH_PUT_WARNING = "simple push depends on the distributor accepting PUT"
const val PUSH_STATUS_UP_FAILED = "unifiedpush failed"
const val PUSH_STATUS_NO_DISTRIBUTOR = "no UnifiedPush distributor"
const val PUSH_STATUS_CHOOSE_DISTRIBUTOR = "choose a distributor"
const val PUSH_STATUS_DISTRIBUTOR_GONE = "distributor unavailable"

data class SimplePushRegistration(
    val token: String,
    val warning: String? = null,
)

/** Blank gateway keeps the distributor endpoint. A base URL is the token Telegram PUTs; it must forward to `endpoint`. */
fun simplePushEndpoint(distributorEndpoint: String, gatewayBase: String): SimplePushRegistration {
    val gateway = gatewayBase.trim()
    if (gateway.isEmpty()) return SimplePushRegistration(distributorEndpoint, SIMPLE_PUSH_PUT_WARNING)
    val encoded = java.net.URLEncoder.encode(distributorEndpoint, Charsets.UTF_8.name())
    return SimplePushRegistration("${gateway.trimEnd('/')}/?endpoint=$encoded")
}

fun endpointHost(token: String, type: PushTokenType?): String {
    val url = when (type) {
        PushTokenType.WebPush -> jsonStringField(token, "endpoint")
        PushTokenType.Simple -> token
        else -> return ""
    }
    return runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
}

fun acceptsFcmToken(register: PushTransport?): Boolean = register == PushTransport.Fcm

/** Force FCM and Off ignore distributor endpoints. Auto accepts one only when the plan selected UnifiedPush. */
fun acceptsUnifiedPushEndpoint(mode: PushProviderMode, register: PushTransport?): Boolean = when (mode) {
    PushProviderMode.Off, PushProviderMode.ForceFcm -> false
    PushProviderMode.ForceUnifiedPush -> true
    PushProviderMode.Auto -> register == PushTransport.UnifiedPush
}

fun showsUnifiedPushSettings(mode: PushProviderMode, playServices: Boolean, transport: String): Boolean =
    when (mode) {
        PushProviderMode.ForceUnifiedPush -> true
        PushProviderMode.Auto -> !playServices || transport == "simple" || transport == "webpush"
        else -> false
    }

private fun jsonStringField(json: String, name: String): String {
    val key = "\"$name\":\""
    val start = json.indexOf(key)
    if (start < 0) return ""
    val from = start + key.length
    val end = json.indexOf('"', from)
    if (end < 0) return ""
    return json.substring(from, end)
}

/**
 * Auto uses FCM only when Play services and Firebase are both configured.
 * Force FCM without Play services falls back to Auto and explains why.
 * Force UnifiedPush never selects FCM. Off registers nothing.
 */
fun planPushProvider(
    requested: PushProviderMode,
    playServices: Boolean,
    firebaseConfigured: Boolean,
    unifiedPushAvailable: Boolean,
): PushProviderPlan {
    if (requested == PushProviderMode.Off) {
        return PushProviderPlan(mode = PushProviderMode.Off, status = "push off")
    }
    val coerced = requested == PushProviderMode.ForceFcm && !playServices
    val mode = if (coerced) PushProviderMode.Auto else requested
    return when (mode) {
        PushProviderMode.Off -> PushProviderPlan(mode = mode, status = "push off")
        PushProviderMode.ForceFcm -> PushProviderPlan(mode = mode, register = PushTransport.Fcm)
        PushProviderMode.ForceUnifiedPush -> PushProviderPlan(
            mode = mode,
            register = PushTransport.UnifiedPush,
        )
        PushProviderMode.Auto -> when {
            playServices && firebaseConfigured -> PushProviderPlan(mode = mode, register = PushTransport.Fcm)
            unifiedPushAvailable -> PushProviderPlan(
                mode = mode,
                register = PushTransport.UnifiedPush,
                status = if (coerced) PLAY_SERVICES_UNAVAILABLE else null,
            )
            else -> PushProviderPlan(
                mode = mode,
                status = if (coerced) "$PLAY_SERVICES_UNAVAILABLE. $NO_PUSH_STATUS" else NO_PUSH_STATUS,
            )
        }
    }
}

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

/**
 * Previous device registration to drop before registering [nextType].
 * Same type and token is a refresh and must not unregister.
 * Leaving UnifiedPush for FCM also drops the distributor registration.
 */
data class PushRegistrationChange(
    val previous: Pair<PushTokenType, String>? = null,
    val unregisterUnifiedPush: Boolean = false,
)

fun pushRegistrationChange(
    previousType: PushTokenType?,
    previousToken: String,
    nextType: PushTokenType,
    nextToken: String,
): PushRegistrationChange {
    if (previousType == null || previousToken.isBlank()) return PushRegistrationChange()
    if (previousType == nextType && previousToken == nextToken) return PushRegistrationChange()
    return PushRegistrationChange(
        previous = previousType to previousToken,
        unregisterUnifiedPush = previousType != PushTokenType.Fcm && nextType == PushTokenType.Fcm,
    )
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
