package org.monogram.core.database

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

class StoredPayloadException :
    IllegalStateException("Stored payload is invalid or unsupported")

internal inline fun <T> readStoredPayload(block: () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: IllegalArgumentException) {
    throw StoredPayloadException()
} catch (_: IllegalStateException) {
    throw StoredPayloadException()
}

internal val storedPayloadJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
