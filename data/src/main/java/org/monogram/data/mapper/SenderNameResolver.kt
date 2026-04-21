package org.monogram.data.mapper

internal object SenderNameResolver {
    fun fromPartsOrBlank(firstName: String?, lastName: String?): String {
        return listOfNotNull(
            firstName?.takeIf { it.isNotBlank() },
            lastName?.takeIf { it.isNotBlank() }
        ).joinToString(" ")
    }

    fun fromParts(firstName: String?, lastName: String?, fallback: String = ""): String {
        val normalizedFallback = fallback.ifBlank { "" }
        return fromPartsOrBlank(firstName, lastName).ifBlank { normalizedFallback }
    }
}