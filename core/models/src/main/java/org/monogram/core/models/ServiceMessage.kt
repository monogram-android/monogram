package org.monogram.core.models

import java.util.Locale

const val SERVICE_UNIT = '\u001F'

data class ServiceMessageParts(
    val kind: String,
    val actor: String,
    val extra: String,
)

fun parseServiceMessage(raw: String): ServiceMessageParts? {
    if (!raw.contains(SERVICE_UNIT)) return null
    val bits = raw.split(SERVICE_UNIT, limit = 3)
    if (bits.isEmpty() || bits[0].isBlank()) return null
    return ServiceMessageParts(
        kind = bits[0],
        actor = bits.getOrElse(1) { "" },
        extra = bits.getOrElse(2) { "" },
    )
}

fun applyServiceTemplate(pattern: String, actor: String, extra: String): String =
    try {
        String.format(Locale.US, pattern, actor, extra)
    } catch (_: Exception) {
        pattern
    }

fun formatServiceMessage(
    raw: String,
    youLabel: String,
    outgoing: Boolean,
    someoneLabel: String,
    templateFor: (String) -> String,
): String {
    val parts = parseServiceMessage(raw) ?: return raw.trim()
    val actor = when {
        outgoing -> youLabel
        parts.actor.isNotBlank() -> parts.actor
        else -> someoneLabel
    }
    return applyServiceTemplate(templateFor(parts.kind), actor, parts.extra)
}
